package mihon.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import tachiyomi.presentation.core.components.material.Scaffold

class LanSyncScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<LanSyncViewModel>()
        val state by viewModel.state.collectAsState()
        val view = LocalView.current
        DisposableEffect(view, state.enabled) {
            val wasKeptOn = view.keepScreenOn
            view.keepScreenOn = wasKeptOn || state.enabled
            onDispose { view.keepScreenOn = wasKeptOn }
        }
        var address by remember { mutableStateOf("") }
        var forget by remember { mutableStateOf<String?>(null) }
        DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
        Scaffold(topBar = { AppBar(title = "Synchronisation locale", navigateUp = navigator::pop) }) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Retrouve ta lecture sur Windows et Android", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Les appareils doivent être sur le même réseau. Aucune donnée n'est partagée avant une association confirmée des deux côtés.",
                )
                Button(
                    onClick = { if (state.enabled) viewModel.stop() else viewModel.start() },
                    enabled =
                    !state.busy || state.enabled,
                ) {
                    Text(if (state.enabled) "Arrêter la découverte" else "Découvrir mes appareils")
                }
                Text(state.status)
                if (state.enabled && state.devices.any { it.id in state.trusted }) {
                    Button(onClick = viewModel::syncAll, enabled = !state.busy) { Text("Synchroniser mes appareils") }
                }
                state.addresses.forEach { Text("Ce téléphone : $it", style = MaterialTheme.typography.bodySmall) }
                if (state.enabled) {
                    OutlinedTextField(value = address, onValueChange = {
                        address = it
                    }, label = {
                        Text("Adresse manuelle : 192.168.1.10:12345")
                    }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedButton(onClick = {
                        val host = address.substringBefore(':').trim()
                        val port = address.substringAfter(':', "").toIntOrNull()
                        if (port != null) viewModel.manual(host, port)
                    }, enabled = !state.busy && address.substringAfter(':', "").toIntOrNull() in 1..65535) {
                        Text("Rechercher à cette adresse")
                    }
                    if (state.devices.isEmpty()) {
                        Text(
                            "Recherche en cours… Si ton routeur bloque la découverte, utilise l'adresse affichée sur l'autre appareil.",
                        )
                    }
                    state.devices.forEach { device ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text("${device.platform} • ${device.host}:${device.port}")
                                Button(
                                    onClick = {
                                        if (device.id in
                                            state.trusted
                                        ) {
                                            viewModel.sync(device.id)
                                        } else {
                                            viewModel.pair(device.id)
                                        }
                                    },
                                    enabled =
                                    !state.busy && state.pairing.isEmpty(),
                                ) {
                                    Text(
                                        if (device.id in
                                            state.trusted
                                        ) {
                                            "Synchroniser maintenant"
                                        } else {
                                            "Associer cet appareil"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Données échangées", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bibliothèque, favoris, notes, catégories, chapitres, marque-pages, progression, historique de lecture et profil Discover (avis, liens et filtres enregistrés). Les comptes et mots de passe restent sur leur appareil.",
                )
                Text(
                    "La progression la plus avancée est conservée. Les autres modifications les plus récentes gagnent. Une remise à zéro de lecture reste locale ; elle ne supprime pas la progression conservée sur un autre appareil.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.trusted.forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, Modifier.weight(1f))
                        TextButton(onClick = { forget = id }) { Text("Oublier") }
                    }
                }
            }
        }
        state.pairing.firstOrNull()?.let { pair ->
            AlertDialog(
                onDismissRequest = {
                    viewModel.approve(pair.pairId, false)
                },
                title = { Text("Associer ${pair.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(pair.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall)
                        Text(
                            "Vérifie que ce code est identique sur l'autre appareil. Accepte uniquement ton propre appareil. L'association expire après 2 minutes.",
                        )
                        if (pair.approved) Text("En attente de confirmation sur l'autre appareil…")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.approve(pair.pairId, true)
                    }, enabled = !pair.approved) { Text("Les codes sont identiques") }
                },
                dismissButton = { TextButton(onClick = { viewModel.approve(pair.pairId, false) }) { Text("Refuser") } },
            )
        }
        forget?.let { id ->
            AlertDialog(
                onDismissRequest = { forget = null },
                title = { Text("Oublier cet appareil ?") },
                text = {
                    Text(
                        "Il ne pourra plus lire ni synchroniser tes données. Oublie également l'association sur l'autre appareil avant de l'associer à nouveau.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.forget(id)
                        forget = null
                    }) { Text("Oublier") }
                },
                dismissButton = { TextButton(onClick = { forget = null }) { Text("Annuler") } },
            )
        }
    }
}
