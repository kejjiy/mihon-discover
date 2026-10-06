package mihon.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class LanDevice(
    val id: String,
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
    val instance: String,
    val seen: Long = System.currentTimeMillis(),
    val manual: Boolean = false,
)
data class PairPrompt(val pairId: String, val name: String, val code: String, val approved: Boolean = false)
data class LanState(
    val enabled: Boolean = false,
    val devices: List<LanDevice> = emptyList(),
    val trusted: Map<String, String> = emptyMap(),
    val pairing: List<PairPrompt> = emptyList(),
    val status: String = "Active la découverte pour trouver tes appareils.",
    val addresses: List<String> = emptyList(),
    val busy: Boolean = false,
)

private data class PendingPair(
    val device: LanDevice,
    val key: ByteArray,
    val outgoing: Boolean,
    val expires: Long,
    @Volatile var approved: Boolean = false,
    @Volatile var completed: Boolean = false,
)
private data class TrustedPeer(val name: String, val key: ByteArray)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LanSyncViewModel(private val context: Context, private val library: LanLibraryAdapter) : ViewModel() {
    private val _state = MutableStateFlow(LanState())
    val state = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("lan_sync", Context.MODE_PRIVATE)
    private val vaultKey: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mihon-lan-sync", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        "mihon-lan-sync",
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(
                            KeyProperties.BLOCK_MODE_GCM,
                        ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
                )
            }.generateKey()
    }
    private val trusted = ConcurrentHashMap<String, TrustedPeer>()
    private val devices = ConcurrentHashMap<String, LanDevice>()
    private val pending = ConcurrentHashMap<String, PendingPair>()
    private val replays = ConcurrentHashMap<String, Long>()
    private val rates = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val activeConnections = AtomicInteger()
    private var server: ServerSocket? = null
    private var multicast: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkJob: Job? = null
    private var port = 0
    private var instance = UUID.randomUUID().toString()
    private val name = "${Build.MANUFACTURER} ${Build.MODEL}".take(60)

    init {
        runCatching {
            val stored = prefs.getString("peers", null) ?: return@runCatching
            val envelope = syncJson.parseToJsonElement(stored).jsonObject
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                vaultKey,
                javax.crypto.spec.GCMParameterSpec(128, unb64(envelope.string("iv"))),
            )
            val data = syncJson.parseToJsonElement(
                cipher.doFinal(unb64(envelope.string("data"))).toString(Charsets.UTF_8),
            ).jsonObject
            data.forEach { (id, item) ->
                trusted[id] =
                    TrustedPeer(item.jsonObject.string("name"), unb64(item.jsonObject.string("key")))
            }
        }.onFailure {
            _state.update {
                it.copy(status = "Les associations sauvegardées sont illisibles. Associe à nouveau tes appareils.")
            }
        }
        changed()
    }

    @Synchronized
    private fun savePeers() {
        val data =
            JsonObject(
                trusted.mapValues { (_, peer) ->
                    buildJsonObject {
                        put("name", peer.name)
                        put("key", b64(peer.key))
                    }
                },
            )
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, vaultKey)
        val envelope =
            buildJsonObject {
                put("iv", b64(cipher.iv))
                put("data", b64(cipher.doFinal(data.toString().toByteArray())))
            }
        check(prefs.edit().putString("peers", envelope.toString()).commit())
    }

    private fun deviceJson() = buildJsonObject {
        put("id", library.deviceId)
        put("name", name)
        put("platform", "android")
        put("port", port)
        put("instance", instance)
    }

    private fun changed() {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                trusted = trusted.mapValues { (_, peer) -> peer.name },
                devices = devices.values.filter { device ->
                    device.manual || now - device.seen < 20000
                }.sortedBy { device -> device.name },
                pairing = pending.filterValues { !it.completed }.map { (id, pair) ->
                    PairPrompt(id, pair.device.name, SyncCrypto.code(pair.key), pair.approved)
                },
            )
        }
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (
                e: Exception,
            ) {
                _state.update { it.copy(status = e.message ?: "Connexion impossible") }
            } finally {
                _state.update { it.copy(busy = false) }
                changed()
            }
        }
    }

    fun start() = action {
        if (server != null) return@action
        val listener = ServerSocket(0).also { server = it }
        port = listener.localPort
        instance = UUID.randomUUID().toString()
        val addresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it.address.size == 4 && it.isSiteLocalAddress }
        _state.update {
            it.copy(
                enabled = true,
                addresses = addresses.map { address ->
                    "${address.hostAddress}:$port"
                },
                status = "Découverte active. Garde cet écran ouvert pendant les échanges.",
            )
        }
        networkJob = viewModelScope.launch(Dispatchers.IO) {
            launch {
                while (isActive && !listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: Exception) {
                        break
                    }
                    if (!socket.inetAddress.isSiteLocalAddress &&
                        !socket.inetAddress.isLoopbackAddress
                    ) {
                        socket.close()
                        continue
                    }
                    if (activeConnections.incrementAndGet() >
                        8
                    ) {
                        activeConnections.decrementAndGet()
                        socket.close()
                        continue
                    }
                    connections += socket
                    launch {
                        socket.use {
                            it.soTimeout = 30000
                            try {
                                writeFrame(it, receive(readFrame(it), it.inetAddress.hostAddress!!))
                            } catch (
                                _: Exception,
                            ) {
                                runCatching { writeFrame(it, buildJsonObject { put("error", "Requête refusée") }) }
                            } finally {
                                connections -= socket
                                activeConnections.decrementAndGet()
                            }
                        }
                    }
                }
            }
            launch {
                try {
                    multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .createMulticastLock("mihon-discover-sync").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                    val udp = MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(SYNC_PORT))
                        timeToLive = 1
                        soTimeout = 5000
                    }.also { multicast = it }
                    addresses.forEach { address ->
                        udp.joinGroup(
                            InetSocketAddress(SYNC_GROUP, SYNC_PORT),
                            NetworkInterface.getByInetAddress(address),
                        )
                    }
                    launch {
                        while (isActive && !udp.isClosed) {
                            val body = buildJsonObject {
                                put("app", "mihon-discover")
                                put("version", SYNC_VERSION)
                                put("device", deviceJson())
                            }.toString().toByteArray()
                            addresses.forEach { address ->
                                runCatching {
                                    udp.networkInterface = NetworkInterface.getByInetAddress(address)
                                    udp.send(
                                        DatagramPacket(body, body.size, InetAddress.getByName(SYNC_GROUP), SYNC_PORT),
                                    )
                                }
                            }
                            val now = System.currentTimeMillis()
                            pending.entries.removeAll { now > it.value.expires }
                            replays.entries.removeAll { now - it.value > 300_000 }
                            rates.entries.removeAll { now - it.value.first > 60_000 }
                            changed()
                            delay(4000)
                        }
                    }
                    val bytes = ByteArray(2048)
                    while (isActive && !udp.isClosed) {
                        val packet = DatagramPacket(bytes, bytes.size)
                        try {
                            udp.receive(packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                        if (!packet.address.isSiteLocalAddress) continue
                        runCatching {
                            val msg = syncJson.parseToJsonElement(
                                String(packet.data, packet.offset, packet.length),
                            ).jsonObject
                            if (msg.string("app") != "mihon-discover" ||
                                msg.number("version") != SYNC_VERSION.toLong()
                            ) {
                                return@runCatching
                            }
                            val device = parseDevice(msg.getValue("device").jsonObject, packet.address.hostAddress!!)
                            if (device.id != library.deviceId) {
                                devices[device.id] = device
                                changed()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (_state.value.enabled) {
                        _state.update {
                            it.copy(status = "Découverte indisponible (${e.message}). Utilise une adresse manuelle.")
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        _state.update {
            it.copy(
                enabled = false,
                busy = false,
                status = "Découverte arrêtée. Aucun échange réseau actif.",
                addresses = emptyList(),
            )
        }
        networkJob?.cancel()
        networkJob = null
        runCatching { server?.close() }
        server = null
        runCatching { multicast?.close() }
        multicast = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        connections.forEach { runCatching { it.close() } }
        pending.clear()
        devices.clear()
        changed()
    }

    fun manual(host: String, remotePort: Int) = action {
        require(_state.value.enabled)
        val reply = request(
            host,
            remotePort,
            buildJsonObject {
                put("type", "info")
                put("version", SYNC_VERSION)
            },
        )
        val device = parseDevice(reply.getValue("device").jsonObject, host)
        require(device.id != library.deviceId)
        devices[device.id] = device.copy(manual = true)
    }

    fun pair(id: String) = action {
        require(_state.value.enabled)
        require(trusted[id] == null) { "Appareil déjà associé" }
        val device = devices[id] ?: error("Appareil absent")
        val pair = SyncCrypto.keyPair()
        val reply = request(
            device.host,
            device.port,
            buildJsonObject {
                put("type", "hello")
                put("version", SYNC_VERSION)
                put("device", deviceJson())
                put("publicKey", SyncCrypto.publicKey(pair))
            },
        )
        require(parseDevice(reply.getValue("device").jsonObject, device.host).id == id)
        val pairId = reply.string("pairId")
        require(pairId.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        pending[pairId] =
            PendingPair(
                device,
                SyncCrypto.sharedKey(pair, reply.string("publicKey"), library.deviceId, id),
                true,
                System.currentTimeMillis() + 120000,
            )
        _state.update { it.copy(status = "Compare le code sur les deux appareils avant d'accepter.") }
    }

    fun approve(pairId: String, accepted: Boolean) = action {
        val pair = pending[pairId] ?: error("Association expirée")
        if (!accepted) {
            pending.remove(pairId)
            return@action
        }
        pair.approved = true
        changed()
        if (!pair.outgoing) return@action
        while (_state.value.enabled && pending.containsKey(pairId) && System.currentTimeMillis() < pair.expires) {
            val reply = request(
                pair.device.host,
                pair.device.port,
                buildJsonObject {
                    put("type", "confirm")
                    put("from", library.deviceId)
                    put("pairId", pairId)
                    put(
                        "envelope",
                        SyncCrypto.encrypt(
                            pair.key,
                            "{\"accepted\":true}",
                            "pair:$pairId:${library.deviceId}->${pair.device.id}",
                        ),
                    )
                },
            )
            val result = syncJson.parseToJsonElement(
                SyncCrypto.decrypt(
                    pair.key,
                    reply.getValue("envelope").jsonObject,
                    "pair-reply:$pairId:${pair.device.id}->${library.deviceId}",
                ),
            ).jsonObject
            if (result.bool("paired")) {
                trusted[pair.device.id] = TrustedPeer(pair.device.name, pair.key)
                savePeers()
                pending.remove(pairId)
                _state.update { it.copy(status = "Appareil associé. Tu peux synchroniser.") }
                return@action
            }
            delay(800)
        }
        pending.remove(pairId)
        error("Association annulée ou expirée")
    }

    fun forget(id: String) = action {
        trusted.remove(id)
        pending.entries.removeAll { it.value.device.id == id }
        savePeers()
    }

    fun sync(id: String) = action { synchronize(id) }

    fun syncAll() = action {
        val peers = _state.value.devices.filter { trusted.containsKey(it.id) }
        require(peers.isNotEmpty()) { "Aucun appareil associé disponible" }
        // A second pass distributes changes received from later peers to the earlier ones.
        repeat(2) { peers.forEach { synchronize(it.id) } }
        _state.update { it.copy(status = "${peers.size} appareil(s) synchronisé(s).") }
    }

    private suspend fun synchronize(id: String) {
        _state.update { it.copy(status = "Synchronisation en cours…") }
        val snapshot = library.snapshot()
        val response = rpc(id, "merge", syncJson.parseToJsonElement(syncJson.encodeToString(snapshot)).jsonObject)
        val merged = library.merge(syncJson.decodeFromString<SyncSnapshot>(response.toString()))
        // Second exchange converges changes made locally while the first request was in flight.
        rpc(id, "merge", syncJson.parseToJsonElement(syncJson.encodeToString(merged)).jsonObject)
        _state.update { it.copy(status = "Synchronisation terminée : ${merged.records.size} éléments.") }
    }

    private suspend fun rpc(id: String, op: String, payload: JsonObject): JsonObject {
        require(_state.value.enabled)
        val peer = trusted[id] ?: error("Appareil non associé")
        val device = devices[id] ?: error("Appareil injoignable")
        val requestId = UUID.randomUUID().toString()
        val body =
            buildJsonObject {
                put("id", requestId)
                put("time", System.currentTimeMillis())
                put("instance", device.instance)
                put("op", op)
                put("payload", payload)
            }
        val reply = request(
            device.host,
            device.port,
            buildJsonObject {
                put("type", "rpc")
                put("from", library.deviceId)
                put("envelope", SyncCrypto.encrypt(peer.key, body.toString(), "rpc:${library.deviceId}->$id"))
            },
        )
        val result = syncJson.parseToJsonElement(
            SyncCrypto.decrypt(peer.key, reply.getValue("envelope").jsonObject, "reply:$id->${library.deviceId}"),
        ).jsonObject
        require(result.string("id") == requestId)
        if (result["error"] != null) error(result.string("error"))
        return result.getValue("payload").jsonObject
    }

    private suspend fun receive(message: JsonObject, host: String): JsonObject {
        require(_state.value.enabled)
        when (message.string("type")) {
            "info" -> {
                require(message.number("version") == SYNC_VERSION.toLong())
                return buildJsonObject { put("device", deviceJson()) }
            }
            "hello" -> {
                require(message.number("version") == SYNC_VERSION.toLong())
                val device = parseDevice(message.getValue("device").jsonObject, host)
                require(device.id != library.deviceId && trusted[device.id] == null)
                val now = System.currentTimeMillis()
                val rate = rates[host]?.takeIf { now - it.first < 60000 } ?: (now to 0)
                rates[host] = rate.first to rate.second + 1
                require(rate.second < 3 && pending.size < 4)
                val pair = SyncCrypto.keyPair()
                val pairId = UUID.randomUUID().toString()
                pending[pairId] =
                    PendingPair(
                        device,
                        SyncCrypto.sharedKey(pair, message.string("publicKey"), device.id, library.deviceId),
                        false,
                        now + 120000,
                    )
                devices[device.id] = device
                changed()
                _state.update { it.copy(status = "${device.name} demande une association. Compare les codes.") }
                return buildJsonObject {
                    put("pairId", pairId)
                    put("publicKey", SyncCrypto.publicKey(pair))
                    put("device", deviceJson())
                }
            }
            "confirm" -> {
                val pairId = message.string("pairId")
                val pair = pending[pairId] ?: error("Association expirée")
                require(
                    !pair.outgoing && pair.device.id == message.string("from") &&
                        System.currentTimeMillis() < pair.expires,
                )
                val body = syncJson.parseToJsonElement(
                    SyncCrypto.decrypt(
                        pair.key,
                        message.getValue("envelope").jsonObject,
                        "pair:$pairId:${pair.device.id}->${library.deviceId}",
                    ),
                ).jsonObject
                val paired = body.bool("accepted") && pair.approved
                if (paired && !pair.completed) {
                    trusted[pair.device.id] = TrustedPeer(pair.device.name, pair.key)
                    savePeers()
                }
                val reply = SyncCrypto.encrypt(
                    pair.key,
                    "{\"paired\":$paired}",
                    "pair-reply:$pairId:${library.deviceId}->${pair.device.id}",
                )
                if (paired) {
                    pair.completed = true
                    changed()
                }
                return buildJsonObject { put("envelope", reply) }
            }
        }
        require(message.string("type") == "rpc")
        val from = message.string("from")
        val peer = trusted[from] ?: error("Appareil non associé")
        val body = syncJson.parseToJsonElement(
            SyncCrypto.decrypt(peer.key, message.getValue("envelope").jsonObject, "rpc:$from->${library.deviceId}"),
        ).jsonObject
        val id = body.string("id")
        val now = System.currentTimeMillis()
        require(body.string("instance") == instance) { "Session réseau expirée" }
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")) && kotlin.math.abs(now - body.number("time")) <= 300000)
        require(replays.size < 4096 && replays.putIfAbsent("$from:$id", now) == null)
        val result = try {
            val payload = body.getValue("payload").jsonObject
            val response = when (body.string("op")) {
                "merge" -> syncJson.parseToJsonElement(
                    syncJson.encodeToString(library.merge(syncJson.decodeFromString<SyncSnapshot>(payload.toString()))),
                ).jsonObject
                "snapshot" -> syncJson.parseToJsonElement(syncJson.encodeToString(library.snapshot())).jsonObject
                "pages" -> library.pages(payload)
                "page" -> library.page(payload)
                else -> error("Opération inconnue")
            }
            buildJsonObject {
                put("id", id)
                put("payload", response)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("id", id)
                put("error", e.message ?: "Échange impossible")
            }
        }
        return buildJsonObject {
            put("envelope", SyncCrypto.encrypt(peer.key, result.toString(), "reply:${library.deviceId}->$from"))
        }
    }

    private fun parseDevice(value: JsonObject, host: String): LanDevice {
        val id = value.string("id")
        val label = value.string("name")
        val platform = value.string("platform")
        val remotePort = value.number("port").toInt()
        val remoteInstance = value.string("instance")
        require(
            id.matches(
                Regex("[a-zA-Z0-9-]{1,128}"),
            ) && label.length <= 60 && platform in listOf("android", "windows") &&
                remotePort in 1..65535 && remoteInstance.matches(Regex("[a-zA-Z0-9-]{1,128}")),
        )
        return LanDevice(id, label, platform, host, remotePort, remoteInstance)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

private suspend fun request(host: String, port: Int, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
    require(host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) { "Saisis une adresse IPv4 locale" }
    val address = InetAddress.getByName(host)
    require(address.isSiteLocalAddress || address.isLoopbackAddress) { "Adresse locale requise" }
    require(port in 1..65535)
    Socket().use { socket ->
        socket.connect(InetSocketAddress(address, port), 10000)
        socket.soTimeout = 30000
        writeFrame(socket, body)
        readFrame(socket).also { if (it["error"] != null) error(it.string("error")) }
    }
}

private fun readFrame(socket: Socket): JsonObject {
    val input = DataInputStream(socket.getInputStream())
    val size = input.readInt()
    require(size in 1..MAX_FRAME)
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return syncJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

private fun writeFrame(socket: Socket, value: JsonObject) {
    val bytes = value.toString().toByteArray()
    require(bytes.size in 1..MAX_FRAME)
    val output = DataOutputStream(socket.getOutputStream())
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}
