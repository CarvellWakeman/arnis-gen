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
- [x] **Fixed vertical datum** — `scale_to_minecraft` normalized elevation to each
  bake's own min/max, faulting adjacent regions vertically. Bake mode now uses a shared
  `--elevation-base` + `--vertical-scale` (`Y = ground_level + (elev - base) * bpm`), so
  the same real elevation is the same Y everywhere. The vertical analog of the fixed
  origin; exposed as plugin `vertical-scale` / `elevation-base` config.
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
- [x] **`PlayerTracker`** — bakes regions ahead of each player, skipping regions already
  baked, in flight, or loaded (from an exact `getLoadedChunks()` set) so arnis never
  writes a `.mca` the server holds open. Prefetch is **predictive**: lookahead is derived
  from the distance covered between scans (`getVelocity` is client-controlled and
  unreliable for players) as `ceil(speed × lead-seconds / 512)` regions along the heading,
  plus the laterally adjacent ones for turns, then the nearest-ring fallback. A symmetric
  radius spent most of its budget behind and beside a travelling player.
- [x] **Bake provenance (`BakedIndex`, `UnbakedRegionWatcher`)** — a region file proves
  only that the *server* wrote one; it writes them for the void it generates when a player
  outruns streaming, which then looks baked forever. Each successful bake now drops a
  marker in `<world>/arnis-baked/` (written on the bake worker, so it survives a crash
  before the callback). Region files without a marker are re-baked automatically once
  nothing holds them loaded. Worlds with no index are adopted as baked, so an upgrade
  does not re-bake everything. Regions failing three bakes are given up on until a restart
  or an explicit `/arnis rebake`.
- [x] **Terrain barrier (`MovementBarrier`)** — prevention, since repair is not
  sufficient: once the server generates a region it also caches that region file, so a
  later external bake may not appear until a restart. Blocks a move whose destination
  *view footprint* reaches unbaked terrain (a view distance short of the edge, since the
  view loads terrain before the player arrives) and queues those regions immediately.
  Teleports are exempt, a player already in unbaked space is never blocked, and
  `arnis.bypass` (granted to nobody by default, operators included) opts out.
  _(gap: non-`goto` teleports can still land in unbaked terrain — see Phase 3.)_
- [x] **`ArnisCommand`** — `/arnis status`, `/arnis prewarm [radius]`, `/arnis goto
  <lat> <lng>`, and `/arnis reload [radius]` implemented.
- [x] **`/arnis goto <lat> <lng>` (real-world navigation)** — teleports a player to the
  in-game location of a real-world coordinate. Forward-projects `(lat, lng)` through a
  Java `Projection` that mirrors the Rust `WebMercatorProjection` exactly (verified:
  origin → MC (0,0), east → +x, north → -z, scale linear; cross-checked live in the e2e),
  ensures that region is baked (awaiting an in-flight bake via `BakeService`), derives a
  surface `y`, then teleports. Accepts `lat,lng` or `lat lng`; console reports the mapped
  coordinates instead of teleporting. _(future: reverse lookup in-game position → lat/lng)_
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
- [x] **README for server owners** — `arnis-paper/SERVER_SETUP.md`: step-by-step
  install/configure/play guide covering the arnis binary, origin and streaming config,
  entering the world via `/arnis goto`, data-source/caching notes, view-distance vs
  prefetch-radius tuning, an optional Pterodactyl/Pelican section, and troubleshooting.
- [x] **Release packaging** — the engine builds headless (`--no-default-features`, no
  GTK/WebKit) so it runs on a server host; `arnis-binary` resolves relative paths and
  bare names against the server directory, its parents and `PATH`; the
  `server-bundle.yml` workflow ships one zip per platform containing the engine, the
  plugin jar and the setup guide; build instructions live in `BUILDING.md`.
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
  approach). Hardened against players outrunning it: predictive lookahead, bake
  provenance + automatic repair, and the movement barrier. Playtested by flying as fast
  as possible and changing direction — the barrier engaged twice, released before the
  player could walk into it, and no void regions were produced.
- [ ] **Phase 3 — Polish.** In rough priority order:
  - [ ] **Teleport safety** — the last routine way to land in unbaked terrain. Make
    non-`goto` teleports (plain `/tp`, portals, other plugins' warps) bake their arrival
    view first, the way `/arnis goto` does, rather than being exempt from the barrier.
  - [ ] **Queue discipline** — bakes are queued unbounded and FIFO, so a fast or
    multi-directional party can build a long backlog whose head is no longer near
    anybody. Prioritise by distance to the nearest player and drop entries nobody is
    heading for.
  - [ ] **Throughput** — bake several regions per arnis invocation to amortise the
    Overpass/elevation fetch, the dominant cost of a cold region.
  - [ ] **Live region-file invalidation** — make a repaired region visible without a
    restart by dropping the server's cached `RegionFile` handle (NMS reflection; needs
    the same defensive treatment as the `GameRule` lookup).
  - [ ] Seam/margin tuning, self-hosted data, lighting/height docs, test mode.

## 4. Future goals (beyond the initial refactor)

- [ ] **Vanilla terrain for survival (optional).** Generate vanilla-style terrain
  underground and in large bodies of water, so the generated world is viable for
  survival gameplay rather than just exploration/building. Arnis already has a
  similar setting today, but it is limited (e.g. shallow/partial coverage) — this
  would extend it to a fuller, more usable implementation in the on-demand model.

## Risks (see worldgen.md for mitigations)

- Writing `.mca` files under a live server. Mitigated by only ever baking regions with
  no loaded chunks. The residue: the server caches an open handle per region file, so a
  region it has already touched keeps serving its own contents until a restart — which
  is why the barrier (prevent) matters more than the repair sweep (cure).
- Cross-region seams on long linear features.
- Network latency / rate limits on Overpass & elevation data.
- Redundant global preprocessing per region.
- Vertical range mismatch between baked chunks and the server world.
