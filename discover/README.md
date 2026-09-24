# Local recommendations

The pure Kotlin scorer lives in `discover/engine`; Android and Mihon adapters remain in
`app/src/main/java/mihon/discover/recommendation`. The only new upstream touch points are the
AniList rate gate and the manga info badge. Catalogue UI and data access stay in `mihon.discover`.

`RecommendationStore` owns `discover.db`. It stores cached AniList metadata, confirmed source
links, local likes/dislikes, reading status, hidden entries, presets and romance preferences.
The schema is independent of Mihon's SQLDelight migrations. A one-time import reads the older
Discover SharedPreferences cache. Personal data can be exported or deleted from Catalogue → Profil.

The scorer uses Mihon's library and chapter progress, recent history, explicit AniList tracking,
tracker scores, and local feedback. When an AniList ID is not available, local genres can still
shape the profile, but the app does not guess an association from a similar title. AniList supplies
candidate metadata only; the reading profile and ranking stay on-device.

Rating quality uses `(votes × mean + 100 × 70) / (votes + 100)`. Missing vote counts get a
neutral quality value, never a fabricated count. Tags carry 75% of content similarity and genres
25%. Primary romance evidence incurs a strong default penalty. Discovery mode diversifies the
first results and rewards less familiar themes. Every rank carries short local explanations.

Candidate collection samples AniList's trending, popular, rated, recent and relevant tag pages.
This is a bounded pool, not a complete crawl of AniList. Offline ranking uses the last cached
pool; it cannot discover a new work until AniList is reachable. Source matching still considers
only installed and enabled extensions, with manual confirmation for ambiguous results.

Explorer, For You and Discovery share one catalogue filter selection and the same saved presets.
Recommendations first fetch AniList candidates under the selected public filters (for example,
`KR` plus `Post-Apocalyptic`), then apply the same predicate to cached candidates before local
ranking. Library and feedback filters are evaluated from Mihon's local data in all three modes.
