const api = window.mihon;
const $ = id => document.getElementById(id);
let state, view = 'library', currentBook = null, query = '', selectedCategory = '', catalogueItems = [], cataloguePage = 1, catalogueMore = false;
let reader = null, readerGeneration = 0, observer = null, readingObserver = null, pairId = null, busy = false, toastTimer, manualAddress = '';
const el = (tag, className, text) => { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; };
const button = (text, className, action) => { const node = el('button', className, text); node.type = 'button'; node.addEventListener('click', () => run(action)); return node; };
function toast(message) { $('toast').textContent = message.replace(/^Error invoking remote method '[^']+': Error: /, ''); $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 6500); }
async function run(action) { try { await action(); } catch (error) { toast(error.message); } }
async function transaction(action) { if (busy) return; busy = true; try { await action(); } finally { busy = false; } }
const chapterRecords = manga => state.records.filter(r => r.kind === 'chapter' && r.value.mangaKey === manga);
const title = media => media.title?.english || media.title?.romaji || media.title?.native || 'Sans titre';
function safeImage(url) { try { return new URL(url).protocol === 'https:' ? url : null; } catch { return null; } }
function cover(name, url, label) {
  const node = el('div', 'cover');
  if (safeImage(url)) { const image = el('img'); image.src = url; image.alt = ''; image.loading = 'lazy'; image.addEventListener('error', () => { image.remove(); node.append(el('span', 'initial', name[0]?.toUpperCase() || 'M')); }); node.append(image); }
  else node.append(el('span', 'initial', name[0]?.toUpperCase() || 'M'));
  if (label) node.append(el('span', 'cover-label', label)); return node;
}
function empty(parent, heading, description, action) {
  const node = el('div', 'empty'); node.append(el('div', 'symbol', '▥'), el('h2', '', heading), el('p', '', description)); if (action) node.append(action); parent.append(node);
}
function updateState(value) {
  const dataChanged = !state || JSON.stringify([state.records, state.files]) !== JSON.stringify([value.records, value.files]);
  const signature = lan => JSON.stringify([lan.enabled, lan.addresses, lan.trusted, lan.peers.map(({ id, name, platform, host, port, trusted }) => ({ id, name, platform, host, port, trusted }))]);
  const networkChanged = !state || signature(state.lan) !== signature(value.lan);
  state = value; $('network-badge').textContent = value.lan.enabled ? 'Réseau local actif' : 'Réseau désactivé'; $('network-badge').classList.toggle('active', value.lan.enabled);
  updateReaderHud();
  $('peer-count').textContent = value.lan.peers.length || '';
  updatePair(); if (!reader && (dataChanged || (view === 'sync' && networkChanged))) render();
}
function updatePair() {
  const pairing = state.lan.pairing[0], dialog = $('pair-dialog');
  if (!pairing) { if (dialog.open) dialog.close(); pairId = null; return; }
  pairId = pairing.pairId; $('pair-title').textContent = `Associer ${pairing.name}`;
  $('pair-code').textContent = `${pairing.code.slice(0, 3)} ${pairing.code.slice(3)}`;
  $('pair-status').textContent = pairing.approved ? 'En attente de confirmation sur l’autre appareil…' : 'L’association expire après 2 minutes.';
  $('pair-accept').disabled = pairing.approved;
  if (!dialog.open) dialog.showModal();
}
function navigate(next) { view = next; currentBook = null; document.querySelectorAll('nav button').forEach(node => node.classList.toggle('selected', node.dataset.view === view)); render(); }
function render() {
  if (!state) return;
  const titles = { library: ['TA COLLECTION', 'Bibliothèque'], history: ['REPRENDS LE FIL', 'Historique'], catalogue: ['EXPLORE DE NOUVEAUX MONDES', 'Catalogue'], recommend: ['SELON TES GOÛTS', 'Pour toi'], sync: ['TES APPAREILS, TA LECTURE', 'Synchronisation'] };
  $('eyebrow').textContent = titles[view][0]; $('title').textContent = titles[view][1]; $('content').replaceChildren();
  if (currentBook && view === 'library') return renderBook(currentBook);
  if (view === 'library') renderLibrary();
  if (view === 'history') renderHistory();
  if (view === 'sync') renderSync();
  if (view === 'catalogue') renderCatalogue();
  if (view === 'recommend') run(renderRecommendations);
}
function historyEntries() {
  const mangas = new Map(state.records.filter(r => r.kind === 'manga').map(r => [r.key, r]));
  return state.records.filter(r => r.kind === 'chapter' && ((r.value.readAt || 0) > 0 || r.value.read || r.value.lastPageRead > 0) && mangas.has(r.value.mangaKey))
    .map(chapter => ({ chapter, manga: mangas.get(chapter.value.mangaKey) }))
    .sort((a, b) => (b.chapter.value.readAt || 0) - (a.chapter.value.readAt || 0) || a.chapter.key.localeCompare(b.chapter.key));
}
function historyRow({ chapter, manga }) {
  const value = chapter.value, row = el('div', 'history-row');
  const open = button('', 'history-open', () => openReader(chapter.key));
  const info = el('div', 'history-info');
  info.append(el('strong', '', manga.value.title), el('span', '', value.name));
  const date = value.readAt > 0 ? new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value.readAt)) : 'Date de lecture indisponible';
  info.append(el('small', '', `${date} · ${value.read ? 'Lu' : 'En cours'} · Page ${(value.lastPageRead || 0) + 1}`));
  open.append(cover(manga.value.title, manga.value.thumbnailUrl), info, el('span', 'history-action', 'Reprendre →'));
  row.append(open); return row;
}
function renderHistory() {
  const parent = $('content'), entries = historyEntries();
  parent.append(el('p', 'muted', 'Tes chapitres lus ou commencés sur tes appareils. Synchronise pour récupérer les dernières lectures de ton téléphone.'));
  const search = el('input'); search.type = 'search'; search.placeholder = 'Rechercher un titre ou un chapitre'; search.setAttribute('aria-label', 'Rechercher dans l’historique'); search.className = 'history-search';
  const list = el('div', 'history-list'); parent.append(search, list);
  function draw() {
    list.replaceChildren(); const query = search.value.trim().toLocaleLowerCase('fr');
    const filtered = entries.filter(({ chapter, manga }) => `${manga.value.title} ${chapter.value.name}`.toLocaleLowerCase('fr').includes(query));
    if (!filtered.length) return empty(list, entries.length ? 'Aucune lecture trouvée' : 'Ton historique apparaîtra ici', entries.length ? 'Essaie un autre titre ou chapitre.' : 'Lis un chapitre ou synchronise ton téléphone pour retrouver tes lectures.', entries.length ? null : button('Synchroniser mes appareils', 'primary', () => navigate('sync')));
    let previousDay;
    for (const entry of filtered) {
      const readAt = entry.chapter.value.readAt || 0;
      const day = readAt > 0 ? new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full' }).format(new Date(readAt)) : 'Lectures sans date';
      if (day !== previousDay) { list.append(el('h3', 'history-day', day)); previousDay = day; }
      list.append(historyRow(entry));
    }
  }
  search.addEventListener('input', draw); draw();
}
function renderLibrary() {
  const parent = $('content'), mangas = state.records.filter(r => r.kind === 'manga');
  const stats = el('div', 'stats');
  for (const [count, label] of [[mangas.filter(r => r.value.favorite).length, 'titres suivis'], [state.records.filter(r => r.kind === 'chapter' && r.value.read).length, 'chapitres lus'], [Object.values(state.files).filter(f => f.cached === f.count).length, 'chapitres hors ligne']]) { const item = el('span'); item.append(el('b', '', count), label); stats.append(item); }
  parent.append(stats);
  const latest = historyEntries()[0];
  if (latest) { const resume = el('section', 'resume-reading'); resume.append(el('h3', '', 'Dernière lecture'), historyRow(latest), button('Tout l’historique', 'subtle', () => navigate('history'))); parent.append(resume); }
  const toolbar = el('div', 'toolbar'), search = el('input'); search.placeholder = 'Rechercher dans ta bibliothèque'; search.setAttribute('aria-label', 'Rechercher dans ta bibliothèque'); search.value = query;
  const categories = el('select'); categories.setAttribute('aria-label', 'Catégorie'); categories.append(new Option('Toutes les catégories', ''));
  [...new Set(mangas.flatMap(r => r.value.categories || []))].sort().forEach(name => categories.append(new Option(name, name))); categories.value = selectedCategory;
  toolbar.append(search, categories); parent.append(toolbar);
  const grid = el('div', 'grid'); parent.append(grid);
  function draw() {
    grid.replaceChildren(); const filtered = mangas.filter(r => (r.value.favorite || query) && r.value.title.toLowerCase().includes(query.toLowerCase()) && (!selectedCategory || r.value.categories?.includes(selectedCategory)));
    filtered.sort((a, b) => a.value.title.localeCompare(b.value.title, 'fr')).forEach(manga => {
      const chapters = chapterRecords(manga.key), read = chapters.filter(c => c.value.read).length;
      const node = button('', 'book', () => { currentBook = manga.key; render(); }); node.append(cover(manga.value.title, manga.value.thumbnailUrl, `${read} / ${chapters.length} lus`), el('h3', '', manga.value.title), el('small', '', manga.value.categories?.join(' · ') || manga.value.author || 'Bibliothèque synchronisée')); grid.append(node);
    });
    if (!filtered.length) empty(grid, mangas.length ? 'Aucun titre ici' : 'Ta prochaine lecture commence ici', mangas.length ? 'Modifie ta recherche ou ta catégorie.' : 'Importe un CBZ ou associe ton téléphone pour retrouver ta bibliothèque, tes chapitres et ta progression.', button('Associer mon téléphone', 'primary', () => navigate('sync')));
  }
  search.addEventListener('input', () => { query = search.value; draw(); }); categories.addEventListener('change', () => { selectedCategory = categories.value; draw(); }); draw();
}
function renderBook(key) {
  const parent = $('content'), manga = state.records.find(r => r.key === key); if (!manga) { currentBook = null; return render(); }
  parent.append(button('← Tous les titres', 'subtle', () => { currentBook = null; render(); }));
  const detail = el('div', 'detail'), info = el('div'); detail.append(cover(manga.value.title, manga.value.thumbnailUrl), info);
  info.append(el('h2', '', manga.value.title), el('p', 'muted', [manga.value.author, manga.value.artist].filter(Boolean).join(' · ')));
  const tags = el('div', 'detail-meta'); (manga.value.genre || []).forEach(genre => tags.append(el('span', 'tag', genre))); info.append(tags);
  info.append(el('p', 'detail-description', manga.value.description || 'Aucune description disponible.'), button(manga.value.favorite ? '♥ Dans ma bibliothèque' : '♡ Ajouter à ma bibliothèque', 'secondary', () => api.edit(key, { favorite: !manga.value.favorite })));
  parent.append(detail);
  const notes = el('div', 'notes'), noteInput = el('textarea'), categoryInput = el('input'); noteInput.placeholder = 'Tes notes personnelles'; noteInput.setAttribute('aria-label', 'Notes personnelles'); noteInput.value = manga.value.notes || ''; categoryInput.placeholder = 'Catégories séparées par des virgules'; categoryInput.setAttribute('aria-label', 'Catégories'); categoryInput.value = (manga.value.categories || []).join(', ');
  notes.append(noteInput, categoryInput, button('Enregistrer notes et catégories', 'secondary', async () => { await api.edit(key, { notes: noteInput.value, categories: [...new Set(categoryInput.value.split(',').map(x => x.trim()).filter(Boolean))].sort() }); toast('Notes enregistrées'); })); parent.append(notes);
  const list = el('div', 'chapter-list'); const chapters = chapterRecords(key).sort((a, b) => b.value.chapterNumber - a.value.chapterNumber || a.value.sourceOrder - b.value.sourceOrder);
  list.append(el('h3', '', `${chapters.length} chapitres`));
  if (!chapters.length) list.append(el('p', 'muted', 'Ouvre ce titre sur ton téléphone pour récupérer ses chapitres, puis synchronise à nouveau.'));
  chapters.forEach(chapter => {
    const row = el('div', `chapter-row${chapter.value.read ? ' read' : ''}`), cached = state.files[chapter.key];
    const open = button('', 'name', () => openReader(chapter.key)); open.append(el('strong', '', chapter.value.name), el('small', '', [chapter.value.read ? 'Lu' : chapter.value.lastPageRead ? `Page ${chapter.value.lastPageRead + 1}` : 'À lire', cached?.cached === cached?.count && cached ? 'Hors ligne' : 'Via le téléphone', chapter.value.scanlator].filter(Boolean).join(' · ')));
    row.append(open, button(chapter.value.bookmark ? '★' : '☆', 'secondary', () => api.edit(chapter.key, { bookmark: !chapter.value.bookmark })), button('↓ Télécharger', 'secondary', () => transaction(async () => { toast('Téléchargement depuis le téléphone…'); const count = await api.download(chapter.key); toast(`${count} pages disponibles hors ligne`); })));
    list.append(row);
  }); parent.append(list);
}
function renderSync() {
  const parent = $('content'), hero = el('div', 'hero'); hero.append(el('h2', '', 'Un appareil à l’autre. La même lecture.'), el('p', '', 'Retrouve ta bibliothèque et ta progression sur le même réseau, sans compte de synchronisation.')); parent.append(hero);
  const layout = el('div', 'sync-layout'), left = el('div'), right = el('div'); layout.append(left, right); parent.append(layout);
  const discover = el('div', 'panel'); discover.append(el('h3', '', 'Appareils à proximité'), el('p', '', 'Sur Android : Plus → Synchronisation locale → Découvrir mes appareils. Garde cet écran ouvert pendant les échanges.'), button(state.lan.enabled ? 'Arrêter la découverte' : 'Découvrir mes appareils', 'primary', () => api.toggleLan(!state.lan.enabled)));
  if (state.lan.enabled && !state.lan.peers.length) discover.append(el('p', 'muted', 'Recherche en cours… Vérifie que tes appareils utilisent le même Wi-Fi.'));
  state.lan.peers.forEach(device => {
    const row = el('div', 'device'), info = el('div', 'device-info'); info.append(el('strong', '', device.name), el('small', '', `${device.platform === 'android' ? 'Android' : 'Windows'} · ${device.trusted ? 'Associé' : 'À associer'}`));
    const action = button(device.trusted ? 'Synchroniser' : 'Associer', device.trusted ? 'primary' : 'secondary', () => transaction(() => device.trusted ? api.sync(device.id) : api.pair(device.id))); row.append(el('span', 'device-icon', device.platform === 'android' ? '▯' : '▣'), info, action); discover.append(row);
  }); left.append(discover);
  if (state.lan.enabled && state.lan.peers.some(peer => peer.trusted)) discover.append(button('Synchroniser mes appareils', 'primary', () => transaction(() => api.syncAll())));
  const manual = el('div', 'panel'); manual.append(el('h3', '', 'Connexion manuelle'), el('p', '', 'Si le routeur bloque la découverte, saisis l’adresse affichée dans l’app Android.'));
  state.lan.addresses.forEach(address => manual.append(el('p', 'address', `Ce PC : ${address}`)));
  const form = el('form', 'manual'), input = el('input'); input.placeholder = '192.168.1.20:12345'; input.value = manualAddress; input.addEventListener('input', () => manualAddress = input.value); input.setAttribute('aria-label', 'Adresse locale et port'); const submit = el('button', 'secondary', 'Rechercher'); submit.type = 'submit'; submit.disabled = !state.lan.enabled; form.append(input, submit);
  form.addEventListener('submit', event => { event.preventDefault(); const [host, port] = input.value.trim().split(':'); run(() => api.manual(host, Number(port))); }); manual.append(form); left.append(manual);
  const data = el('div', 'panel'); data.append(el('h3', '', 'Ce qui te suit'), el('p', '', 'Bibliothèque, favoris, notes et catégories. Chapitres lus, progression, marque-pages et dates de lecture. Avis, associations et filtres enregistrés du catalogue Discover.'), el('h3', '', 'Une fusion prévisible'), el('p', '', 'La progression la plus avancée est conservée. Les autres modifications les plus récentes gagnent. Une sauvegarde est créée avant la fusion.'), el('p', '', 'Les comptes, mots de passe et fichiers d’extensions restent sur leur appareil. Les images sont transférées uniquement quand tu lis ou télécharges un chapitre.'), el('p', 'muted', 'Le PC doit autoriser l’app sur le réseau privé dans le pare-feu Windows. Le réseau invité ou l’isolation Wi-Fi peuvent bloquer les connexions.')); right.append(data);
  const trust = el('div', 'panel'); trust.append(el('h3', '', 'Appareils associés'));
  if (!state.lan.trusted.length) trust.append(el('p', '', 'Une première association demande de comparer un code sur les deux écrans.'));
  state.lan.trusted.forEach(peer => { const row = el('div', 'device'); row.append(el('span', 'device-info', peer.name), button('Oublier', 'subtle', async () => { if (await confirm('Oublier cet appareil ?', 'Il ne pourra plus accéder à tes données. Pour une nouvelle association, oublie également le PC dans l’autre app.')) await api.forget(peer.id); })); trust.append(row); }); right.append(trust);
}
function catalogueCard(media, reasons = false) {
  const node = button('', 'book', () => renderMedia(media)); node.append(cover(title(media), media.coverImage?.large, media.averageScore ? `${media.averageScore} / 100` : media.format), el('h3', '', title(media)), el('small', '', `${media.countryOfOrigin || ''} · ${media.chapters ? `${media.chapters} chapitres` : media.status || 'En cours'}`));
  if (reasons) node.append(el('div', 'reason', media.reasons.join(' · '))); return node;
}
function renderCatalogue() {
  const parent = $('content'), form = el('form', 'toolbar'), search = el('input'); search.placeholder = 'Rechercher un manga, manhwa, manhua…'; search.setAttribute('aria-label', 'Rechercher sur AniList');
  const sort = el('select'), country = el('select'), genre = el('input'); sort.setAttribute('aria-label', 'Trier le catalogue'); country.setAttribute('aria-label', 'Pays d’origine'); genre.placeholder = 'Genre (ex. Action)'; genre.setAttribute('aria-label', 'Genre AniList');
  [['Tendances','TRENDING_DESC'],['Popularité','POPULARITY_DESC'],['Note','SCORE_DESC'],['Nouveautés','START_DATE_DESC']].forEach(([label, value]) => sort.append(new Option(label,value)));
  [['Tous les pays',''],['Japon','JP'],['Corée','KR'],['Chine','CN'],['Taïwan','TW']].forEach(([label,value]) => country.append(new Option(label,value)));
  const submit = el('button', 'primary', 'Rechercher'); submit.type = 'submit'; form.append(search, sort, country, genre, submit); parent.append(form, el('p', 'catalogue-note', 'Catalogue AniList · Les chapitres se lisent depuis ta bibliothèque synchronisée ou tes fichiers locaux.'));
  const grid = el('div', 'grid'); parent.append(grid); const more = button('Charger la suite', 'secondary load-more', () => load(true)); parent.append(more);
  const draw = () => { grid.replaceChildren(); catalogueItems.forEach(media => grid.append(catalogueCard(media))); more.hidden = !catalogueMore; };
  async function load(append = false) {
    submit.disabled = true; more.disabled = true;
    try {
      const result = await api.catalogue({ page: append ? cataloguePage + 1 : 1, search: search.value, sort: sort.value, country: country.value, genre: genre.value });
      cataloguePage = append ? cataloguePage + 1 : 1; catalogueMore = result.pageInfo.hasNextPage; catalogueItems = append ? [...catalogueItems, ...result.media] : result.media; draw();
      if (!catalogueItems.length) empty(grid, 'Aucun titre trouvé', 'Essaie une autre recherche.');
    } finally { submit.disabled = false; more.disabled = false; }
  }
  form.addEventListener('submit', event => { event.preventDefault(); run(() => load()); }); draw();
  if (!catalogueItems.length) empty(grid, 'Explore le catalogue', 'Recherche un titre ou choisis un pays et un genre, puis lance la recherche. Les dernières fiches chargées sont conservées pour consulter le catalogue hors ligne.', button('Voir les tendances', 'primary', () => load()));
}
async function renderRecommendations() {
  const parent = $('content'), result = await api.recommend(); if (view !== 'recommend') return;
  parent.append(el('p', 'catalogue-note', 'Classement local des fiches AniList mises en cache, à partir des genres de ta bibliothèque et de tes avis.'));
  if (!result.length) { empty(parent, 'Des idées pour ta prochaine lecture', 'Charge d’abord des fiches dans le catalogue et synchronise ta bibliothèque. Tes avis restent locaux et peuvent suivre tes appareils.', button('Explorer le catalogue', 'primary', () => navigate('catalogue'))); return; }
  const grid = el('div', 'grid'); result.forEach(media => grid.append(catalogueCard(media, true))); parent.append(grid);
}
function renderMedia(media) {
  const parent = $('content'); parent.replaceChildren(button('← Retour au catalogue', 'subtle', render));
  const detail = el('div', 'detail'), info = el('div'); detail.append(cover(title(media), media.coverImage?.large), info);
  info.append(el('h2', '', title(media)), el('p', 'detail-description', (media.description || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')));
  const tags = el('div', 'detail-meta'); media.genres.forEach(g => tags.append(el('span', 'tag', g))); info.append(tags);
  const actions = el('div', 'catalogue-actions'); actions.append(button('♡ J’aime', 'primary', async () => { await api.feedback(media.id, 1); toast('Avis enregistré'); }), button('Je n’aime pas', 'secondary', async () => { await api.feedback(media.id, -1); toast('Avis enregistré'); })); info.append(actions, el('p', 'muted', 'Pour lire : ajoute ce titre depuis une source sur ton téléphone, puis synchronise la bibliothèque.'));
  parent.append(detail);
}
async function openReader(key) {
  const opened = await api.openChapter(key), chapter = state.records.find(r => r.key === key);
  reader = { key, count: opened.count, page: opened.page, chapter }; $('shell').hidden = true; $('reader').hidden = false; $('reader-title').textContent = chapter.value.name;
  $('reader-mode').value = state.settings.reader; $('reader-direction').value = state.settings.direction; $('page-total').textContent = `/ ${reader.count}`; $('page-number').max = reader.count;
  await renderReader();
}
async function renderReader() {
  const generation = ++readerGeneration; observer?.disconnect(); readingObserver?.disconnect(); const container = $('page-container'); container.replaceChildren();
  if (!reader) return; const mode = $('reader-mode').value; container.className = mode; updateReaderHud();
  if (mode === 'paged') return showPage(reader.page);
  const resume = reader.page;
  const visible = new Set();
  function trackVisible() {
    if (!reader || generation !== readerGeneration) return;
    const area = container.getBoundingClientRect();
    const nodes = [...visible].filter(node => node.dataset.loaded).sort((a, b) => Number(a.dataset.index) - Number(b.dataset.index));
    const current = nodes.find(node => node.getBoundingClientRect().bottom > area.top + Math.min(100, area.height / 4));
    if (!current) return;
    const index = Number(current.dataset.index), bounds = current.getBoundingClientRect();
    saveProgress(index, index === reader.count - 1 && bounds.bottom <= area.bottom + 2);
  }
  readingObserver = new IntersectionObserver(entries => {
    entries.forEach(entry => { if (entry.isIntersecting) visible.add(entry.target); else visible.delete(entry.target); }); trackVisible();
  }, { root: container, threshold: 0 });
  container.onscroll = () => { requestAnimationFrame(trackVisible); };
  observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      const node = entry.target, index = Number(node.dataset.index);
      if (entry.isIntersecting && !node.dataset.loading) {
        node.dataset.loading = 'true';
        run(async () => {
          const url = await api.page(reader.key, index); if (generation !== readerGeneration || !reader) return;
          const image = el('img'); image.alt = `Page ${index + 1}`; image.src = url;
          image.addEventListener('load', () => { node.dataset.loaded = 'true'; trackVisible(); }); node.replaceChildren(image);
        });
      }
    }
  }, { root: container, rootMargin: '500px 0px', threshold: 0.05 });
  for (let index = 0; index < reader.count; index++) {
    const node = el('div', 'webtoon-page'); node.dataset.index = index; node.append(el('div', 'loading', `Page ${index + 1}…`)); container.append(node); observer.observe(node); readingObserver.observe(node);
  }
  container.children[resume]?.scrollIntoView(); $('page-number').value = resume + 1; $('reader-status').textContent = 'Défile pour lire · F plein écran · Échap pour revenir';
}
async function showPage(index) {
  if (!reader) return; index = Math.max(0, Math.min(index, reader.count - 1)); const generation = ++readerGeneration;
  const active = reader; $('reader-status').textContent = 'Chargement de la page…';
  const url = await api.page(active.key, index); if (generation !== readerGeneration || reader !== active) return;
  const image = el('img'); image.alt = `Page ${index + 1}`;
  image.addEventListener('load', () => { if (generation !== readerGeneration || reader !== active) return; reader.page = index; saveProgress(index); $('reader-status').textContent = '← → changer de page · F plein écran'; });
  image.addEventListener('error', () => toast('Cette image ne peut pas être affichée.')); image.src = url;
  $('page-container').replaceChildren(image); $('page-number').value = index + 1; $('previous').disabled = index === 0; $('next').disabled = index === reader.count - 1;
}
function saveProgress(index, completed = true) {
  if (!reader) return;
  reader.page = index; $('page-number').value = index + 1;
  $('previous').disabled = index === 0; $('next').disabled = index === reader.count - 1;
  const read = completed && index === reader.count - 1;
  if (reader.lastSavedIndex === index && (!read || reader.lastSavedRead)) return;
  reader.lastSavedIndex = index; reader.lastSavedRead = read;
  run(() => api.progress(reader.key, index, read));
}
function updateReaderHud() {
  const immersive = !!reader && !!state?.fullscreen && $('reader-mode').value === 'webtoon';
  document.body.classList.toggle('reader-immersive', immersive);
  $('fullscreen').setAttribute('aria-label', state?.fullscreen ? 'Quitter le plein écran' : 'Plein écran');
  if (immersive && document.activeElement?.closest('.reader-toolbar, .reader-footer')) $('page-container').focus({ preventScroll: true });
}
async function closeReader() { ++readerGeneration; observer?.disconnect(); readingObserver?.disconnect(); $('page-container').onscroll = null; reader = null; updateReaderHud(); await api.fullscreen(false); $('reader').hidden = true; $('shell').hidden = false; updateState(await api.state()); render(); }
function jump(index) { if (!reader) return; if ($('reader-mode').value === 'webtoon') $('page-container').children[Math.max(0, Math.min(index, reader.count - 1))]?.scrollIntoView(); else return showPage(index); }
function confirm(heading, text) {
  return new Promise(resolve => { const dialog = $('confirm-dialog'); $('confirm-title').textContent = heading; $('confirm-text').textContent = text;
    const end = value => { dialog.close(); $('confirm-no').onclick = null; $('confirm-yes').onclick = null; dialog.oncancel = null; resolve(value); };
    $('confirm-no').onclick = () => end(false); $('confirm-yes').onclick = () => end(true); dialog.oncancel = event => { event.preventDefault(); end(false); }; dialog.showModal(); });
}
document.querySelectorAll('nav button').forEach(node => node.addEventListener('click', () => navigate(node.dataset.view)));
$('import').onclick = () => run(() => transaction(() => api.import(false))); $('import-folder').onclick = () => run(() => transaction(() => api.import(true)));
$('export').onclick = () => run(() => api.export()); $('import-snapshot').onclick = () => run(() => api.importSnapshot());
$('pair-accept').onclick = () => run(() => api.approve(pairId, true)); $('pair-reject').onclick = () => run(() => api.approve(pairId, false));
$('pair-dialog').addEventListener('cancel', event => { event.preventDefault(); run(() => api.approve(pairId, false)); });
$('reader-back').onclick = () => run(closeReader); $('fullscreen').onclick = () => run(() => api.fullscreen());
$('previous').onclick = () => run(() => jump(reader.page - 1)); $('next').onclick = () => run(() => jump(reader.page + 1));
$('page-number').addEventListener('change', () => run(() => jump(Number($('page-number').value) - 1)));
for (const name of ['reader-mode', 'reader-direction']) $(name).addEventListener('change', () => run(async () => { await api.settings({ reader: $('reader-mode').value, direction: $('reader-direction').value }); await renderReader(); }));
document.addEventListener('keydown', event => {
  if (!reader || document.querySelector('dialog[open]') || event.ctrlKey || event.altKey || event.metaKey) return;
  if (event.key === 'Escape' && state.fullscreen) { event.preventDefault(); if (!event.repeat) run(() => api.fullscreen(false)); return; }
  if (['INPUT','TEXTAREA','SELECT'].includes(event.target.tagName)) return;
  if (event.key === 'Escape') { event.preventDefault(); if (!event.repeat) run(closeReader); return; }
  if (event.key.toLowerCase() === 'f') { event.preventDefault(); if (!event.repeat) run(() => api.fullscreen()); return; }
  const reverse = $('reader-direction').value === 'rtl' ? -1 : 1;
  if (event.key === 'ArrowRight') { event.preventDefault(); run(() => jump(reader.page + reverse)); }
  if (event.key === 'ArrowLeft') { event.preventDefault(); run(() => jump(reader.page - reverse)); }
  if (event.key === 'Home') run(() => jump(0)); if (event.key === 'End') run(() => jump(reader.count - 1));
});
api.onState(updateState); api.onNotice(toast); api.onDownload(({ done, count }) => toast(`Téléchargement : ${done} / ${count} pages`));
run(async () => { catalogueItems = await api.cachedCatalogue(); updateState(await api.state()); });
