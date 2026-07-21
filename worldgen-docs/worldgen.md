# Plan: Refactor arnis into an on-demand server terrain generator

## Context

`arnis-gen` is a mature Rust tool (v3.0.0, ~140 source files) that converts OpenStreetMap
data into a **complete, pre-built Minecraft world** written to disk (Java Anvil, Bedrock,
Luanti), which the user then copies into a server. That offline full-world model imposes the
limits this refactor removes:

- **Size** — the whole area is fetched, held in RAM, and written up front.
- **Duration** — nothing is playable until the entire generation finishes.
- **Merging** — stitching adjacent/overlapping generations into one coherent world is manual and error-prone.
- **Transfer overhead** — generate locally, then copy a large world into the server.

**Goal:** make arnis behave as a *terrain generator* — terrain is produced **on demand, region
by region, ahead of players as they explore**, directly inside a running server, with no
practical world-size cap and no up-front full-world build/transfer.

**Key enabling facts discovered during exploration:**
- arnis's renderer is *already* region-aligned: it tiles the world into 512-block,
  region-boundary-aligned tiles (`DEFAULT_TILE_SIZE = 512`, `TILE_EDITOR_HALO = 64`, `src/tile.rs`),
  renders them in parallel, and already has a **stream-to-disk** mode that flushes
  `r.X.Z.mca` region files incrementally (`data_processing.rs`, `world_editor/java.rs`).
- The Java writer targets `world_dir/region/r.X.Z.mca` and bakes each region as a **complete**
  512×512 unit (`write_region_to_disk`, `java.rs:227`), so a single invocation yields drop-in region files.
- `WebMercatorProjection` (`src/projection/web_mercator.rs`) is deterministic and reversible
  (`forward` + `inverse`), so any Minecraft region's block bounds map back to a fixed lat/lon
  bbox — the primitive for seamless, independent region bakes.

**Decisions locked in:**
- **Integration model:** Rust engine *bakes region files on demand*; a thin Java server plugin orchestrates. (Reuse the Rust engine; no rewrite, no live per-chunk IPC.)
- **Trigger:** automatic, ahead of players (lazy/streaming).
- **Platform:** **Paper** (justified below).

## Why Paper (not Fabric)

In the region-baking model the Java side does **orchestration only** — track players, prefetch
region coordinates, run the arnis subprocess, manage chunk load/unload, expose admin commands.
Paper's plugin API is by far the simplest for exactly that (async chunk APIs, schedulers,
world/chunk management, `plugin.yml` commands), and it matches the researched recommendation.
Fabric would add custom-dimension + Codec/registry boilerplate for no benefit here. Fabric would
only win if we needed a genuine custom *dimension type* or beyond-vanilla vertical range (Cubic
Chunks) — neither is required. **Target Paper**, keeping the Rust↔Java contract a plain
CLI/file boundary so a Fabric front-end could be added later without touching the engine.

## Architecture

```
Player moves ─► Paper plugin (PlayerTracker)
                   │  computes region coords within a prefetch radius
                   ▼
              Bake queue (dedupe + in-flight guard)
                   │  region (rx,rz) not yet on disk
                   ▼
        arnis  --bake-region rx,rz  --world-dir <server world>
               --origin <lat,lon>  --scale <bpm>  --ground-level <y> ...
                   │  inverse-project region block bounds → lat/lon bbox
                   │  fetch OSM+elevation+land cover (cached) → render → write r.rx.rz.mca
                   ▼
        Server loads the now-present region file (status=full);
        the plugin's VoidChunkGenerator fills only not-yet-baked gaps with air.
```

The arnis world uses a **void `ChunkGenerator`** so any region not yet baked is cheap empty air,
never vanilla terrain. Because chunk storage is consulted before the generator, a baked region
file loads verbatim. Bakes always run **ahead** of the player, so we never write a region the
server currently has memory-mapped.

## Coordinate model (must be fixed & global)

A single infinite world requires one fixed mapping shared by every bake:
`origin_lat`, `origin_lon`, `scale` (blocks/m), `projection = web_mercator`, `ground_level`.
Stored once in a world-level config file (e.g. `<world>/arnis.json`). For a target region
`(rx,rz)`, block bounds `[rx*512 .. rx*512+511] × [rz*512 .. rz*512+511]` are `inverse`-projected
to a lat/lon bbox. Determinism across independent invocations is guaranteed as long as
origin+scale are constant — this is what makes adjacent bakes line up seamlessly.

## Rust-side changes (arnis engine)

1. **New "bake region" mode** (`src/bake.rs` + `--bake-region rx,rz` / `--bake-region-range` in `src/args.rs`, dispatched from `src/main.rs`).
   - Input: world dir, region coord(s), fixed origin lat/lon, scale, ground config.
   - Derive the lat/lon bbox from region block bounds via `WebMercatorProjection::inverse`, expanded by a **bake margin** (larger than the 64-block tile halo) so long linear features (roads/rivers/bridges) crossing the region edge are preprocessed with context; commit only the central target region.
   - Reuse `data_processing::generate_world_with_options`, writing **only the target region file(s)** into the existing `world_dir/region/`, and **skip all per-world one-time artifacts**: `level.dat`, spawn, map item, branding maps, decoration maps (guard these in `main.rs`/`data_processing.rs`).
