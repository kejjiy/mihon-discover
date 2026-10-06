const net = require('node:net');
const dgram = require('node:dgram');
const os = require('node:os');
const crypto = require('node:crypto');
const p = require('./protocol.cjs');

function privateAddress(address) {
  address = address?.replace(/^::ffff:/, '');
  return /^(127\.|10\.|192\.168\.|169\.254\.)/.test(address) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(address) || address === '::1' || /^f[cd]/i.test(address);
}
function frame(value) {
  const body = Buffer.from(JSON.stringify(value));
  if (body.length > p.MAX_FRAME) throw Error('Message trop volumineux');
  const size = Buffer.alloc(4); size.writeUInt32BE(body.length);
  return Buffer.concat([size, body]);
}
function readFrame(socket) {
  return new Promise((resolve, reject) => {
    let chunks = [], received = 0, length = null;
    const cleanup = () => { socket.removeListener('data', onData); socket.removeListener('error', onError); socket.removeListener('end', onEnd); socket.removeListener('timeout', onTimeout); };
    const fail = error => { cleanup(); reject(error); };
    const onError = error => fail(error), onEnd = () => fail(Error('Connexion interrompue'));
    const onTimeout = () => { fail(Error('Appareil injoignable (délai dépassé)')); socket.destroy(); };
    function onData(chunk) {
      chunks.push(chunk); received += chunk.length;
      if (received > p.MAX_FRAME + 4) { fail(Error('Message trop volumineux')); socket.destroy(); return; }
      if (length === null && received >= 4) {
        chunks = [Buffer.concat(chunks)]; length = chunks[0].readUInt32BE(0);
        if (!length || length > p.MAX_FRAME) { fail(Error('Trame invalide')); socket.destroy(); return; }
      }
      if (length !== null && received >= length + 4) {
        cleanup();
        try { resolve(JSON.parse(Buffer.concat(chunks).subarray(4, length + 4).toString())); } catch (e) { reject(e); }
      }
    }
    socket.on('data', onData); socket.once('error', onError); socket.once('end', onEnd); socket.once('timeout', onTimeout);
  });
}
async function request(host, port, value) {
  if (!privateAddress(host) || !Number.isInteger(port) || port < 1 || port > 65535) throw Error('Adresse locale IPv4 requise');
  const socket = net.createConnection({ host, port }); socket.setTimeout(30000);
  try { const reply = readFrame(socket); socket.write(frame(value)); return await reply; } finally { socket.destroy(); }
}
function validDevice(d) {
  return d && /^[a-zA-Z0-9-]{1,128}$/.test(d.id) && typeof d.name === 'string' && d.name.length <= 60 &&
    ['android', 'windows'].includes(d.platform) && /^[a-zA-Z0-9-]{1,128}$/.test(d.instance) && Number.isInteger(d.port) && d.port > 0 && d.port < 65536;
}

