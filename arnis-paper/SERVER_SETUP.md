# Server owner's guide — arnis terrain generator

Stream real-world terrain (from OpenStreetMap + elevation + land cover) into a
[Paper](https://papermc.io/) server, generated on demand as players explore. This
guide takes you from a downloaded release to walking around your city in-game.

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
- The **arnis server bundle** for your platform (Step 1) — one zip containing the
  engine and the plugin. Nothing has to be compiled, and the engine runs headless, so
  the host needs no desktop libraries.
- **Internet access on the server host** — arnis fetches OpenStreetMap (via an
  Overpass proxy), elevation, and land cover while baking. No API keys are required.
- Disk space for the baked world plus data caches.

On **Linux**, the bundled engine is built against glibc 2.35, so it runs on Debian 12+,
Ubuntu 22.04+, RHEL 9+ and anything newer. Older distros, Alpine, or a non-x86_64 host
need a build from source — see [BUILDING.md](https://github.com/CarvellWakeman/arnis-gen/blob/main/BUILDING.md).

---

## Step 1 — download the bundle

Grab the zip for your server's platform from the
[latest release](https://github.com/CarvellWakeman/arnis-gen/releases/latest):

| Server host | File |
|---|---|
| Linux (x86_64) | `arnis-server-linux-x86_64.zip` |
| Windows (x86_64) | `arnis-server-windows-x86_64.zip` |

Unzip it anywhere. Inside:

| File | What it is |
|---|---|
| `arnis` / `arnis.exe` | the engine — generates the terrain |
| `arnis-paper-<version>.jar` | the Paper plugin |
| `SERVER_SETUP.md` | this guide |
| `config.example.yml` | a fully commented copy of the default config |

> Prefer to build it yourself, or need a platform the bundles don't cover?
> [BUILDING.md](https://github.com/CarvellWakeman/arnis-gen/blob/main/BUILDING.md) covers both artifacts on Windows and on Linux.

## Step 2 — set up the Paper server

1. Put `paper-1.21.1.jar` in an empty server folder (download from
   <https://papermc.io/downloads/paper>).
2. Create `eula.txt` containing `eula=true` (agrees to the Mojang EULA).
3. Copy `arnis-paper-<version>.jar` from the bundle into `plugins/`.
4. Copy the **engine** (`arnis` / `arnis.exe`) into the server folder itself, or into a
   `bin/` subfolder of it — either location is found automatically, so the default
   `arnis-binary: "arnis"` needs no editing. (Any other location works too; see
   [How `arnis-binary` is found](#how-arnis-binary-is-found).)
5. **On Linux/macOS, make the engine executable:** `chmod +x arnis`. Extracting a zip
   with a GUI tool, or copying the file over SFTP or through a hosting panel's file
   manager, usually drops the executable bit. If it's missing, the plugin says so at
   startup and prints the exact command to run.
6. Start the server once with the command below, then stop it (type `stop`). This
   generates `plugins/ArnisGen/config.yml`.

The server folder then looks like this:

```
server/
├── paper-1.21.1.jar
├── eula.txt
├── arnis                    (or bin/arnis, or arnis.exe on Windows)
└── plugins/
    ├── arnis-paper-1.0.0.jar
    └── ArnisGen/config.yml  (created by that first start)
```

Start the server from the server folder with:

```
java -Xms2G -Xmx2G -jar paper-1.21.1.jar --nogui
```

Adjust `-Xmx` (max heap) to taste — 2–4 GB is plenty for a small server. `--nogui`
runs it headless in the terminal; omit it to get Paper's small GUI window. On
Windows, use the JDK you installed (e.g. `"%JAVA_HOME%\bin\java.exe" -Xmx2G -jar
paper-1.21.1.jar --nogui`) if `java` isn't on your `PATH`.

## Step 3 — configure `plugins/ArnisGen/config.yml`

```yaml
world: arnis                 # the generated world's name
origin:
  lat: 51.515                # the real-world point that becomes Minecraft (0,0)
  lng: -0.115                # pick where you want your map anchored
scale: 1.0                   # blocks per meter (1.0 = 1:1)
bake-margin: 64              # cross-boundary context; leave as-is
ground-level: -62            # matches arnis; leave as-is
arnis-binary: "arnis"        # the engine from the bundle; see below for how it's found
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
  repair-unbaked: true       # re-bake regions the server generated before arnis got there
  max-repairs-per-scan: 2
  barrier: true              # hold players at the edge of baked terrain
  safe-teleport: true        # defer teleports until the arrival view is baked
  lead-seconds: 60           # seconds of travel kept baked ahead along the direction of travel
```

Set `origin` to wherever you want your world centered.

### How `arnis-binary` is found

An absolute path (e.g. `/srv/minecraft/arnis` or `'C:\minecraft\arnis.exe'`) is used
as given. Anything else is searched for, in this order:

1. the plugin folder (`plugins/ArnisGen/`) and its parents — which covers `plugins/`,
   the server directory, and up to five levels above it;
2. within each of those, `target/release/`, `target/debug/` and `bin/`;
3. `PATH`.

So the default `arnis-binary: "arnis"` needs no editing when the engine sits in the
server folder, in `bin/`, or next to the config in `plugins/ArnisGen/`. A server
folder inside a source checkout finds `target/release/arnis` by itself, too.

The `.exe` suffix is added on Windows and dropped elsewhere, so one config file serves
both platforms — handy when you develop on Windows and deploy to Linux. If a
configured path is missing (a moved install, or a config copied from another machine)
the same search runs on its file name as a fallback.

The startup log states what was resolved, and `/arnis status` shows it in game:

```
[ArnisGen] Enabling: origin 51.515,-0.115 -> MC (0,0), scale 1.0 blocks/m, arnis 'arnis' -> /srv/minecraft/bin/arnis
```

## Step 4 — start, and enter your world

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

## Optional: running under Pterodactyl (or Pelican)

Nothing in the plugin is panel-specific, but a panel-managed server runs inside a
Docker container, which changes three things: the server runs as a **non-root user**,
only **`/home/container`** is writable and persistent, and there is **no `apt` or
`sudo`** inside. So install the release bundle — do not try to build anything there.

**Compatibility.** The Paper eggs run on the Debian-based `yolks:java_*` images
(glibc 2.36+), so the released Linux bundle runs as-is. An Alpine-based image needs a
static musl build instead — see [BUILDING.md](https://github.com/CarvellWakeman/arnis-gen/blob/main/BUILDING.md).

### Installing

`/home/container` is both the server directory and the JVM's working directory, so
the layout from Step 2 applies unchanged, with the engine in `bin/`:

```
/home/container/
├── paper-1.21.1.jar
├── bin/arnis
└── plugins/
    ├── arnis-paper-1.0.0.jar
    └── ArnisGen/config.yml
```

Because `bin/` is one of the searched locations, the default `arnis-binary: "arnis"`
still needs no editing.

1. Upload `arnis-server-linux-x86_64.zip` with the panel's **file manager**, then use
   its **Unarchive** action and move the engine and the jar into place. (Unpacking
   server-side is also much faster than uploading the engine binary over SFTP — it is
   by far the largest file.) Delete the zip afterwards so it doesn't count against
   your disk quota.
2. **Make the engine executable.** The web file manager cannot change permissions, and
   an upload lands as `644`. In rough order of convenience:
   - your SFTP client's chmod, using the panel's SFTP credentials —
     `chmod +x bin/arnis` in the `sftp` CLI, or the permissions dialog in FileZilla;
   - from the node host, if you administer it:
     `docker exec -it <server-uuid> chmod +x /home/container/bin/arnis`;
   - unarchiving a **`.tar.gz`** instead of a zip, which reliably carries the mode
     through — build one with
     `tar -czf arnis.tar.gz bin/arnis` (see "Assembling a bundle yourself" in
     [BUILDING.md](https://github.com/CarvellWakeman/arnis-gen/blob/main/BUILDING.md)).
3. Start the server and check the console: `[ArnisGen] Enabling: ... arnis 'arnis' ->
   /home/container/bin/arnis`. If the bit is still missing, the plugin logs the exact
   `chmod` to run rather than failing on every region.

### Tuning for a container

The engine runs as a subprocess of the JVM, which means it shares the container's
resource limits. Three things follow:

- **Leave memory headroom.** The engine's memory counts against the *server's* limit
  because it lives in the same cgroup — and most Paper eggs start Java with
  `-XX:MaxRAMPercentage=95` or `-Xmx` set to nearly the whole allocation, leaving
  none. When the limit is hit the kernel OOM-kills the largest process, which is your
  server. Lower the startup command to roughly `-XX:MaxRAMPercentage=70` (or an
  explicit `-Xmx` a couple of GB below the allocation) and give the container enough
  memory that the remainder is a real budget.
- **Keep `workers` low.** Each concurrent bake uses a CPU core, and the panel's CPU
  limit throttles the whole container — Paper included — so an aggressive setting
  costs you TPS. Start at `workers: 2` and raise it only while watching the panel's
  resource graphs.
- **Watch the disk quota.** Baked regions are ~4–5 MB each and `prefetch-radius: 2`
  is 25 regions per player (~115 MB) before anyone explores far. Panels enforce the
  disk limit hard, and the baked world only grows.

Outbound HTTPS must be allowed for the engine to fetch map data; the stock images
include the CA certificates it reads from the OS trust store.

---

## Commands

All require `arnis.use`, which every player has by default — exploring by coordinate is
the point of an arnis world. `prewarm` and `rebake` additionally require `arnis.bake`,
which only operators hold: a region is minutes of CPU and ~4–5 MB on disk, and nothing
rate-limits how often a sender may ask for one. Both are ordinary permission nodes, so
a permissions plugin can hand `arnis.bake` to a trusted group, or negate `arnis.use` on
the default group to put everything back in staff hands.

A third permission, `arnis.bypass`, exempts a player from the terrain barrier; it is
granted to nobody by default, operators included, since flying past the frontier is
what creates the holes the barrier prevents.

| Command | Permission | Effect |
|---|---|---|
| `/arnis status` | `arnis.use` | Show config and bake-pool stats (baked / in-flight / ok / failed). |
| `/arnis goto <lat> <lng>` | `arnis.use` | Teleport to a real-world coordinate, baking everything the arrival view reaches first. Accepts `lat,lng` or `lat lng`. |
| `/arnis reload [radius]` | `arnis.use` | Refresh already-loaded chunks from disk (e.g. after a prewarm). |
| `/arnis prewarm [radius]` | `arnis.bake` | Bake the regions within `radius` around you now (default 1). |
| `/arnis rebake [radius]` | `arnis.bake` | Force-regenerate regions around you *even if already on disk* — repairs void holes and terrain baked with older settings. |

Note that `goto` also queues bakes for the destination view, so a player with only
`arnis.use` can still cause baking — bounded by where they can reach, not by a quota.
On a public server, size the bake pool for that.

---

## Gotchas & tuning

- **Check the binary the plugin picked.** The startup log and `/arnis status` print the
  resolved path; if it says `NOT FOUND`, baking is disabled and nothing else will work.
  Verify the engine runs on its own first: `./arnis --version`. On Linux, a binary that
  exists but isn't marked executable is reported with the `chmod +x` to run.
- **First visit to an area is slow** (network fetch + render). Adjacent areas are
  fast — arnis caches OSM/elevation/land-cover tiles, so once a ~2 km tile is fetched,
  every nearby region reuses it.
- **Console `goto` and negative longitudes:** from the *server console*, a space before
  a negative longitude can be misparsed — use the comma form `goto 51.515,-0.115`.
  In-game chat, both `goto 51.515 -0.115` and the comma form work.
- **Teleports wait for their destination.** `goto`, spawn, and — with
  `safe-teleport: true` — any other teleport bake the whole area your view will reach on
  arrival, not just the region you land in, because a player dropped next to an unbaked
  neighbour makes the server generate it as void. A `/tp` into fresh terrain therefore
  pauses ("Preparing terrain at your destination...") and moves you when it's ready,
  rather than landing you in a hole. Expect that pause to be 30–60 s for a cold area.
- **Players get held at the frontier when they outrun generation.** Flying is much
  faster than baking (a sprint-flying player crosses a region in ~23 s; a cold bake
  takes 30–60 s), so `barrier: true` stops them about a view distance short of unbaked
  terrain with a "Generating terrain ahead..." notice, and releases them when the bake
  lands. This is prevention rather than repair, and it matters: once the server
  generates a region itself it also caches that region file, so a later bake of the
  same region may not appear until a restart. Raise `lead-seconds` (and `workers`) if
  players hit the wall often; grant `arnis.bypass` to let someone through it, at the
  cost of leaving holes behind them. Regions that do slip through are re-baked
  automatically once the player moves on (`repair-unbaked`), tracked via the
  `<world>/arnis-baked/` index — but a restart may still be needed for the repaired
  terrain to show.
- **`/arnis rebake` is for holes the plugin can't know about.** Regions the server
  generated are now tracked (`<world>/arnis-baked/`) and re-baked on their own. But
  regions that pre-date that index — any world baked before this version, whose region
  files were adopted as "baked" on first start — are invisible to it, as is terrain baked
  before an `origin` / `scale` / vertical-datum change. `/arnis rebake [radius]`
  regenerates regardless. Restart afterwards if the terrain still looks stale (the server
  can cache a region-file handle).
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
- **A sharp void wall along a straight line, that never fills in.** That line is a region
  border, and the void side was generated by the server before arnis baked it — so its
  region file exists and every later bake skips it. `/arnis rebake 1` (standing near the
  seam) regenerates it; restart if it still looks void afterwards.
- **`Command 'arnis' is not defined`.** The plugin jar didn't load — check the server
  log for a load error, and that the jar from the bundle is in `plugins/` (Step 2).
- **`GLIBC_2.xx not found` when the engine runs.** The binary was built against a newer
  glibc than the host has. Use the released Linux bundle, or build on (or for) the older
  system — see the glibc notes in [BUILDING.md](https://github.com/CarvellWakeman/arnis-gen/blob/main/BUILDING.md).

---

## Current limitations (early build)

- **Repaired regions may need a restart to appear.** The server caches an open handle
  per region file, so a region it generated itself and then arnis re-baked can keep
  serving the old contents until a restart, even though the correct terrain is on disk.
- Regions bake independently, so very long features that cross region boundaries can
  show minor seams. Tuning this is planned.
- One region can only be baked before a player reaches it, not while they stand on it.
- Bakes are queued per region and are network-bound, so a fast-moving player (or several
  going different ways) can build a long queue. The barrier means this shows up as a
  wait at the frontier rather than as missing terrain. The queue is worked in priority
  order — whatever a player is blocked on first (`goto`, a deferred teleport, the
  barrier), then prefetch nearest-first, then repairs — and work for terrain nobody is
  heading for any more is dropped. Because priority only decides what runs *next*, and a
  bake already running cannot be interrupted, the pool also runs a few extra workers
  while somebody is blocked (never more than double `workers`, and only for as long as
  the wait lasts) so an urgent bake starts at once instead of waiting for a prefetch to
  finish. `/arnis status` breaks the queue down by priority and shows running workers and
  the stale-drop count; the console logs each `goto`'s region count and how long it took.
