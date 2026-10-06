const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const p = require('../src/protocol.cjs');
const { LanSync, request } = require('../src/lan.cjs');
const { LibraryStore } = require('../src/store.cjs');
const { importFile, imagePath } = require('../src/media.cjs');
const fixture = require('../../sync/fixtures/crypto.json');
const record = (kind, key, value, modifiedAt = 1, deviceId = 'a') => ({ kind, key, value, modifiedAt, deviceId });

test('Node and Android share an AES-GCM/code fixture; changed identity and ciphertext are rejected', () => {
  const key = Buffer.from(fixture.key, 'base64');
  assert.deepEqual(p.decrypt(key, fixture.envelope, fixture.aad), fixture.plaintext);
  assert.equal(p.code(key), fixture.code);
  assert.throws(() => p.decrypt(key, fixture.envelope, fixture.aad + 'x'));
  const damaged = { ...fixture.envelope, data: fixture.envelope.data.slice(0, -4) + 'AAAA' };
  assert.throws(() => p.decrypt(key, damaged, fixture.aad));
  const privateKey = crypto.createPrivateKey({ key: Buffer.from(fixture.privateKey, 'base64'), format: 'der', type: 'pkcs8' });
  assert.equal(p.sharedKey({ privateKey }, fixture.remotePublicKey, 'initiator', 'responder').toString('base64'), fixture.sharedKey);
});
test('fresh ECDH sessions agree and differ from previous sessions', () => {
  const a = p.keyPair(), b = p.keyPair();
  const key = p.sharedKey(a, p.publicKey(b), 'a', 'b');
  assert.deepEqual(key, p.sharedKey(b, p.publicKey(a), 'a', 'b'));
  assert.notDeepEqual(key, p.sharedKey(a, p.publicKey(p.keyPair()), 'a', 'b'));
});
test('merge is commutative and idempotent, and preserves reading while accepting newer bookmarks', () => {
  const m = p.mangaKey('9223372036854775000', '/manga'), key = p.chapterKey(m, '/chapter');
  const old = record('chapter', key, { mangaKey: m, url: '/chapter', read: true, lastPageRead: 40, readAt: 10, bookmark: true }, 1);
  const next = record('chapter', key, { mangaKey: m, url: '/chapter', read: false, lastPageRead: 3, readAt: 20, bookmark: false }, 2, 'b');
  const result = p.mergeRecords([old], [next]);
  assert.equal(result[0].value.read, true); assert.equal(result[0].value.lastPageRead, 40); assert.equal(result[0].value.bookmark, false); assert.equal(result[0].value.readAt, 20);
  assert.deepEqual(result, p.mergeRecords([next], [old])); assert.deepEqual(result, p.mergeRecords(result, result));
  assert.throws(() => p.mergeRecords([], [next, next]));
});
test('equal timestamps have a deterministic winner and IDs retain 64-bit source identity', () => {
  assert.notEqual(p.mangaKey('9223372036854775000', '/m'), p.mangaKey('9223372036854775001', '/m'));
  const a = record('option', 'o:romance_policy', { name: 'romance_policy', value: 'allow' }, 10, 'a');
  const b = record('option', 'o:romance_policy', { name: 'romance_policy', value: 'exclude' }, 10, 'b');
  assert.equal(p.mergeRecords([a], [b])[0].value.value, 'exclude'); assert.deepEqual(p.mergeRecords([a], [b]), p.mergeRecords([b], [a]));
});
test('malformed personal records and out-of-range source IDs are rejected before library mutation', () => {
  const feedback = record('feedback', 'f:42', { id: 42, vote: 1, hidden: false });
  p.validateSnapshot({ version: 1, records: [feedback] });
  assert.throws(() => p.validateSnapshot({ version: 1, records: [{ ...feedback, value: { ...feedback.value, id: '42' } }] }));
  const value = { sourceId: '9999999999999999999', url: '/bad', title: 'Bad', favorite: true };
  assert.throws(() => p.validateSnapshot({ version: 1, records: [record('manga', p.mangaKey(value.sourceId, value.url), value)] }));
});
test('two actual TCP peers require mutual confirmation, encrypt RPCs, reject replay, and revoke access', async t => {
  let aPeers = {}, bPeers = {}, received = 0;
  const a = new LanSync({ device: { id: 'device-a', name: 'PC A' }, loadPeers: () => aPeers, savePeers: peers => { aPeers = peers; }, handle: async () => ({}) });
  const b = new LanSync({ device: { id: 'device-b', name: 'PC B' }, loadPeers: () => bPeers, savePeers: peers => { bPeers = peers; }, handle: async (op, payload) => { received++; return { op, payload }; } });
  t.after(() => { a.stop(); b.stop(); }); await a.start({ discovery: false }); await b.start({ discovery: false });
  await a.manual('127.0.0.1', b.device.port); await a.pair(b.device.id);
  const pendingA = a.state().pairing[0], pendingB = b.state().pairing[0];
  assert.equal(pendingA.code, pendingB.code); assert.equal(Object.keys(aPeers).length, 0); assert.equal(Object.keys(bPeers).length, 0);
  await assert.rejects(() => a.rpc(b.device.id, 'merge', {}), /associé/);
  await b.approve(pendingB.pairId, true); assert.equal(Object.keys(bPeers).length, 0);
  await a.approve(pendingA.pairId, true); assert.ok(aPeers[b.device.id]); assert.ok(bPeers[a.device.id]);
  assert.deepEqual(await a.rpc(b.device.id, 'echo', { title: 'Privé' }), { op: 'echo', payload: { title: 'Privé' } });
  const key = Buffer.from(aPeers[b.device.id].key, 'base64');
  const value = { type: 'rpc', from: a.device.id, envelope: p.encrypt(key, { id: 'replay-test', time: Date.now(), instance: b.device.instance, op: 'echo', payload: {} }, `rpc:${a.device.id}->${b.device.id}`) };
  const first = await request('127.0.0.1', b.device.port, value); assert.ok(first.envelope);
  const duplicate = await request('127.0.0.1', b.device.port, value); assert.ok(duplicate.error); assert.equal(received, 2);
  const previousInstance = b.device.instance;
  b.stop(); await b.start({ discovery: false }); await a.manual('127.0.0.1', b.device.port);
  assert.notEqual(previousInstance, b.device.instance);
  const restartedReplay = await request('127.0.0.1', b.device.port, value); assert.ok(restartedReplay.error);
  await a.rpc(b.device.id, 'echo', {}); assert.equal(received, 3);
  b.forget(a.device.id); await assert.rejects(() => a.rpc(b.device.id, 'echo', {}), /refusée/);
});
test('library persists, exports no filesystem paths, and makes a recoverable pre-merge snapshot', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mihon-library-')); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const store = new LibraryStore(directory); store.save();
  const key = p.mangaKey('42', '/book'); store.put(key, 'manga', { sourceId: '42', url: '/book', title: 'Livre', favorite: true });
  store.merge({ version: 1, records: [record('option', 'o:romance_policy', { name: 'romance_policy', value: 'exclude' })] });
  assert.equal(new LibraryStore(directory).state.records.length, 2); assert.equal(JSON.parse(fs.readFileSync(store.file + '.before-sync')).records.length, 1);
  assert.equal(JSON.stringify(store.snapshot()).includes(directory.replace(/\\/g, '\\\\')), false);
});
test('local images import in natural page order and remain readable without the original folder', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mihon-media-')); t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const input = path.join(directory, 'input'); fs.mkdirSync(input); fs.writeFileSync(path.join(input, '10.png'), 'page10'); fs.writeFileSync(path.join(input, '2.png'), 'page2');
  const store = new LibraryStore(path.join(directory, 'store')); store.save(); const key = await importFile(store, input);
  const chapter = store.state.records.find(r => r.kind === 'chapter' && r.value.mangaKey === key), file = store.state.files[chapter.key];
  assert.equal(file.count, 2); assert.equal(fs.readFileSync(imagePath(store, file.pages[0]), 'utf8'), 'page2');
  const manga = store.state.records.find(r => r.key === key); store.put(key, 'manga', { ...manga.value, notes: 'À conserver', favorite: false });
  await importFile(store, input);
  assert.equal(store.state.records.find(r => r.key === key).value.notes, 'À conserver');
  assert.equal(store.state.records.find(r => r.key === key).value.favorite, false);
  fs.rmSync(input, { recursive: true }); assert.ok(fs.existsSync(imagePath(store, file.pages[1])));
});
