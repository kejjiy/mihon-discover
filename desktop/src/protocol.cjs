const crypto = require('node:crypto');
const VERSION = 1, GROUP = '239.255.77.77', PORT = 41783, MAX_FRAME = 16 * 1024 * 1024;
const b64 = value => Buffer.from(value).toString('base64');
const mangaKey = (source, url) => 'm:' + b64(`${source}\n${url}`);
const chapterKey = (manga, url) => 'c:' + b64(`${manga}\n${url}`);
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();
function keyPair() { return crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' }); }
function publicKey(pair) { return pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'); }
function sharedKey(pair, remote, initiator, responder) {
  const publicKey = crypto.createPublicKey({ key: Buffer.from(remote, 'base64'), type: 'spki', format: 'der' });
  if (publicKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') throw Error('Courbe incompatible');
  const secret = crypto.diffieHellman({ privateKey: pair.privateKey, publicKey });
  const salt = crypto.createHash('sha256').update(`mihon-discover-sync-v1\n${initiator}\n${responder}`).digest();
  return hmac(hmac(salt, secret), 'session\x01');
}
function code(key) { return String(hmac(key, 'compare-code-v1').readUInt32BE(0) % 1000000).padStart(6, '0'); }
function encrypt(key, value, aad) {
  const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(aad));
  const data = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final(), cipher.getAuthTag()]);
  return { iv: b64(iv), data: b64(data) };
}
function decrypt(key, envelope, aad) {
  const iv = Buffer.from(envelope.iv, 'base64'), data = Buffer.from(envelope.data, 'base64');
  if (iv.length !== 12 || data.length < 16) throw Error('Message invalide');
  const cipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(aad)); cipher.setAuthTag(data.subarray(-16));
  return JSON.parse(Buffer.concat([cipher.update(data.subarray(0, -16)), cipher.final()]).toString());
}
const canonical = value => JSON.stringify(value, function (_, v) {
  if (v && typeof v === 'object' && !Array.isArray(v)) return Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b, 'en')));
  return v;
});
const kinds = new Set(['manga', 'chapter', 'feedback', 'link', 'option']);
const longString = value => typeof value === 'string' && /^-?\d{1,19}$/.test(value) && BigInt(value) >= -9223372036854775808n && BigInt(value) <= 9223372036854775807n && String(BigInt(value)) === value;
function validateSnapshot(snapshot) {
  if (snapshot?.version !== VERSION || !Array.isArray(snapshot.records) || snapshot.records.length > 100000) throw Error('Version ou taille de bibliothèque incompatible');
  const keys = new Set();
  for (const r of snapshot.records) {
    if (!r || typeof r.key !== 'string' || r.key.length > 8192 || keys.has(r.key) || !kinds.has(r.kind) ||
        !r.value || Array.isArray(r.value) || typeof r.value !== 'object' || typeof r.deviceId !== 'string' ||
        !/^[a-zA-Z0-9-]{1,128}$/.test(r.deviceId) || !Number.isSafeInteger(r.modifiedAt) || r.modifiedAt < 0 || r.modifiedAt > Date.now() + 300000) throw Error('Donnée de synchronisation invalide');
    if (r.kind === 'manga' && (typeof r.value.sourceId !== 'string' || typeof r.value.url !== 'string' || mangaKey(r.value.sourceId, r.value.url) !== r.key)) throw Error('Identité manga invalide');
    if (r.kind === 'chapter' && (typeof r.value.mangaKey !== 'string' || typeof r.value.url !== 'string' || chapterKey(r.value.mangaKey, r.value.url) !== r.key || !Number.isSafeInteger(r.value.lastPageRead) || r.value.lastPageRead < 0)) throw Error('Identité chapitre invalide');
    const v = r.value;
    if (v.deleted !== undefined && typeof v.deleted !== 'boolean') throw Error('Suppression invalide');
    if (r.kind === 'manga' && (!longString(v.sourceId) || typeof v.title !== 'string' || v.title.length > 2000 || typeof v.favorite !== 'boolean' || !v.url || v.url.length > 4096 ||
        ['genre', 'categories'].some(field => v[field] !== undefined && (!Array.isArray(v[field]) || v[field].length > 100 || v[field].some(x => typeof x !== 'string' || x.length > 200))) ||
        ['author','artist','description','notes','thumbnailUrl'].some(field => v[field] !== undefined && (typeof v[field] !== 'string' || v[field].length > 100000)) ||
        ['viewerFlags','chapterFlags'].some(field => v[field] !== undefined && !longString(v[field])) || (v.status !== undefined && !Number.isSafeInteger(v.status)))) throw Error('Manga invalide');
    if (r.kind === 'chapter' && (typeof v.read !== 'boolean' || typeof v.bookmark !== 'boolean' || v.lastPageRead > 1000000 ||
        ['readAt','sourceOrder','dateUpload'].some(field => v[field] !== undefined && !Number.isSafeInteger(v[field])) ||
        (v.chapterNumber !== undefined && !Number.isFinite(v.chapterNumber)) || (v.readAt || 0) < 0)) throw Error('Progression invalide');
    if (r.kind === 'feedback' && (!Number.isInteger(v.id) || v.id < 1 || v.id > 2147483647 || r.key !== `f:${v.id}` || ![-1,0,1].includes(v.vote) || typeof v.hidden !== 'boolean' || (v.status != null && (typeof v.status !== 'string' || v.status.length > 100)))) throw Error('Avis invalide');
    if (r.kind === 'link' && (!longString(v.sourceId) || typeof v.url !== 'string' || v.url.length > 4096 || !Number.isInteger(v.mediaId) || v.mediaId < 1 || v.mediaId > 2147483647 || r.key !== `l:${mangaKey(v.sourceId, v.url)}` || typeof v.confirmed !== 'boolean')) throw Error('Association catalogue invalide');
    if (r.kind === 'option' && (typeof v.name !== 'string' || v.name.length > 200 || (v.name !== 'romance_policy' && !v.name.startsWith('preset:')) || r.key !== `o:${v.name}` || typeof v.value !== 'string' || v.value.length > 100000)) throw Error('Filtre invalide');
    keys.add(r.key);
  }
  return snapshot;
}
function mergeRecords(local, incoming) {
  validateSnapshot({ version: VERSION, records: incoming });
  const merged = new Map(local.map(r => [r.key, r]));
  for (const r of incoming) {
    const old = merged.get(r.key);
    if (!old) { merged.set(r.key, structuredClone(r)); continue; }
    if (old.kind !== r.kind) throw Error('Type de donnée incohérent');
    const winner = r.modifiedAt > old.modifiedAt || (r.modifiedAt === old.modifiedAt && r.deviceId > old.deviceId) ? r : old;
    const value = { ...winner.value };
    if (r.kind === 'chapter') {
      value.read = !!old.value.read || !!r.value.read;
      value.lastPageRead = Math.max(old.value.lastPageRead || 0, r.value.lastPageRead || 0);
      value.readAt = Math.max(old.value.readAt || 0, r.value.readAt || 0);
    }
    merged.set(r.key, { ...winner, value });
  }
  return [...merged.values()].sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0);
}
module.exports = { VERSION, GROUP, PORT, MAX_FRAME, mangaKey, chapterKey, keyPair, publicKey, sharedKey, code, encrypt, decrypt, canonical, mergeRecords, validateSnapshot };
