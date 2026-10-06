const QUERY = `query($page:Int,$search:String,$sort:[MediaSort],$country:CountryCode,$genre:String){Page(page:$page,perPage:40){pageInfo{hasNextPage}media(type:MANGA,isAdult:false,search:$search,sort:$sort,countryOfOrigin:$country,genre:$genre){id title{romaji english native}coverImage{large}description(asHtml:false)genres countryOfOrigin format status averageScore meanScore popularity favourites chapters tags{name rank isMediaSpoiler}stats{scoreDistribution{score amount}}}}}`;
let nextRequest = 0;
async function catalogue({ page = 1, search = '', sort = 'TRENDING_DESC', country = '', genre = '' } = {}) {
  if (!Number.isInteger(page) || page < 1 || page > 100 || search.length > 150 || genre.length > 100 || !['TRENDING_DESC', 'POPULARITY_DESC', 'SCORE_DESC', 'START_DATE_DESC'].includes(sort) || !['', 'JP', 'KR', 'CN', 'TW'].includes(country)) throw Error('Filtres invalides');
  if (Date.now() < nextRequest) throw Error('Attends quelques secondes avant une autre recherche');
  nextRequest = Date.now() + 2000;
  const response = await fetch('https://graphql.anilist.co', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ query: QUERY, variables: { page, search: search.trim() || undefined, sort: [sort], country: country || undefined, genre: genre || undefined } }), signal: AbortSignal.timeout(20000) });
  if (response.status === 429) { nextRequest = Date.now() + Math.min(Number(response.headers.get('retry-after')) || 60, 600) * 1000; throw Error('AniList limite les requêtes. Réessaie dans une minute.'); }
  if (!response.ok) throw Error(`Catalogue indisponible (${response.status})`);
  const result = await response.json(); if (result.errors) throw Error('AniList a refusé la recherche');
  return result.data.Page;
}
function rank(media, records) {
  const feedback = new Map(records.filter(r => r.kind === 'feedback' && !r.value.deleted).map(r => [r.value.id, r.value]));
  const weights = new Map();
  for (const r of records.filter(r => r.kind === 'manga' && r.value.favorite)) {
    for (const genre of r.value.genre || []) weights.set(genre, (weights.get(genre) || 0) + 1);
  }
  const maxWeight = Math.max(1, ...weights.values());
  return media.filter(m => !feedback.get(m.id)?.hidden && feedback.get(m.id)?.vote !== -1).map(m => {
    const votes = (m.stats?.scoreDistribution || []).reduce((n, x) => n + x.amount, 0);
    const quality = votes ? (votes * (m.meanScore || m.averageScore || 70) + 100 * 70) / (votes + 100) : 70;
    const matches = (m.genres || []).filter(g => weights.has(g));
    const similarity = matches.reduce((n, g) => n + weights.get(g) / maxWeight, 0) / Math.max(1, (m.genres || []).length);
    const bonus = feedback.get(m.id)?.vote === 1 ? 25 : 0;
    return { ...m, recommendationScore: similarity * 60 + quality * 0.4 + bonus,
      reasons: [matches.length ? `Proche de ta bibliothèque : ${matches.slice(0, 3).join(', ')}` : 'À découvrir', ...(bonus ? ['Tu as aimé ce titre'] : [])] };
  }).sort((a, b) => b.recommendationScore - a.recommendationScore);
}
module.exports = { catalogue, rank };
