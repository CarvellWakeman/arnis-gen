# arnis-paper

A [Paper](https://papermc.io/) plugin that turns the `arnis` region-bake backend
into an on-demand server terrain generator. It bootstraps a **void world** and
streams `arnis`-baked region files into it ahead of players as they explore.

> **Setting up a server to play on? See [SERVER_SETUP.md](./SERVER_SETUP.md)** —
> a step-by-step install/config/play guide. The rest of this file is a developer
> quick-reference.

See [`../worldgen-docs/roadmap.md`](../worldgen-docs/roadmap.md) for design and status.

## Requirements

- An `arnis` binary with `--bake-region` support (this repo, Phase 0).
- Paper **1.21.1** (matches the chunk data version arnis writes).
- JDK 21+ and Gradle 8.5+ to build.

## Build

```
gradle build          # or: ./gradlew build after `gradle wrapper`
```

The plugin jar lands in `build/libs/arnis-paper-1.0.0.jar`. See
[`../BUILDING.md`](../BUILDING.md) for prerequisites on Windows and Linux, and for
building the engine. Prebuilt bundles of both are attached to each
[release](https://github.com/CarvellWakeman/arnis-gen/releases/latest).

## Install & configure

1. Drop the jar into your server's `plugins/` folder and start once to generate
   `plugins/ArnisGen/config.yml`.
2. Edit `config.yml`:
   - `arnis-binary`: path to the `arnis` executable. An absolute path always works;
     a relative one (or the bare name `arnis`) is looked for in the server directory
     and its parents — including their `bin/`, `target/release/` and `target/debug/`
     subfolders — and then on `PATH`, with the `.exe` suffix added or dropped to suit
     the host OS.
   - `origin.lat` / `origin.lng`: the real-world point that maps to Minecraft `(0,0)`.
   - `scale`, `bake-margin`, `ground-level`, `spawn`, `world` as needed.
3. Restart. On enable the plugin creates the `arnis` world with a void generator and
   (by default) bakes the spawn region so spawn has real terrain.

## Commands

Gated on `arnis.use`, which defaults to every player:

- `/arnis status` — show config and bake-pool stats.
- `/arnis goto <lat> <lng>` — teleport to the in-game location of a real-world
  coordinate (accepts `lat,lng` or `lat lng`), baking that region first if needed.
  From the console it reports the mapped Minecraft coordinates instead of teleporting.
- `/arnis reload [radius]` — reload baked region chunks around you from disk without a
  restart (e.g. after a `prewarm`).

Gated on `arnis.bake`, op-only by default, since each region costs real CPU and disk:

- `/arnis prewarm [radius]` — bake the regions within `radius` (in regions, default 1)
  around you (or world spawn), in the background.
- `/arnis rebake [radius]` — force-regenerate the regions around you even if they are
  already on disk; the escape hatch for terrain the plugin cannot know is stale.

## Streaming model

Three mechanisms keep players on baked terrain, in order of importance:

- **`MovementBarrier`** holds a player when the destination's view footprint reaches
  unbaked terrain, so the server never generates the region in the first place. It also
  defers teleports — cancel, bake the arrival view, re-issue — since a teleport arrives
  with no lead time and blocking one outright would strand the player. `arnis.bypass`
  opts out of both.
- **`PlayerTracker`** bakes ahead along the direction of travel — lookahead scales with
  measured speed over `streaming.lead-seconds` — plus a ring at `prefetch-radius`.
- **`BakedIndex`** records which regions arnis produced (`<world>/arnis-baked/`), so a
  region the *server* generated is not mistaken for a baked one and is re-baked once
  nothing holds it loaded.

## Known limitations

- Baked terrain appears reliably in chunks loaded **fresh**. A region the server already
  holds resident is best-effort — it caches an open region-file handle, so an external
  re-bake of it may not show until a **restart**.
- Baking is per region on a worker thread and fetches map data over the network, so a
  fast player or a wide `prewarm` can build a long queue. `BakeService` works it in
  priority order (whatever a player is blocked on, then prefetch nearest-first, then
  repairs), promotes a queued region when a more urgent caller asks for it, and drops
  queued work nobody is heading for.
