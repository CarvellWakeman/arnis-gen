# Building from source

Two artifacts come out of this repository:

| Artifact | What it is | Built with |
|---|---|---|
| `arnis` / `arnis.exe` | the engine — generates Minecraft regions from real-world data | Rust / Cargo |
| `arnis-paper-<version>.jar` | the Paper plugin that streams baked regions into a live server | JDK 21 / Gradle |

**You do not need to build either one to run a server.** Prebuilt bundles containing
both are published on the
[Releases page](https://github.com/CarvellWakeman/arnis-gen/releases/latest) —
see [`arnis-paper/SERVER_SETUP.md`](./arnis-paper/SERVER_SETUP.md). Build from source
when you want unreleased changes, a platform the bundles don't cover (Alpine, older
distros, ARM), or you're developing.

---

## The engine

### Prerequisites

**Rust (stable)** on every platform — install from <https://rustup.rs>.

Plus a **C toolchain**, because a few dependencies compile bundled C sources
(SQLite, mimalloc, zstd, ring):

<details open>
<summary><b>Linux</b></summary>

| Distro | Command |
|---|---|
| Debian / Ubuntu | `sudo apt install build-essential` |
| Fedora / RHEL / Rocky | `sudo dnf group install "Development Tools"` (or just `gcc make`) |
| Arch | `sudo pacman -S base-devel` |
| Alpine | `sudo apk add build-base` |
| openSUSE | `sudo zypper install -t pattern devel_basis` |

Already have one? `cc --version && make --version` — if both answer, skip the install.

There is **no OpenSSL dependency** (TLS is rustls, in-tree) and, for the headless
build below, no GTK/WebKit dependency either.

</details>

<details>
<summary><b>Windows</b></summary>

Install the **Visual Studio Build Tools** with the "Desktop development with C++"
workload (rustup prompts for this on first run, or get it from
<https://visualstudio.microsoft.com/visual-cpp-build-tools/>). That supplies the MSVC
compiler and linker the C dependencies need. No other system libraries are required.

</details>

<details>
<summary><b>macOS</b></summary>

`xcode-select --install` for the Command Line Tools.

</details>

### Headless build (what a server runs)

```
cargo build --release --no-default-features
```

`--no-default-features` drops the `gui` feature — Tauri and its WebKitGTK stack — so
the build needs no desktop libraries at all. This is the right build for any server,
and the only one that will build on a headless Linux box without extra packages.

Output: `target/release/arnis` (`arnis.exe` on Windows). Verify it:

```
./target/release/arnis --version
./target/release/arnis --help | grep -- --bake-region
```

A `target/debug` build (plain `cargo build`) also works and is much quicker to
produce, but it bakes regions several times slower — use release for anything real.

### Desktop build (the GUI app)

```
cargo build --release
```

On Linux this additionally needs the Tauri system libraries:

```
sudo apt install libgtk-3-dev pkg-config libglib2.0-dev libsoup-3.0-dev libwebkit2gtk-4.1-dev
```

Windows and macOS need nothing beyond the toolchains above.

### Cross-compiling

Cargo builds for the **host** platform; `--release` selects an optimization profile,
not a target. Building a Linux binary on a Windows machine therefore needs more than
`--target x86_64-unknown-linux-gnu`: the bundled C dependencies above require a full
cross C toolchain and a Linux sysroot, which is painful to assemble on Windows. In
rough order of least trouble:

1. **Let CI do it** — the [Server Bundle](.github/workflows/server-bundle.yml)
   workflow builds Linux and Windows bundles on every push to `main` and can be run
   on demand from the Actions tab. Download the artifact.
2. **WSL2** — `wsl --install`, then install Rust and `build-essential` inside it and
   build natively.
3. **[`cross`](https://github.com/cross-rs/cross)** — does the whole thing in a
   container: `cross build --release --no-default-features --target x86_64-unknown-linux-gnu`.
   Needs Docker.
4. **[`cargo-zigbuild`](https://github.com/rust-cross/cargo-zigbuild)** — uses `zig cc`
   as the cross compiler, no Docker, but the fiddliest when it misbehaves.

### glibc compatibility, and static builds

A Linux binary links against the glibc of the machine that built it, and glibc is
backward compatible but **not forward** compatible: something built on Ubuntu 24.04
(glibc 2.39) fails on Debian 12 (glibc 2.36) with `GLIBC_2.38 not found`. Build on
the oldest system you intend to support — the released bundle uses Ubuntu 22.04
(glibc 2.35), which covers Debian 12+, Ubuntu 22.04+ and RHEL 9+.

For a binary that does not care at all — old distros, Alpine, minimal containers —
build a static one against musl:

```
rustup target add x86_64-unknown-linux-musl
sudo apt install musl-tools
cargo build --release --no-default-features --target x86_64-unknown-linux-musl
```

Output: `target/x86_64-unknown-linux-musl/release/arnis`, with no runtime libc
dependency. Note that TLS roots are read from the OS trust store, so a minimal image
still needs its `ca-certificates` package for arnis to fetch map data.

---

## The Paper plugin

### Prerequisites

- **JDK 21+** (Temurin, or any distribution). Paper 1.21.x runs on Java 21, and the
  build targets that bytecode level regardless of which JDK runs Gradle.
- **Gradle 8.5+** — <https://gradle.org/install/>, or your package manager.

### Build

```
cd arnis-paper
gradle build
```

The jar lands at `arnis-paper/build/libs/arnis-paper-1.0.0.jar`. Paper's API is
resolved from `repo.papermc.io` at build time, so the first build needs network
access.

No Gradle installed and you'd rather not install it:

```
gradle wrapper && ./gradlew build
```

...still needs Gradle once. Alternatively, run the end-to-end test from the repo root
(`pwsh -File e2e/smoke_test.ps1`); it compiles the plugin with `javac` as a side
effect and leaves a usable jar at `e2e/.cache/plugin-build/arnis-paper.jar`. The e2e
scripts are PowerShell, so on Linux they need `pwsh` installed — Gradle is the simpler
route there.

---

## Assembling a bundle yourself

The release bundles are just a directory of the two artifacts plus docs. To make the
equivalent from a local build:

```
mkdir arnis-server
cp target/release/arnis arnis-server/                       # arnis.exe on Windows
cp arnis-paper/build/libs/arnis-paper-*.jar arnis-server/
cp arnis-paper/SERVER_SETUP.md arnis-server/
cp arnis-paper/src/main/resources/config.yml arnis-server/config.example.yml
```

Package it with `tar -czf` or `zip -r` (both preserve the executable bit on Linux;
copying a bare binary over SFTP or through a web file manager does not — `chmod +x`
it afterwards). Then follow `SERVER_SETUP.md` from Step 2.

---

## Continuous integration

| Workflow | What it guards |
|---|---|
| [`ci-build.yml`](.github/workflows/ci-build.yml) | fmt, clippy, full build + unit tests, and a `build-headless` job that builds `--no-default-features` with the GUI packages deliberately absent — so a desktop-only dependency creeping into the CLI path fails here rather than on someone's server |
| [`server-bundle.yml`](.github/workflows/server-bundle.yml) | builds the engine and the plugin jar and packages the per-platform server bundles |
| [`release.yml`](.github/workflows/release.yml) | the desktop app artifacts (Windows exe, Linux AppImage, macOS universal) |
