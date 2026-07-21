# arnis-paper (Phase 1)

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

- `/arnis status` — show config and bake-pool stats.
- `/arnis prewarm [radius]` — bake the regions within `radius` (in regions, default 1)
  around you (or world spawn), in the background.
- `/arnis goto <lat> <lng>` — teleport to the in-game location of a real-world
  coordinate (accepts `lat,lng` or `lat lng`), baking that region first if needed.
  From the console it reports the mapped Minecraft coordinates instead of teleporting.
- `/arnis reload [radius]` — reload baked region chunks around you from disk without a
  restart (e.g. after a `prewarm`).

## Known Phase 1 limitations

- Baked terrain appears reliably in chunks loaded **fresh**. Reloading a region the
  server already holds resident is best-effort; a **server restart** always loads
  baked regions cleanly. Safe live streaming is Phase 2.
- Baking is synchronous per region on a worker thread and fetches map data over the
  network, so `prewarm` over a large radius can take a while.
