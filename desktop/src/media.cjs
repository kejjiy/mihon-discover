const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const yauzl = require('yauzl');
const p = require('./protocol.cjs');
const IMAGE = /\.(png|jpe?g|webp|gif|avif|bmp)$/i;
const MAX_IMAGE = 8 * 1024 * 1024;
const sortNames = (a, b) => a.localeCompare(b, 'en', { numeric: true });
function saveImage(store, bytes) {
  if (!Buffer.isBuffer(bytes) || !bytes.length || bytes.length > MAX_IMAGE) throw Error('Image trop volumineuse ou vide');
  const hash = crypto.createHash('sha256').update(bytes).digest('hex');
  const directory = path.join(store.directory, 'media'); fs.mkdirSync(directory, { recursive: true });
  const target = path.join(directory, hash);
  if (!fs.existsSync(target)) fs.writeFileSync(target, bytes);
  return hash;
}
function imagePath(store, hash) {
  if (!/^[a-f0-9]{64}$/.test(hash)) throw Error('Image invalide');
  return path.join(store.directory, 'media', hash);
}
function openZip(file) {
  return new Promise((resolve, reject) => yauzl.open(file, { lazyEntries: true, validateEntrySizes: true }, (error, zip) => error ? reject(error) : resolve(zip)));
}
function streamBytes(zip, entry) {
  return new Promise((resolve, reject) => {
    if (entry.uncompressedSize > MAX_IMAGE) { reject(Error('Une image du CBZ dépasse 8 Mo')); return; }
    zip.openReadStream(entry, (error, stream) => {
      if (error) { reject(error); return; }
      const chunks = []; let count = 0;
      stream.on('data', data => {
        count += data.length;
        if (count > MAX_IMAGE) stream.destroy(Error('Image trop volumineuse')); else chunks.push(data);
      });
      stream.on('error', reject); stream.on('end', () => resolve(Buffer.concat(chunks)));
    });
  });
}
async function importFile(store, file) {
  let pages = [];
  if (fs.statSync(file).isDirectory()) {
    const names = fs.readdirSync(file).filter(name => IMAGE.test(name) && fs.statSync(path.join(file, name)).isFile()).sort(sortNames);
    if (names.length > 3000) throw Error('Maximum 3000 pages par chapitre');
    pages = names.map(name => {
      const target = path.join(file, name);
      if (fs.statSync(target).size > MAX_IMAGE) throw Error('Image supérieure à 8 Mo');
      return saveImage(store, fs.readFileSync(target));
    });
  } else {
    if (!/\.(cbz|zip)$/i.test(file)) throw Error('Choisis un CBZ ou ZIP contenant des images');
    const zip = await openZip(file), entries = [];
    await new Promise((resolve, reject) => {
      zip.on('error', reject);
      zip.on('entry', entry => {
        if (IMAGE.test(entry.fileName) && !entry.fileName.startsWith('__MACOSX/')) entries.push(entry);
        if (entries.length > 3000) { zip.close(); reject(Error('Maximum 3000 pages')); return; }
        zip.readEntry();
      });
      // Keep the archive open while reading page streams after indexing.
      zip.autoClose = false;
      zip.on('end', resolve); zip.readEntry();
    });
    entries.sort((a, b) => sortNames(a.fileName, b.fileName));
    try { for (const entry of entries) pages.push(saveImage(store, await streamBytes(zip, entry))); }
    finally { zip.close(); }
  }
  if (!pages.length) throw Error('Aucune image trouvée');
  const digest = crypto.createHash('sha256').update(pages.join('')).digest('hex');
  const title = path.basename(file).replace(/\.(cbz|zip)$/i, '');
  const url = `desktop-local:${digest}`, key = p.mangaKey('-41783', url), chapter = p.chapterKey(key, 'archive');
  if (!store.state.records.some(r => r.key === key)) store.put(key, 'manga', { sourceId: '-41783', url, title, author: '', artist: '', description: '', thumbnailUrl: '', genre: [], favorite: true,
    notes: '', status: 0, viewerFlags: '0', chapterFlags: '0', categories: ['Local Windows'] });
  if (!store.state.records.some(r => r.key === chapter)) store.put(chapter, 'chapter', { mangaKey: key, url: 'archive', name: title, chapterNumber: 1,
    scanlator: '', sourceOrder: 0, dateUpload: 0, read: false, bookmark: false, lastPageRead: 0, readAt: 0 });
  store.state.files[chapter] = { pages, count: pages.length }; store.save(); return key;
}
module.exports = { importFile, saveImage, imagePath };
