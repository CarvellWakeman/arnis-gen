# Server owner's guide — arnis terrain generator

Stream real-world terrain (from OpenStreetMap + elevation + land cover) into a
[Paper](https://papermc.io/) server, generated on demand as players explore. This
guide takes you from a fresh checkout to walking around your city in-game.

---

## What it is

The plugin creates a dedicated **void world** and fills it with terrain by calling
the `arnis` engine to bake one 512×512 region at a time. Regions are baked **ahead
of players** as they explore, so the world streams in with no up-front full-world
build. A fixed real-world **origin** you choose maps to Minecraft `(0,0)`, and every
region shares that frame so the world is one seamless map.

---

## Requirements

- **Paper 1.21.1** — matches the chunk format arnis writes. Newer Paper may work but
  is untested; older will not load the chunks.
- **Java 21+** to run Paper (JDK 25 runs it fine, with a couple of harmless startup
  warnings).
- The **`arnis` binary** built from this repository.
- **Internet access on the server host** — arnis fetches OpenStreetMap (via an
  Overpass proxy), elevation, and land cover while baking. No API keys are required.
- Disk space for the baked world plus data caches.

---

## Step 1 — build the arnis engine

From the repository root:

```
cargo build --release
```

This produces the binary at `target/release/arnis.exe` (Windows) or
`target/release/arnis`. **Note its absolute path** — you'll point the plugin at it.

> A `target/debug/arnis` build works too, but release is much faster per region.

## Step 2 — build the plugin jar

With Gradle:

```
cd arnis-paper
gradle build      # or: gradle wrapper && ./gradlew build
```

The jar lands at `arnis-paper/build/libs/arnis-paper-0.1.0.jar`.

**No Gradle installed?** Run the end-to-end test once from the repo root:

```
pwsh -File e2e/smoke_test.ps1
```

It compiles the plugin as a side effect to
`e2e/.cache/plugin-build/arnis-paper.jar` — copy that jar.

## Step 3 — set up the Paper server

1. Put `paper-1.21.1.jar` in an empty server folder (download from
   <https://papermc.io/downloads/paper>).
2. Create `eula.txt` containing `eula=true` (agrees to the Mojang EULA).
3. Copy the plugin jar into `plugins/`.
4. Start the server once, then stop it (type `stop`). This generates
   `plugins/ArnisGen/config.yml`.

Start the server from the server folder with:

```
java -Xms2G -Xmx2G -jar paper-1.21.1.jar --nogui
```

Adjust `-Xmx` (max heap) to taste — 2–4 GB is plenty for a small server. `--nogui`
runs it headless in the terminal; omit it to get Paper's small GUI window. On
Windows, use the JDK you installed (e.g. `"%JAVA_HOME%\bin\java.exe" -Xmx2G -jar
paper-1.21.1.jar --nogui`) if `java` isn't on your `PATH`.

## Step 4 — configure `plugins/ArnisGen/config.yml`

```yaml
world: arnis                 # the generated world's name
origin:
  lat: 51.515                # the real-world point that becomes Minecraft (0,0)
  lng: -0.115                # pick where you want your map anchored
scale: 1.0                   # blocks per meter (1.0 = 1:1)
bake-margin: 64              # cross-boundary context; leave as-is
ground-level: -62            # matches arnis; leave as-is
arnis-binary: "arnis"        # <-- SET THIS to the ABSOLUTE path from Step 1
bake-spawn-on-enable: true   # bake the spawn area on startup
spawn:                       # keep near a region CENTRE (multiple of 512, +~256)
  x: 256                     # so only one region is pre-baked at first start
  z: 256
vertical-scale: 1.0          # blocks per metre of elevation (shared by all regions)
elevation-base: 0.0          # real-world metres that map to ground-level (0 = sea level)
streaming:
  enabled: true
  prefetch-radius: 2         # regions baked ahead of each player (1 region = 512 blocks)
  workers: 2                 # concurrent bakes (each uses a CPU core + network)
  interval-ticks: 40         # how often player positions are scanned (20 = 1s)
  max-per-scan: 8
```

The one setting you **must** change is `arnis-binary` — use the **absolute** path,
e.g. `arnis-binary: 'C:\Users\you\arnis-gen\target\release\arnis.exe'`. A relative
path resolves against the server's working directory and usually won't be found.

Set `origin` to wherever you want your world centered.

## Step 5 — start, and enter your world

1. Start the server. On the **first** start the plugin pre-bakes the spawn region
   *before* the world loads, so **startup pauses ~30–60 s** (network fetch + render)
   while you see:
   ```
   [ArnisGen] Pre-baking spawn region into 'arnis' before world load (first start; this can take ~30-60s)...
   [ArnisGen] Spawn region pre-baked.
   ...
   [ArnisGen] Arnis world 'arnis' ready.
   [ArnisGen] Spawn set to 8,-3,8
   [ArnisGen] Streaming enabled: prefetch radius 2 region(s), 2 worker(s)...
   ```
   Pre-baking means spawn comes up as real terrain (not void) with no restart. Later
   starts skip it (the region is already on disk) and boot normally.
2. Join the server. You spawn in the normal `world` — the generated terrain lives in
   the separate `arnis` world.
3. **Enter the generated world with `/arnis goto`:**
   ```
   /arnis goto 51.515 -0.115
   ```
   This teleports you into the `arnis` world at that real-world coordinate, baking
   the region first if needed. Try your origin, or any real place within a sensible
   distance of it.
4. **Walk around.** Regions bake ahead of you automatically as you explore.

---

## Optional: make `arnis` the world players spawn into

By default the generated world is **separate** from your normal overworld, and you
enter it with `/arnis goto`. To have players spawn **directly** in the generated
world instead, make it the server's primary world:

1. In `server.properties`, set `level-name=arnis` — it must match `world:` in the
   plugin config.
2. In `bukkit.yml`, register the plugin's generator for it:
   ```yaml
   worlds:
     arnis:
       generator: ArnisGen
   ```
3. Restart. The plugin loads early enough (`load: STARTUP`) to provide the void
   generator, so the main world comes up as arnis terrain — you'll see
   `Preparing level "arnis"` followed by `[ArnisGen] Arnis world 'arnis' ready.`
   and no "Could not set generator" error.

**Spawn:** the plugin pre-bakes the spawn region before the world loads (first start
only), so spawn comes up as real terrain with no restart. This pauses the first
startup ~30–60 s — watch for `[ArnisGen] Spawn region pre-baked.`

---

## Commands

All require the `arnis.admin` permission (op by default).

| Command | Effect |
|---|---|
| `/arnis status` | Show config and bake-pool stats (baked / in-flight / ok / failed). |
| `/arnis goto <lat> <lng>` | Teleport to a real-world coordinate (bakes it first). Accepts `lat,lng` or `lat lng`. |
| `/arnis prewarm [radius]` | Bake the regions within `radius` around you now (default 1). |
| `/arnis reload [radius]` | Refresh already-loaded chunks from disk (e.g. after a prewarm). |

---

## Gotchas & tuning

- **`arnis-binary` must be an absolute path.** This is the most common setup mistake.
  Verify the binary runs on its own first: `arnis --help`.
- **First visit to an area is slow** (network fetch + render). Adjacent areas are
  fast — arnis caches OSM/elevation/land-cover tiles, so once a ~2 km tile is fetched,
  every nearby region reuses it.
- **Console `goto` and negative longitudes:** from the *server console*, a space before
  a negative longitude can be misparsed — use the comma form `goto 51.515,-0.115`.
  In-game chat, both `goto 51.515 -0.115` and the comma form work.
- **Enter new areas with `/arnis goto`.** Streaming bakes *ahead* of players; the region
  you're standing in is baked by `goto`/spawn before you arrive. If you jump into a
  totally fresh area another way, it may be void until a bake catches up.
- **Keep `prefetch-radius` ahead of view distance.** With server `view-distance=N`
  chunks, set `prefetch-radius` ≥ `ceil(N × 16 / 512) + 1` so regions are baked before
  the server tries to load them. A modest `view-distance` (4–8) plus `prefetch-radius: 2`
  is a good starting point.
- **`prewarm` of the region you're standing in is best-effort.** Terrain you *stream
  into* by exploring loads cleanly, and the spawn region is handled automatically. But
  a `prewarm`/`goto` bake of a region the server *already* has resident around you may
  not refresh until those chunks reload (walk away and back, or reconnect).
- **World height:** arnis targets the vanilla range with `ground-level -62`. Leave the
  defaults unless you have a reason to change them.
- **Vertical mapping (regions lining up):** every region shares one datum so the same
  real elevation is the same Y everywhere — that's why adjacent regions don't fault
  vertically. `elevation-base` is the metres that map to `ground-level` (0 = sea level;
  good for low/coastal areas) and `vertical-scale` is blocks per metre. If your area is
  **high-altitude** (e.g. a mountain city), sea-level base buries terrain under deep
  stone — set `elevation-base` near that area's base elevation. Raising `vertical-scale`
  exaggerates relief; lowering it flattens. Changing either invalidates already-baked
  regions (re-bake by deleting the world), like changing the origin.
- **Be considerate of data sources.** Heavy, wide-ranging exploration makes many
  Overpass/elevation requests. The caches reduce this, but don't point a public server
  at unlimited exploration without thinking about load.
- **Caches** live in your OS cache directory: `arnis-osm-cache`, `arnis-tile-cache`
  (elevation), `arnis-landcover-cache`. Safe to delete to reclaim space; they refill on
  demand. Tune the OSM tile size with `ARNIS_OSM_TILE_DEG` and freshness with
  `ARNIS_OSM_CACHE_TTL_DAYS` (environment variables).
- **Backups:** the baked world is ordinary `.mca` region files in the `arnis/` world
  folder — back it up like any world.

---

## Troubleshooting

- **`Spawn region bake failed` in the console.** The `arnis` subprocess returned an
  error — the console prints it. Check `arnis-binary` is correct and runnable, and that
  the host has internet access.
- **World is void where you stand.** The region wasn't baked (bake failed, or you
  entered a fresh area faster than streaming). Try `/arnis goto` to that spot, or check
  `/arnis status` for failures.
- **`Command 'arnis' is not defined`.** The plugin jar didn't load — check the server
  log for a load error and that the jar is a proper build (Steps 2).

---

## Current limitations (early build)

- Live player-driven streaming and the in-game `goto` teleport are validated by design
  and by a headless server test, but a hands-on playtest is the real proof — that's what
  this guide is for.
- Regions bake independently, so very long features that cross region boundaries can
  show minor seams. Tuning this is planned.
- One region can only be baked before a player reaches it, not while they stand on it.