class LanSync {
  constructor({ device, loadPeers, savePeers, onState = () => {}, onNotice = () => {}, handle }) {
    this.device = { ...device, platform: 'windows' }; this.loadPeers = loadPeers; this.savePeers = savePeers;
    this.onState = onState; this.onNotice = onNotice; this.handle = handle;
    this.peers = loadPeers(); this.discovered = new Map(); this.pending = new Map(); this.replays = new Map();
    this.rates = new Map(); this.sockets = new Set(); this.enabled = false;
  }
  state() {
    return { enabled: this.enabled, device: this.device,
      addresses: Object.values(os.networkInterfaces()).flat().filter(x => x.family === 'IPv4' && !x.internal && privateAddress(x.address)).map(x => `${x.address}:${this.device.port}`),
      peers: [...this.discovered.values()].filter(d => d.manual || Date.now() - d.seen < 20000).map(d => ({ ...d, trusted: !!this.peers[d.id] })),
      trusted: Object.entries(this.peers).map(([id, peer]) => ({ id, name: peer.name })),
      pairing: [...this.pending.values()].filter(x => !x.completed).map(x => ({ pairId: x.pairId, name: x.device.name, code: p.code(x.key), outgoing: x.outgoing, approved: x.approved })) };
  }
  changed() { this.onState(this.state()); }
  async start({ port = 0, discovery = true } = {}) {
    if (this.enabled) return;
    this.server = net.createServer(socket => {
      if (!privateAddress(socket.remoteAddress) || this.sockets.size >= 8) { socket.destroy(); return; }
      this.sockets.add(socket); socket.once('close', () => this.sockets.delete(socket)); socket.setTimeout(30000);
      readFrame(socket).then(message => this.receive(message, socket.remoteAddress)).then(reply => {
        if (!socket.destroyed) socket.end(frame(reply));
      }).catch(() => { if (!socket.destroyed) socket.end(frame({ error: 'Requête refusée' })); });
    });
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(port, '0.0.0.0', resolve); });
    this.server.on('error', e => this.onNotice(e.message));
    this.device.port = this.server.address().port; this.device.instance = crypto.randomUUID(); this.enabled = true;
    if (discovery) {
      this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      this.udp.on('error', e => this.onNotice(`Découverte : ${e.message}. Utilise l'adresse manuelle.`));
      this.udp.on('message', (data, source) => {
        if (!privateAddress(source.address) || data.length > 2048) return;
        try {
          const msg = JSON.parse(data.toString());
          if (msg.app !== 'mihon-discover' || msg.version !== p.VERSION || !validDevice(msg.device) || msg.device.id === this.device.id) return;
          const fresh = !this.discovered.has(msg.device.id);
          this.discovered.set(msg.device.id, { ...msg.device, host: source.address, seen: Date.now() });
          this.changed();
          if (fresh) this.onNotice(`${msg.device.name} est disponible sur le réseau local.`);
        } catch { /* Ignore unrelated multicast traffic. */ }
      });
      this.udp.bind(p.PORT, () => {
        for (const host of this.state().addresses) {
          try { this.udp.addMembership(p.GROUP, host.split(':')[0]); } catch { /* Manual connection remains available. */ }
        }
        this.udp.setMulticastTTL(1); this.announce();
      });
    }
    this.timer = setInterval(() => {
      for (const [id, pending] of this.pending) if (Date.now() > pending.expires) this.pending.delete(id);
      for (const [id, time] of this.replays) if (Date.now() - time > 300000) this.replays.delete(id);
      for (const [id, rate] of this.rates) if (Date.now() - rate.start > 60000) this.rates.delete(id);
      this.announce(); this.changed();
    }, 4000);
    this.changed();
  }
  announce() {
    if (!this.udp) return;
    const body = Buffer.from(JSON.stringify({ app: 'mihon-discover', version: p.VERSION, device: this.device }));
    for (const host of this.state().addresses) {
      try { this.udp.setMulticastInterface(host.split(':')[0]); this.udp.send(body, p.PORT, p.GROUP); } catch { /* Report through manual fallback in UI. */ }
    }
  }
  stop() {
    this.enabled = false; clearInterval(this.timer); this.timer = null;
    for (const socket of this.sockets) socket.destroy();
    this.server?.close(); this.server = null;
    try { this.udp?.close(); } catch {} this.udp = null;
    this.pending.clear(); this.discovered.clear(); this.changed();
  }
  async manual(host, port) {
    const result = await request(host, port, { type: 'info', version: p.VERSION });
    if (!validDevice(result.device) || result.device.id === this.device.id) throw Error('Appareil incompatible');
    this.discovered.set(result.device.id, { ...result.device, host, port, seen: Date.now(), manual: true }); this.changed();
  }
  async pair(id) {
    if (!this.enabled) throw Error('Active la synchronisation locale');
    const device = this.discovered.get(id); if (!device) throw Error('Appareil absent');
    if (this.peers[id]) throw Error('Appareil déjà associé');
    const pair = p.keyPair();
    const reply = await request(device.host, device.port, { type: 'hello', version: p.VERSION, device: this.device, publicKey: p.publicKey(pair) });
    if (reply.error || !validDevice(reply.device) || reply.device.id !== id || !/^[a-zA-Z0-9-]{1,128}$/.test(reply.pairId)) throw Error(reply.error || 'Association refusée');
    const key = p.sharedKey(pair, reply.publicKey, this.device.id, id);
    this.pending.set(reply.pairId, { pairId: reply.pairId, key, device, outgoing: true, approved: false, expires: Date.now() + 120000 }); this.changed();
  }
  async approve(pairId, accepted) {
    const pending = this.pending.get(pairId);
    if (!pending) throw Error('Association expirée');
    if (!accepted) { this.pending.delete(pairId); this.changed(); return; }
    pending.approved = true; this.changed();
    if (!pending.outgoing) return;
    while (this.enabled && this.pending.has(pairId) && Date.now() < pending.expires) {
      const from = this.device.id, to = pending.device.id;
      const reply = await request(pending.device.host, pending.device.port, { type: 'confirm', from, pairId,
        envelope: p.encrypt(pending.key, { accepted: true }, `pair:${pairId}:${from}->${to}`) });
      if (reply.error) throw Error(reply.error);
      const result = p.decrypt(pending.key, reply.envelope, `pair-reply:${pairId}:${to}->${from}`);
      if (result.paired) {
        this.peers[to] = { name: pending.device.name, key: pending.key.toString('base64') }; this.savePeers(this.peers);
        this.pending.delete(pairId); this.changed(); return;
      }
      await new Promise(resolve => setTimeout(resolve, 800));
    }
    this.pending.delete(pairId); this.changed(); throw Error('Association annulée ou expirée');
  }
  forget(id) { delete this.peers[id]; for (const [pairId, pair] of this.pending) if (pair.device.id === id) this.pending.delete(pairId); this.savePeers(this.peers); this.changed(); }
  async rpc(id, op, payload = {}) {
    if (!this.enabled) throw Error('Active la synchronisation locale');
    const peer = this.peers[id], device = this.discovered.get(id);
    if (!peer || !device) throw Error('Appareil associé injoignable. Relance la découverte.');
    const key = Buffer.from(peer.key, 'base64'), requestId = crypto.randomUUID(), from = this.device.id;
    const reply = await request(device.host, device.port, { type: 'rpc', from,
      envelope: p.encrypt(key, { id: requestId, time: Date.now(), instance: device.instance, op, payload }, `rpc:${from}->${id}`) });
    if (reply.error) throw Error(reply.error);
    const result = p.decrypt(key, reply.envelope, `reply:${id}->${from}`);
    if (result.id !== requestId) throw Error('Réponse inattendue');
    if (result.error) throw Error(result.error);
    return result.payload;
  }
  async receive(message, host) {
    if (!this.enabled) throw Error('Désactivé');
    if (message.type === 'info' && message.version === p.VERSION) return { device: this.device };
    if (message.type === 'hello') {
      if (message.version !== p.VERSION || !validDevice(message.device) || message.device.id === this.device.id || this.peers[message.device.id]) throw Error('Appareil incompatible ou déjà associé');
      const rate = this.rates.get(host) || { start: Date.now(), count: 0 };
      if (++rate.count > 3 || this.pending.size >= 4) throw Error('Trop de demandes'); this.rates.set(host, rate);
      const pair = p.keyPair(), pairId = crypto.randomUUID();
      const key = p.sharedKey(pair, message.publicKey, message.device.id, this.device.id);
      const device = { ...message.device, host: host.replace(/^::ffff:/, ''), seen: Date.now() };
      this.discovered.set(device.id, device);
      this.pending.set(pairId, { pairId, key, device, outgoing: false, approved: false, expires: Date.now() + 120000 });
      this.changed(); this.onNotice(`${device.name} demande une association. Compare le code dans l'app.`);
      return { pairId, publicKey: p.publicKey(pair), device: this.device };
    }
    if (message.type === 'confirm') {
      const pending = this.pending.get(message.pairId);
      if (!pending || pending.outgoing || pending.device.id !== message.from || Date.now() > pending.expires) throw Error('Association expirée');
      const accepted = p.decrypt(pending.key, message.envelope, `pair:${message.pairId}:${message.from}->${this.device.id}`).accepted === true;
      const paired = accepted && pending.approved;
      if (paired && !pending.completed) { this.peers[message.from] = { name: pending.device.name, key: pending.key.toString('base64') }; this.savePeers(this.peers); }
      const envelope = p.encrypt(pending.key, { paired }, `pair-reply:${message.pairId}:${this.device.id}->${message.from}`);
      if (paired) { pending.completed = true; this.changed(); }
      return { envelope };
    }
    if (message.type !== 'rpc' || !this.peers[message.from]) throw Error('Appareil non associé');
    const key = Buffer.from(this.peers[message.from].key, 'base64');
    const body = p.decrypt(key, message.envelope, `rpc:${message.from}->${this.device.id}`);
    if (body.instance !== this.device.instance || !/^[a-zA-Z0-9-]{1,128}$/.test(body.id) || !Number.isSafeInteger(body.time) || Math.abs(Date.now() - body.time) > 300000 || this.replays.has(`${message.from}:${body.id}`) || this.replays.size >= 4096) throw Error('Message expiré ou déjà reçu');
    this.replays.set(`${message.from}:${body.id}`, Date.now());
    let result;
    try { result = { id: body.id, payload: await this.handle(body.op, body.payload, message.from) }; }
    catch (error) { result = { id: body.id, error: error.message }; }
    return { envelope: p.encrypt(key, result, `reply:${this.device.id}->${message.from}`) };
  }
}
module.exports = { LanSync, request, privateAddress, frame, readFrame };
