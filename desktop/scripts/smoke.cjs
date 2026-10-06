// Run a real Electron renderer against an isolated library and a loopback peer.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const net = require('node:net');
const { LibraryStore } = require('../src/store.cjs');
const { importFile } = require('../src/media.cjs');
const { LanSync } = require('../src/lan.cjs');
const p = require('../src/protocol.cjs');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const output = path.resolve(__dirname, '../test-output');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mihon-electron-'));
let child, websocket, peer;
const failures = [];
async function main() {
  fs.mkdirSync(output, { recursive: true });
  const images = path.join(temp, 'Lecture de test'); fs.mkdirSync(images);
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64');
  fs.writeFileSync(path.join(images, '01.png'), png); fs.writeFileSync(path.join(images, '02.png'), png);
  const store = new LibraryStore(path.join(temp, 'profile', 'library')); store.save(); await importFile(store, images);
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  const probe = net.createServer();
  await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
  const debugPort = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const executable = process.env.MIHON_SMOKE_EXECUTABLE || require('electron');
  const applicationArgs = process.env.MIHON_SMOKE_EXECUTABLE ? [] : [path.resolve(__dirname, '..')];
  child = spawn(executable, [...applicationArgs, `--user-data-dir=${path.join(temp, 'profile')}`, `--remote-debugging-port=${debugPort}`], { env, windowsHide: true, stdio: 'pipe' });
  let stderr = ''; child.stderr.on('data', data => stderr += data); child.stdout.on('data', () => {});
  let target;
  for (let attempt = 0; attempt < 180; attempt++) {
    try { const list = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json(); target = list.find(item => item.type === 'page' && item.url.includes('index.html')); if (target) break; } catch {}
    await wait(250);
  }
  if (!target) throw Error(`Electron did not start (launcher exit=${child.exitCode}): ${stderr}`);
  websocket = new WebSocket(target.webSocketDebuggerUrl); await new Promise((resolve, reject) => { websocket.addEventListener('open', resolve, { once: true }); websocket.addEventListener('error', reject, { once: true }); });
  let nextId = 0; const pending = new Map();
  websocket.addEventListener('message', event => {
    const msg = JSON.parse(event.data);
    if (msg.method === 'Runtime.exceptionThrown') failures.push(msg.params.exceptionDetails);
    if (msg.id && pending.has(msg.id)) { const item = pending.get(msg.id); pending.delete(msg.id); msg.error ? item.reject(Error(msg.error.message)) : item.resolve(msg.result); }
  });
  const command = (method, params = {}) => new Promise((resolve, reject) => { const id = ++nextId; pending.set(id, { resolve, reject }); websocket.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expression => {
    const result = await command('Runtime.evaluate', { expression: `(async () => (${expression}))()`, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.text + ': ' + result.exceptionDetails.exception?.description);
    return result.result.value;
  };
  const until = async expression => {
    // A silent install can leave Chromium's window occluded. Request a painted
    // frame so visibility-driven page loaders run before inspecting the UI.
    await command('Page.captureScreenshot', { format: 'png' });
    for (let attempt = 0; attempt < 50; attempt++) { if (await evaluate(expression)) return; await wait(100); }
    const failureShot = await command('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(output, 'smoke-failure.png'), Buffer.from(failureShot.data, 'base64'));
    throw Error(`Timed out waiting for: ${expression}; ${JSON.stringify(await evaluate('({fullscreen:(await window.mihon.state()).fullscreen,rendererFullscreen:state.fullscreen,mode:document.getElementById("reader-mode").value,toast:document.getElementById("toast").textContent,pages:[...document.querySelectorAll(".webtoon-page")].map(n=>({index:n.dataset.index,loaded:n.dataset.loaded,loading:n.dataset.loading,images:[...n.querySelectorAll("img")].map(i=>({width:i.naturalWidth,complete:i.complete}))})),failures:' + JSON.stringify(failures) + '})'))}`);
  };
  const key = async (key, code) => {
    await command('Input.dispatchKeyEvent', { type: 'keyDown', key, code });
    await command('Input.dispatchKeyEvent', { type: 'keyUp', key, code });
  };
  await command('Runtime.enable'); await command('Page.enable'); await command('Page.bringToFront'); await wait(400);
  assert.equal(await evaluate('document.querySelectorAll(".book").length'), 1);
  await evaluate('document.querySelector(".book").click()'); await wait(100);
  assert.equal(await evaluate('document.querySelectorAll(".chapter-row").length'), 1);
  await evaluate('document.querySelector(".chapter-row .name").click()'); await wait(500);
  assert.equal(await evaluate('document.getElementById("reader").hidden'), false);
  assert.equal(await evaluate('document.querySelector("#page-container img").naturalWidth'), 1);
  await evaluate('document.getElementById("next").click()'); await wait(200);
  assert.equal(await evaluate('(await window.mihon.state()).records.find(r=>r.kind==="chapter").value.read'), true);
  await evaluate('document.getElementById("reader-mode").value="webtoon", document.getElementById("reader-mode").dispatchEvent(new Event("change"))');
  await until('document.querySelector("#page-container.webtoon img")?.naturalWidth === 1');
  await evaluate('document.getElementById("fullscreen").focus(), document.getElementById("fullscreen").click()');
  await until('document.body.classList.contains("reader-immersive")');
  assert.equal(await evaluate('[".reader-toolbar", ".reader-footer", "#toast"].every(s=>getComputedStyle(document.querySelector(s)).display==="none")'), true);
  assert.equal(await evaluate('document.getElementById("page-container").getBoundingClientRect().height===innerHeight'), true);
  assert.equal(await evaluate('document.activeElement.id'), 'page-container');
  const immersiveShot = await command('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(output, 'webtoon-fullscreen.png'), Buffer.from(immersiveShot.data, 'base64'));
  await evaluate('document.getElementById("page-container").scrollTop=20');
  assert.ok(await evaluate('document.getElementById("page-container").scrollTop') > 0, 'Webtoon scrolling must remain available with hidden HUD');
  await evaluate('document.getElementById("reader-mode").value="paged", document.getElementById("reader-mode").dispatchEvent(new Event("change"))');
  await until('!document.body.classList.contains("reader-immersive")');
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".reader-toolbar")).display'), 'flex');
  await evaluate('document.getElementById("reader-mode").value="webtoon", document.getElementById("reader-mode").dispatchEvent(new Event("change"))');
  await until('document.body.classList.contains("reader-immersive")');
  await key('f', 'KeyF'); await until('!(await window.mihon.state()).fullscreen');
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".reader-footer")).display'), 'flex');
  await key('f', 'KeyF'); await until('document.body.classList.contains("reader-immersive")');
  await key('Escape', 'Escape'); await until('!document.body.classList.contains("reader-immersive")');
  assert.equal(await evaluate('document.getElementById("reader").hidden'), false, 'Escape must first exit fullscreen without closing the chapter');
  await evaluate('document.getElementById("reader-mode").value="paged", document.getElementById("reader-mode").dispatchEvent(new Event("change"))');
  await until('document.querySelector("#page-container.paged img")?.naturalWidth===1');
  await evaluate('document.getElementById("reader-back").click()'); await wait(100);
  await evaluate('document.querySelector("nav [data-view=sync]").click()'); await wait(100);
  await evaluate('window.mihon.toggleLan(true)');
  let peers = {};
  const historyManga = p.mangaKey('42', '/history-only'), historyChapter = p.chapterKey(historyManga, '/chapter-zero');
  const recentTime = Date.now() + 1000;
  const remoteHistory = [
    { key: historyManga, kind: 'manga', value: { sourceId: '42', url: '/history-only', title: 'Lecture hors bibliothèque', favorite: false }, modifiedAt: Date.now(), deviceId: 'qa-phone' },
    { key: historyChapter, kind: 'chapter', value: { mangaKey: historyManga, url: '/chapter-zero', name: 'Chapitre commencé sur Android', read: false, bookmark: false, lastPageRead: 0, readAt: recentTime }, modifiedAt: Date.now(), deviceId: 'qa-phone' },
  ];
  peer = new LanSync({ device: { id: 'qa-phone', name: 'Téléphone de test' }, loadPeers: () => peers, savePeers: value => peers = value,
    handle: async (op, payload) => { if (op === 'merge') return { version: 1, records: p.mergeRecords(payload.records, remoteHistory) }; throw Error('Unknown'); } });
  await peer.start({ discovery: false });
  await evaluate(`window.mihon.manual('127.0.0.1', ${peer.device.port})`);
  await evaluate("window.mihon.pair('qa-phone')"); await wait(100);
  assert.equal(await evaluate('document.getElementById("pair-dialog").open'), true);
  const expected = peer.state().pairing[0].code;
  assert.equal((await evaluate('document.getElementById("pair-code").textContent')).replace(/ /g, ''), expected);
  const screenshot = await command('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(output, 'pairing.png'), Buffer.from(screenshot.data, 'base64'));
  await peer.approve(peer.state().pairing[0].pairId, true);
  await evaluate("window.mihon.approve((await window.mihon.state()).lan.pairing[0].pairId,true)");
  await evaluate("window.mihon.sync('qa-phone')");
  assert.equal(await evaluate('(await window.mihon.state()).lan.trusted.length'), 1);
  await evaluate('window.mihon.toggleLan(false)');
  await evaluate('document.querySelector("nav [data-view=history]").click()');
  assert.equal(await evaluate('document.querySelectorAll(".history-row").length'), 2);
  assert.equal(await evaluate('document.querySelector(".history-info strong").textContent'), 'Lecture hors bibliothèque');
  assert.ok((await evaluate('document.querySelector(".history-info small").textContent')).includes('Page 1'));
  const historyShot = await command('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(output, 'history.png'), Buffer.from(historyShot.data, 'base64'));
  await evaluate('document.querySelector(".history-search").value="Lecture de test", document.querySelector(".history-search").dispatchEvent(new Event("input"))');
  assert.equal(await evaluate('document.querySelectorAll(".history-row").length'), 1);
  await evaluate('document.querySelector(".history-open").click()');
  await until('document.getElementById("page-number").value==="2" && document.querySelector("#page-container.paged img")?.naturalWidth===1');
  await evaluate('document.getElementById("reader-back").click()'); await until('document.getElementById("reader").hidden');
  const persisted = new LibraryStore(path.join(temp, 'profile', 'library')).state.records;
  assert.equal(persisted.find(r => r.key === historyChapter).value.readAt, recentTime);
  await evaluate('document.querySelector("nav [data-view=library]").click()'); await wait(100);
  const libraryShot = await command('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(output, 'library.png'), Buffer.from(libraryShot.data, 'base64'));
  assert.deepEqual(failures, []);
  await evaluate('window.close()');
  for (let attempt = 0; attempt < 40 && child.exitCode === null; attempt++) await wait(250);
  assert.equal(child.exitCode, 0, 'The application must terminate when its window closes');
  console.log('PASS: real Electron reading, fullscreen webtoon HUD/scroll/exit, history sync/order/search/resume/persistence, pairing, encrypted sync, shutdown; no renderer exceptions.');
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  peer?.stop(); websocket?.close(); child?.kill(); await wait(500);
  // This directory was created by this script under the system temp directory.
  if (path.resolve(temp).startsWith(path.resolve(os.tmpdir()) + path.sep)) await fs.promises.rm(temp, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
});
