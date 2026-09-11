# Subtitle search: matching, cascades and ordering

The behaviour to reproduce, taken from two places that already solved it:

- `torbox-advanced` (the web backend) — `src/app/api/lib/subtitles/normalize.js`
  and `cascade.js`. This is where the ordering lives.
- `iina-episode-info` — `main.js`. This is where the per-service cascades live,
  written against the same three sources with no backend in between.

Just Player Pro has no backend, so it takes the *cascades* from the plugin and
the *ordering* from the web app, and runs both on-device.

## Per-service parameter rules

These differ in ways that fail quietly — a wrong id format returns an empty list,
not an error.

| | OpenSubtitles | SubDL | Wyzie |
|---|---|---|---|
| IMDb id | numeric, **no `tt`**, leading zeros stripped | **with `tt`** | with `tt` |
| Language | lowercase (`en`) | **UPPERCASE** (`EN`) | lowercase (`en`) |
| Param order | **must be alphabetical** | any | any |
| Key | `Api-Key` header + `User-Agent` | `api_key` query param | `key` query param |
| Series | `parent_imdb_id` + `season_number` + `episode_number` | `tmdb_id` + `type=tv` + s/e | `id` + `season` + `episode` |

**The alphabetical rule is not folklore.** Verified against the live API:
`?imdb_id=…&season_number=1&episode_number=1&languages=en` returns `301` with no
body; the same parameters sorted
(`?episode_number=1&imdb_id=…&languages=en&season_number=1`) returns `200` with
results. Build the query from a sorted map, never by hand.

## Cascades

Tried in order; the first attempt that returns anything wins. The label of the
winning attempt is kept, because ordering uses it as a confidence signal.

**OpenSubtitles — movie:** `imdb_id` → `tmdb_id` + year → text query + year +
`type=movie`.

**OpenSubtitles — episode**, per their documented guidance (an episode's own id
must be sent *without* season/episode numbers, while a parent id must be sent
*with* them):

1. `imdb_id` = the episode's own id, no season/episode
2. `parent_imdb_id` + season + episode ← the recommended pattern
3. `parent_tmdb_id` + season + episode
4. text query + season + episode + `type=episode`

**SubDL — movie:** `tmdb_id` + `type=movie` (+ year) → `imdb_id` → `film_name`.

**Wyzie:** `id` (IMDb) + `language` + `format=srt` + `key`, with season/episode
added for an episode and omitted for a whole-show search.

A manual/typed query is always an independent search: no saved episode
parameters bleed into it.

## Ordering

`sortResults` ranks by `scoreSubtitle`, then breaks ties by language name, then
download count, then source label.

```
+120  row is in a language that was actually asked for
+ 60  release names exactly the requested episode
- 90  release names a DIFFERENT episode
+ 25  found by an id lookup (attempt label matches imdb/tmdb/addon)
- 10  found by text search
+ 40  title overlap >= 0.8   (+15 at >= 0.5)
+ 20  expected year present (movies)
+ 20  season pack containing the requested episode
-  8  machine-translated
+  5  from a trusted uploader
+/- 8..10  release-quality nudge (CAM/TS punished, BluRay/WEB rewarded)
```

Two rules carry most of the weight, and both were written against observed
failures:

- **Language dominates at +120.** Ordering used to be alphabetical by language,
  which was harmless while sources returned one or two languages — but addons
  return up to 29, so "Albanian" sorted above "English" and the top of the list
  became a language nobody asked for.
- **A wrong episode (-90) is punished harder than a right one (+60) is
  rewarded.** Being shown a different episode of the right show is the worst
  outcome available: the title looks right and the file is useless. -90 sinks
  those below every plausible row, including season packs, which genuinely do
  contain the episode and so score positive.

Release-name comparison normalises first: lowercase, dots and underscores to
spaces, punctuation stripped, whitespace collapsed. Episode matching accepts
`s01e01`, `1x01` and `season 1 episode 1`, all zero-tolerant.

## The one thing a player can do that the web app cannot

`releaseQualityNudge` is deliberately weak, and its comment says why: *"This app
cannot know which file the user has, so the nudge follows the odds rather than
asserting a match."*

A player **does** know. The file being played has a name, and feature #5 already
parses it into release group, resolution, source and codec. So Just Player Pro
can add a signal the web app had no way to compute: score the subtitle's release
name against the *actual* file's, because a subtitle timed to the same release
is the one that will be in sync.

This is additive. The scoring above is ported unchanged, and the filename term
sits on top of it — so behaviour matches the web app when the filename tells us
nothing, and beats it when it does.
