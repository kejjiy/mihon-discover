const { app, BrowserWindow, ipcMain, dialog, protocol, net, Notification, safeStorage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { pathToFileURL } = require('node:url');
const { LibraryStore } = require('./store.cjs');
const { LanSync } = require('./lan.cjs');
const { importFile, saveImage, imagePath } = require('./media.cjs');
const { catalogue, rank } = require('./catalogue.cjs');
const p = require('./protocol.cjs');
protocol.registerSchemesAsPrivileged([{ scheme: 'mihon-media', privileges: { standard: true, secure: true, supportFetchAPI: true } }]);
let window, store, lan, peerFile;
const remoteSessions = new Map();
function send(channel, value) {
  if (window && !window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send(channel, value);
}
function notice(message) {
  send('notice', message);
  if (window && !window.isDestroyed() && !window.isFocused() && Notification.isSupported()) new Notification({ title: 'Mihon Discover', body: message }).show();
}
function state() { return { records: store.state.records, files: Object.fromEntries(Object.entries(store.state.files).map(([key, value]) => [key, { count: value.count, cached: value.pages.filter(Boolean).length }])), lan: lan.state(), settings: store.state.settings, fullscreen: !!window && !window.isDestroyed() && window.isFullScreen() }; }
function sendState() { send('state', state()); }
function loadPeers() {
  if (!fs.existsSync(peerFile)) return {};
  if (!safeStorage.isEncryptionAvailable()) throw Error('Le chiffrement Windows des associations est indisponible');
  return JSON.parse(safeStorage.decryptString(fs.readFileSync(peerFile)));
}
function savePeers(peers) {
  if (!safeStorage.isEncryptionAvailable()) throw Error('Le chiffrement Windows des associations est indisponible');
  const temp = peerFile + '.tmp'; fs.writeFileSync(temp, safeStorage.encryptString(JSON.stringify(peers))); fs.renameSync(temp, peerFile);
}
function handle(channel, handler) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (event.sender !== window?.webContents || event.senderFrame !== window.webContents.mainFrame) throw Error('Accès refusé');
    return handler(...args);
  });
}
async function sync(id) {
  const reply = await lan.rpc(id, 'merge', store.snapshot()); store.merge(reply);
  const final = await lan.rpc(id, 'merge', store.snapshot()); store.merge(final); sendState();
  notice(`Synchronisation terminée : ${store.state.records.length} éléments.`);
}
async function openChapter(key, peerId) {
  const chapter = store.state.records.find(r => r.key === key && r.kind === 'chapter'); if (!chapter) throw Error('Chapitre absent');
  const cache = store.state.files[key];
  if (cache && cache.pages.filter(Boolean).length === cache.count) return { count: cache.count, page: Math.min(chapter.value.lastPageRead || 0, cache.count - 1) };
  const peer = peerId || lan.state().peers.find(x => x.trusted && x.platform === 'android')?.id;
  if (!peer) throw Error('Connecte ton téléphone associé, ou importe un CBZ pour lire hors ligne.');
  const result = await lan.rpc(peer, 'pages', { chapterKey: key });
  if (!Number.isInteger(result.count) || result.count < 1 || result.count > 3000 || typeof result.token !== 'string') throw Error('Pages invalides');
  remoteSessions.set(key, { peer, token: result.token });
  store.state.files[key] = { count: result.count, pages: cache?.count === result.count ? cache.pages : Array(result.count).fill(null) }; store.save();
  return { count: result.count, page: Math.min(chapter.value.lastPageRead || 0, result.count - 1) };
}
async function readPage(key, index) {
  const file = store.state.files[key];
  if (!file || !Number.isInteger(index) || index < 0 || index >= file.count) throw Error('Page invalide');
  if (!file.pages[index]) {
    const session = remoteSessions.get(key); if (!session) throw Error('Rouvre le chapitre pour te reconnecter au téléphone.');
    const result = await lan.rpc(session.peer, 'page', { chapterKey: key, token: session.token, index });
    file.pages[index] = saveImage(store, Buffer.from(result.data, 'base64')); store.save();
  }
  return `mihon-media://page/${file.pages[index]}`;
}
app.whenReady().then(async () => {
  app.setAppUserModelId('io.github.kejjiy.mihondiscover.windows');
  const directory = path.join(app.getPath('userData'), 'library'); store = new LibraryStore(directory); store.save(); peerFile = path.join(directory, 'peers.bin');
  lan = new LanSync({ device: { id: store.state.deviceId, name: os.hostname().slice(0, 60) }, loadPeers, savePeers,
    onState: sendState, onNotice: notice, handle: async (op, payload) => {
      if (op === 'merge') { const result = store.merge(payload); sendState(); return result; }
      if (op === 'snapshot') return store.snapshot();
      throw Error('La lecture distante depuis Windows sera disponible dans une version ultérieure.');
    } });
  protocol.handle('mihon-media', request => {
    const url = new URL(request.url);
    if (url.host !== 'page') return new Response('Forbidden', { status: 403 });
    try { return net.fetch(pathToFileURL(imagePath(store, url.pathname.slice(1))).href); }
    catch { return new Response('Not found', { status: 404 }); }
  });
  window = new BrowserWindow({ width: 1320, height: 900, minWidth: 860, minHeight: 600, backgroundColor: '#101118', title: 'Mihon Discover',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.setMenu(null);
  window.on('enter-full-screen', () => send('state', { ...state(), fullscreen: true }));
  window.on('leave-full-screen', () => send('state', { ...state(), fullscreen: false }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.session.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
  handle('state', state);
  handle('lan-toggle', async enabled => { if (enabled) await lan.start(); else lan.stop(); sendState(); });
  handle('lan-manual', (host, port) => lan.manual(host, port));
  handle('lan-pair', id => lan.pair(id));
  handle('lan-approve', (id, accepted) => lan.approve(id, accepted === true));
  handle('lan-forget', id => lan.forget(id));
  handle('lan-sync', sync);
  handle('lan-sync-all', async () => {
    const peers = lan.state().peers.filter(peer => peer.trusted);
    if (!peers.length) throw Error('Aucun appareil associé disponible');
    for (let pass = 0; pass < 2; pass++) for (const peer of peers) await sync(peer.id);
    notice(`${peers.length} appareil(s) synchronisé(s).`);
  });
  handle('import', async folder => {
    const result = await dialog.showOpenDialog(window, { title: folder ? "Importer un dossier d'images" : 'Importer des CBZ', properties: folder ? ['openDirectory'] : ['openFile', 'multiSelections'], filters: [{ name: 'Bandes dessinées', extensions: ['cbz', 'zip'] }] });
    if (!result.canceled) for (const file of result.filePaths) await importFile(store, file);
    sendState();
  });
  handle('chapter-open', openChapter); handle('page', readPage);
  handle('download', async (key, peer) => {
    const { count } = await openChapter(key, peer);
    for (let index = 0; index < count; index++) { await readPage(key, index); send('download-progress', { key, done: index + 1, count }); }
    sendState(); return count;
  });
  handle('progress', (key, page, read) => { store.progress(key, page, read); });
  handle('edit', (key, patch) => {
    const record = store.state.records.find(r => r.key === key); if (!record) throw Error('Entrée absente');
    const allowed = record.kind === 'manga' ? ['favorite', 'notes', 'categories'] : record.kind === 'chapter' ? ['bookmark'] : [];
    const value = { ...record.value };
    for (const [field, item] of Object.entries(patch)) {
      if (!allowed.includes(field)) throw Error('Champ invalide');
      if (['favorite', 'bookmark'].includes(field) && typeof item !== 'boolean') throw Error('Valeur invalide');
      if (field === 'notes' && (typeof item !== 'string' || item.length > 100000)) throw Error('Note invalide');
      if (field === 'categories' && (!Array.isArray(item) || item.length > 100 || item.some(x => typeof x !== 'string' || x.length > 200))) throw Error('Catégorie invalide');
      value[field] = item;
    }
    store.put(key, record.kind, value); sendState();
  });
  handle('feedback', (id, vote) => { store.feedback(id, vote); sendState(); });
  handle('catalogue', async options => {
    const result = await catalogue(options); store.state.catalogue = result.media; store.save(); return result;
  });
  handle('cached-catalogue', () => store.state.catalogue || []);
  handle('recommend', () => rank(store.state.catalogue || [], store.state.records));
  handle('settings', options => { if (!['paged', 'webtoon'].includes(options.reader) || !['ltr', 'rtl'].includes(options.direction)) throw Error('Réglage invalide'); store.state.settings = options; store.save(); sendState(); });
  handle('fullscreen', enabled => window.setFullScreen(typeof enabled === 'boolean' ? enabled : !window.isFullScreen()));
  handle('export', async () => {
    const result = await dialog.showSaveDialog(window, { defaultPath: 'mihon-discover-sync.json', filters: [{ name: 'Bibliothèque portable', extensions: ['json'] }] });
    if (!result.canceled) fs.writeFileSync(result.filePath, JSON.stringify(store.snapshot(), null, 2));
  });
  handle('import-snapshot', async () => {
    const result = await dialog.showOpenDialog(window, { filters: [{ name: 'Bibliothèque portable', extensions: ['json'] }], properties: ['openFile'] });
    if (!result.canceled) { const file = result.filePaths[0]; if (fs.statSync(file).size > p.MAX_FRAME) throw Error('Fichier trop volumineux'); store.merge(JSON.parse(fs.readFileSync(file, 'utf8'))); sendState(); }
  });
  await window.loadFile(path.join(__dirname, 'index.html'));
}).catch(error => { dialog.showErrorBox('Mihon Discover', error.message); app.quit(); });
app.on('window-all-closed', () => { lan?.stop(); app.quit(); });
app.on('before-quit', () => lan?.stop());
