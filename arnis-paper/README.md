# arnis-paper (Phase 1)

A minimal [Paper](https://papermc.io/) plugin that turns the `arnis` region-bake
backend into an on-demand server terrain generator. It bootstraps a **void world**
and bakes `arnis` region files into it, so real-world terrain streams in as chunks
load. This is Phase 1 — manual/prewarm baking; automatic player-driven streaming is
Phase 2 (see [`../worldgen-docs/roadmap.md`](../worldgen-docs/roadmap.md)).

## Requirements

- A built `arnis` binary with `--bake-region` support (this repo, Phase 0).
- Paper **1.21.1** (matches the chunk data version arnis writes).
- JDK 21+ to build.

## Build

```
gradle build          # or: ./gradlew build after `gradle wrapper`
```

The plugin jar lands in `build/libs/arnis-paper-0.1.0.jar`.

## Install & configure

1. Drop the jar into your server's `plugins/` folder and start once to generate
   `plugins/ArnisGen/config.yml`.
2. Edit `config.yml`:
   - `arnis-binary`: absolute path to the `arnis` executable.
   - `origin.lat` / `origin.lng`: the real-world point that maps to Minecraft `(0,0)`.
   - `scale`, `bake-margin`, `ground-level`, `spawn`, `world` as needed.
3. Restart. On enable the plugin creates the `arnis` world with a void generator and
   (by default) bakes the spawn region so spawn has real terrain.

## Commands

- `/arnis status` — show config and how many regions are baked.
- `/arnis prewarm [radius]` — bake the regions within `radius` (in regions, default 1)
  around you (or world spawn), in the background.

## Known Phase 1 limitations

- Baked terrain appears reliably in chunks loaded **fresh**. Reloading a region the
  server already holds resident is best-effort; a **server restart** always loads
  baked regions cleanly. Safe live streaming is Phase 2.
- Baking is synchronous per region on a worker thread and fetches map data over the
  network, so `prewarm` over a large radius can take a while.