2. **Explicit projection origin** — add an origin override so `web_mercator` no longer derives its origin from the per-run bbox center (`main.rs:366-377`, `CoordTransformer::with_projection`). Thread a fixed `(origin_lat, origin_lon)` through.
3. **Machine-readable result** — emit JSON (regions written, timings, errors) to stdout for the plugin to consume.
4. **Global data caching** — key fetched OSM (`retrieve_data.rs`) and land cover (`land_cover/mod.rs`) tiles to a shared on-disk cache like elevation already is (`elevation_data::cleanup_old_cached_tiles`), so adjacent region bakes reuse data and cut Overpass/S3 load. Optionally **batch several adjacent regions per invocation** to amortize global preprocessing (flood fill, highway connectivity, bridges) and network.
5. **Height/lighting config** — ensure baked Y range fits the server world (matched `--ground-level`, or tall-world datapack); bake per-chunk lighting (`--bake-lighting`) so streamed-in regions render before being visited. Verify chunk NBT `Status = minecraft:full` in `create_chunk_nbt` so the server accepts baked chunks without regenerating.

## Java side (new Paper plugin, separate module e.g. `arnis-paper/`)

- `plugin.yml`, `config.yml` (origin lat/lon, scale, prefetch radius, arnis binary path, worker count, world name).
- `VoidChunkGenerator` — empty air generator for the arnis world.
- `RegionBaker` — bounded worker pool; runs `arnis --bake-region …` as a subprocess; parses JSON result; concurrency + in-flight guard; never bakes a currently-loaded region.
- `PlayerTracker` — on move/interval, compute needed region set within the prefetch radius, enqueue misses.
- `ArnisCommand` — `/arnis status | prewarm <radius> | reload`.
- World bootstrap via `WorldCreator(...).generator(voidGen)` (or `getDefaultWorldGenerator`), loading `arnis.json`.

## Risks & mitigations

- **Writing `.mca` under a live server** → only bake ahead of players (unloaded regions) + in-flight lock; never touch a loaded region.
- **Cross-region seams** on long features → generous bake margin + shared deterministic projection; accept minor seams in v1, refine margin/caching later.
- **Network latency / Overpass & elevation rate limits** → on-disk global cache, batch adjacent regions, and support the existing self-hosted endpoints (`api.arnismc.com`).
- **Redundant global preprocessing per region** → region batching + caching.
- **Vertical range mismatch** → matched `--ground-level` or tall-world datapack, documented in world setup.

## Phased delivery

- **Phase 0 — Rust bake mode (prove the core).** `--bake-region` + fixed origin; bake `r.0.0.mca` into an empty Java world folder; open in vanilla MC 1.21.1 and confirm it matches an equivalent full-run over the same bbox.
- **Phase 1 — Minimal plugin.** Paper void generator + `/arnis prewarm` invoking the subprocess; manual, synchronous.
- **Phase 2 — Automatic streaming.** PlayerTracker prefetch + worker pool + data caching.
- **Phase 3 — Polish.** Seam/margin tuning, region batching, self-hosted data, lighting/height docs.

## Verification

- **Rust unit test:** region→bbox `inverse` determinism (same region ⇒ identical bbox across calls; adjacent regions share an exact edge).
- **Rust integration:** bake `r.0.0` into a fresh world dir; byte/-content compare the region against a full-run's `r.0.0.mca` (arnis already has a `ARNIS_BLOCK_HASH` region hash — reuse it). Bake an adjacent region; verify edge continuity of a road/river spanning the boundary.
- **End-to-end:** run a Paper server with the plugin, walk in a straight line, observe regions baking ahead (`/arnis status`), confirm seamless terrain and no server stalls.

## Critical files

- **Rust (modify):** `src/args.rs` (bake mode + origin args), `src/main.rs` (dispatch, skip per-world artifacts), `src/data_processing.rs` (reuse generation entry, region-target output), `src/world_editor/java.rs` (single-region targeting; confirm `Status=full`), `src/projection/web_mercator.rs` (fixed origin — already supports `inverse`), `src/coordinate_system/transformation.rs` (`CoordTransformer::with_projection`), `src/retrieve_data.rs` + `src/land_cover/mod.rs` + `src/elevation/` (region-scoped fetch + shared caching).
- **Rust (new):** `src/bake.rs` — region-bake orchestration (or a dedicated `[[bin]]`).
- **Java (new):** `arnis-paper/` — `plugin.yml`, `ArnisPlugin`, `VoidChunkGenerator`, `RegionBaker`, `PlayerTracker`, `ArnisCommand`, `config.yml`.
