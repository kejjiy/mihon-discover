const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const p = require('./protocol.cjs');

class LibraryStore {
  constructor(directory) {
    this.directory = directory; fs.mkdirSync(directory, { recursive: true });
    this.file = path.join(directory, 'library.json');
    this.state = fs.existsSync(this.file) ? JSON.parse(fs.readFileSync(this.file, 'utf8')) : { deviceId: crypto.randomUUID(), records: [], files: {}, settings: { reader: 'paged', direction: 'ltr' } };
    p.validateSnapshot({ version: 1, records: this.state.records });
  }
  save() {
    const tmp = this.file + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(this.state));
    fs.renameSync(tmp, this.file);
  }
  snapshot() { return { version: p.VERSION, records: this.state.records }; }
  merge(snapshot) {
    p.validateSnapshot(snapshot);
    fs.copyFileSync(this.file, this.file + '.before-sync');
    this.state.records = p.mergeRecords(this.state.records, snapshot.records); this.save(); return this.snapshot();
  }
  put(key, kind, value) {
    const old = this.state.records.find(r => r.key === key);
    if (old && p.canonical(old.value) === p.canonical(value)) return old;
    const record = { key, kind, value, modifiedAt: Math.max(Date.now(), (old?.modifiedAt || 0) + 1), deviceId: this.state.deviceId };
    this.state.records = this.state.records.filter(r => r.key !== key); this.state.records.push(record); this.save(); return record;
  }
  progress(key, page, read) {
    const chapter = this.state.records.find(r => r.key === key && r.kind === 'chapter');
    if (!chapter || !Number.isSafeInteger(page) || page < 0) throw Error('Chapitre invalide');
    this.put(key, 'chapter', { ...chapter.value, lastPageRead: Math.max(page, chapter.value.lastPageRead || 0), read: !!read || !!chapter.value.read, readAt: Date.now() });
  }
  feedback(id, vote) {
    if (!Number.isSafeInteger(id) || ![-1, 0, 1].includes(vote)) throw Error('Avis invalide');
    const old = this.state.records.find(r => r.key === `f:${id}`)?.value;
    this.put(`f:${id}`, 'feedback', { id, vote, status: old?.status ?? null, hidden: old?.hidden || false });
  }
}
module.exports = { LibraryStore };
