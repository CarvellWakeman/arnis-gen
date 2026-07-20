# Roadmap: On-Demand Server Terrain Generator

Tracks the top-level work items for refactoring `arnis` from an offline full-world
builder into an on-demand, region-streaming server terrain generator. Full rationale,
architecture, and design detail live in [`worldgen.md`](./worldgen.md) — this file is
just the scannable checklist of deliverables.

## Summary

- **Rust engine**: add a region-bake mode so `arnis` can produce a single Minecraft
  region file on demand, using a fixed global coordinate origin.
- **Java plugin (new, Paper)**: a thin orchestrator that tracks players, calls the Rust
  engine ahead of them, and fills unbaked terrain with void chunks in the meantime.
- **Delivery**: four phases, from proving the Rust bake mode standalone through to a
  fully automatic streaming plugin with polish.

## 1. Rust engine changes (`arnis` core)

- [x] **`--bake-region` mode** (`src/bake.rs`, `src/args.rs`, dispatched from `src/main.rs`)
  - Bake one region into an existing world's `region/` dir. _(multi-region range: pending)_
  - Derive lat/lon bbox from region block bounds via `WebMercatorProjection::inverse`,
    expanded by a bake margin for cross-region features (roads/rivers/bridges).
  - Reuse `data_processing::generate_world_with_options`; write only the target
    region file(s); skip one-time world artifacts (`level.dat`, spawn, maps, etc.).
- [x] **Fixed/explicit projection origin** — stop deriving `web_mercator` origin from
  per-run bbox center; thread a fixed `(origin_lat, origin_lon)` through
  (`CoordTransformer::with_web_mercator_origin`, `parse_osm_data_with_origin`) so
  independent bakes stay aligned.
- [x] **Machine-readable output** — emit JSON to stdout (`ARNIS_BAKE_RESULT`) for the
  plugin to parse.
- [x] **Global data caching** — land cover and elevation were already tile-cached;
  added a tiled on-disk OSM cache (`src/osm_cache.rs`, `arnis-osm-cache`) that fetches
  whole geographic grid tiles once and merges covering tiles per region, so adjacent
  region bakes reuse Overpass fetches. _(multi-region-per-invocation batching still
  optional/pending.)_
- [ ] **Height/lighting config** — match baked Y range to the server world
  (`--ground-level` / tall-world datapack); support `--bake-lighting`; verify chunk
  NBT `Status = minecraft:full` so baked chunks are accepted without regeneration.

## 2. Java Paper plugin (new module, `arnis-paper/`)

- [x] **`VoidChunkGenerator`** — empty-air generator for the arnis world; only fills
  gaps not yet baked (chunk storage is consulted first, so a baked region loads verbatim).
- [x] **`RegionBaker`** — runs `arnis --bake-region …` as a subprocess and parses the
  `ARNIS_BAKE_RESULT` line. Pooled + de-duplicated by `BakeService` (bounded worker
  pool, `baked`/`in-flight` sets); the currently-loaded-region guard lives in `PlayerTracker`.
- [x] **`PlayerTracker`** — bakes regions within a prefetch radius ahead of each player
  (nearest-ring first, capped per scan), skipping regions already baked, in flight, or
  loaded — so arnis never writes a `.mca` the server holds open.
- [x] **`ArnisCommand`** — `/arnis status` and `/arnis prewarm [radius]` implemented.
  _(`reload`, `goto` pending)_
- [ ] **`/arnis goto <lat> <lng>` (real-world navigation)** — teleport a player to the
  in-game location of a real-world coordinate. Forward-projects `(lat, lng)` through the
  world's fixed Web Mercator origin (the same `WebMercatorProjection` the bake uses, so
  the mapping is exact and consistent) to get Minecraft `(x, z)`, ensures that region is
  baked (triggering an on-demand bake and awaiting it if needed), derives a safe surface
  `y` from the terrain, then teleports. Accepts `lat,lng` or `lat lng`; reports the
  resulting coordinates. A natural companion to a future reverse lookup (in-game position
  → real-world lat/lng) for sharing/among players.
- [x] **Plugin scaffolding** — `plugin.yml`, `config.yml` (origin lat/lon, scale, bake
  margin, ground level, arnis binary path, spawn, world), world bootstrap via
  `WorldCreator(...).generator(voidGen)`. _(persistent per-world `arnis.json` + worker
  count: later)_
- [ ] **World anchoring & spawn config** — let the server owner configure, per world:
  a **starting lat/lng that maps to Minecraft `(0,0)`** (the fixed Web Mercator origin
  the bake and all coordinate math share), and a **spawn point** given as either a
  real-world lat/lng (forward-projected to MC `x,z`, with `y` from terrain) or explicit
  MC coordinates. Persist both in `arnis.json` so every bake and `/arnis goto` stay
  consistent, and set the world's actual spawn on first load. Changing the origin after
  regions exist invalidates them (warn / require a reset).
- [x] **Logging** — log to the server console on plugin enable/load; log the
  start and completion of each chunk/region bake; log any generation errors
  (subprocess failures, malformed JSON, projection/config errors, etc.) so a server
  owner can diagnose issues from the console alone.
- [ ] **README for server owners** — document how to install and run the plugin:
  setting the server to use this world generator, required environment
  variables/API keys (e.g. self-hosted Overpass/elevation endpoints), and other
  gotchas or vanilla server settings that need changing (view-distance, world
  height/datapacks, etc.). _(a basic build/install README exists in `arnis-paper/`;
  the full server-owner guide is still pending)_
- [x] **End-to-end smoke test** (`e2e/smoke_test.ps1`) — boots a real headless Paper
  server with the plugin + real `arnis`, asserts the void world is created and the
  spawn region bakes (`r.0.0.mca`), then stops cleanly. Re-runnable; caches downloads.
- [ ] **Test mode with mock arnis** — a way to run the plugin against a mock/stub
  `arnis` binary (or in-process fake) that returns canned bake results, so chunk
  generation, streaming, and command logic can be tested without invoking the real
  arnis engine or making any OSM/network calls.

## 3. Phased delivery

- [x] **Phase 0 — Rust bake mode.** `--bake-region` + fixed origin; bakes `r.0.0.mca`
  into a world folder; verified via unit tests + a real end-to-end bake.
- [x] **Phase 1 — Minimal plugin.** Paper void generator + `/arnis prewarm` invoking
  the subprocess; verified by the e2e smoke test against a live server.
- [x] **Phase 2 — Automatic streaming.** `PlayerTracker` prefetch + `BakeService` worker
  pool + safe live region loading (bake ahead of the server so files load cleanly on
  approach). _(arnis-side OSM/land-cover caching still pending — see §1 Global data caching)_
- [ ] **Phase 3 — Polish.** Seam/margin tuning, region batching, self-hosted data,
  lighting/height docs, logging, README, test mode.

## 4. Future goals (beyond the initial refactor)

- [ ] **Vanilla terrain for survival (optional).** Generate vanilla-style terrain
  underground and in large bodies of water, so the generated world is viable for
  survival gameplay rather than just exploration/building. Arnis already has a
  similar setting today, but it is limited (e.g. shallow/partial coverage) — this
  would extend it to a fuller, more usable implementation in the on-demand model.

## Risks (see worldgen.md for mitigations)

- Writing `.mca` files under a live server.
- Cross-region seams on long linear features.
- Network latency / rate limits on Overpass & elevation data.
- Redundant global preprocessing per region.
- Vertical range mismatch between baked chunks and the server world.
