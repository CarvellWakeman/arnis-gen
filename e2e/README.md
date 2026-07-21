# e2e — server smoke test

An end-to-end test that boots a real, headless **Paper 1.21.1** server with the
[`arnis-paper`](../arnis-paper) plugin and the `arnis` region-bake backend, and
asserts the full loop works: the plugin creates the void world and bakes the
spawn region (`arnis/region/r.0.0.mca`) with no errors, then the server shuts
down cleanly.

It is **re-runnable** — nothing large is committed. On each run it:

1. Resolves the `arnis` binary (uses `target/release` or `target/debug`, or builds it).
2. Downloads the Paper server jar and the plugin's compile dependencies into a
   gitignored `.cache/` (skipped when already cached).
3. Builds the plugin jar with `javac` (no Gradle needed).
4. Sets up a throwaway server run dir, boots Paper headless, waits for the bake,
   asserts the region file exists, and stops the server.

## Run

```powershell
pwsh -File e2e/smoke_test.ps1
# or, from the e2e dir:
./smoke_test.ps1 -TimeoutSec 360 -Origin "51.515,-0.115"
```

Options:

- `-Origin "lat,lng"` — real-world point mapped to Minecraft `(0,0)` (default: central London).
- `-TimeoutSec <n>` — how long to wait for the spawn-region bake (default 360).
- `-ArnisBinary <path>` — use a specific arnis executable instead of auto-resolving.
- `-KeepRun` — keep the server run dir (`.cache/run`) for inspection.

Exit code `0` = pass, `1` = fail. On failure the run dir is kept and the last log
lines are printed.

### Primary-world variant

`primary_world_test.ps1` boots a server with `level-name=arnis` + a `bukkit.yml`
generator entry and asserts arnis comes up as the **primary** world with no
"Could not set generator" error. Run `smoke_test.ps1` once first (it downloads the
Paper jar and builds the plugin jar this test reuses), then:

```powershell
pwsh -File e2e/primary_world_test.ps1
```

## Requirements

- **JDK 21+** on `JAVA_HOME` (used to build the plugin and run the server).
- A **Rust toolchain** (only if no `arnis` binary exists yet).
- **Internet access** — for the Paper jar, Maven deps, and the OpenStreetMap /
  elevation data `arnis` fetches while baking.

## Notes

- The server agrees to the Mojang EULA (`eula=true`) in the throwaway run dir; the
  test exists to run the server, so this is implied.
- The main world uses a flat generator to keep startup cheap; only the separate
  `arnis` world exercises the generator under test.
- This is currently PowerShell-only (matching the dev environment). A cross-platform
  runner can be added later for CI.
