# GoLinux VM (android-go-liunx)

Run a full **aarch64 Linux virtual machine on your Android phone** — powered by
QEMU system emulation, UEFI (EDK2) boot, and a built-in SSH/serial terminal.

| | |
|---|---|
| Package | `com.ee555719.golinux` |
| ABI | `arm64-v8a` only |
| Min Android | 11 (API 30) |
| Engine | QEMU system emulation (Termux GPL-2.0 build, patched linker) |
| Firmware | EDK2 `AAVMF_CODE.fd` / `AAVMF_VARS.fd` |
| UI | Jetpack Compose, Material 3 |

## Features

- **VM manager** – create / start / stop VMs with configurable CPU, RAM and disk.
- **Disk & ISO manager** – create raw virtual disks, import bootable ISO images.
- **QEMU monitor** – QMP control channel (pause, reset, screenshot, …).
- **Terminal** – SSH into the running guest *or* attach to the QEMU serial
  console. Includes a VT100/ANSI terminal emulator (cursor, colors, SGR,
  alternate screen, scrolling regions) and quick keys (`Tab`, `Esc`, `^C`, `^D`).
- **Port forwarding** – forward guest ports to the phone (host ↔ guest).
- **Backup / restore** – pack a VM's disk + config into a `.tar.gz`.
- **Foreground service** – keeps the VM alive with a notification.
- **Settings** – theme, SSH credentials, default VM parameters, storage access.

## Architecture notes (why it is built this way)

Android ≥ 10 enforces **W^X**: an app can only `exec()` ELF files located in
`nativeLibraryDir` (the `jniLibs` inside the APK). Data files may only be
`dlopen()`ed, not executed.

Therefore the engine is split:

```
app/src/main/jniLibs/arm64-v8a/
  libqemu-system-aarch64.so   <- the actual qemu-system-aarch64 binary (exec'd)
  libqemu-img.so              <- qemu-img (exec'd)

app/src/main/assets/
  qemu-libs.tar.gz            <- all shared libraries (extracted to filesDir,
                                 exposed via LD_LIBRARY_PATH for dlopen)
  firmware/AAVMF_CODE.fd      <- UEFI code (Termux EDK2 build)
  firmware/AAVMF_VARS.fd      <- UEFI NVRAM template
```

The Termux-built binaries are linked against
`/data/data/com.termux/.../linker64`, which does not exist here, so
`scripts/prepare_assets.ps1` rewrites `PT_INTERP` to `/system/bin/linker64`
and puts every `usr/lib/*.so*` dependency onto `LD_LIBRARY_PATH`.

## Build

### Prerequisites (Windows)

- JDK 17+ (`C:\Program Files\Java\jdk-17` works; script auto-detects)
- Android SDK with `platforms;android-36`, `build-tools;36.0.0`
  (auto-installed by the script via `sdkmanager`)
- Internet access (Termux, Debian, Maven/Gradle, GitHub)
- `git`, GitHub CLI `gh` (authenticated) — used for push & release

### One-shot release

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_and_release.ps1 -Version 1.0.0
```

This runs the full pipeline:

1. `prepare_assets.ps1` – downloads the QEMU engine + deps from Termux and
   UEFI firmware from Debian, patches the ELF interpreter, packs
   `jniLibs/` + `assets/qemu-libs.tar.gz`.
2. Bootstraps Gradle 8.11.1, generates the wrapper, creates the release
   keystore (`keystores/golinux-release.jks`) if absent.
3. `gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
4. `git init/add/commit`, merges upstream `origin/main`, pushes `main` + tag.
5. `gh release create vX.Y.Z` with the APK attached.

Useful switches: `-SkipAssets`, `-SkipBuild`, `-SkipGit`, `-SkipRelease`.

### Build only (no git / GitHub)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_and_release.ps1 -Version 1.0.0 -SkipGit -SkipRelease
```

### Manual asset preparation

```powershell
powershell -ExecutionPolicy Bypass -File scripts\prepare_assets.ps1
# optional: custom prebuilt binaries / firmware
powershell ... prepare_assets.ps1 -QemuBinary https://example/qemu-system-aarch64 `
    -QemuImgBinary ... -FirmwareDeb https://.../qemu-efi-aarch64_..._all.deb
```

## Project layout

```
app/src/main/java/com/ee555719/golinux/
  App.kt                 Application (channel setup, crash log)
  MainActivity.kt        single-activity Compose host + VM notification
  data/                  models + DataStore settings
  qemu/                  QEMU process mgmt, command builder, QMP, disks, ISOs
  ssh/                   SSHJ client + serial-console client (telnet)
  terminal/              VT100/ANSI terminal emulator (pure Kotlin)
  backup/                VM backup/restore
  vm/                    foreground VM service
  ui/                    Compose screens (Home, Disk/ISO, Terminal, Ports,
                         Backup, Settings) + navigation
scripts/
  prepare_assets.ps1     fetch & patch QEMU engine + firmware
  build_and_release.ps1  full build → git push → GitHub release
```

## Usage

1. Install the APK, grant **All files access** (Settings screen has a shortcut).
2. Create a disk (raw/qcow2) and optionally import a bootable ISO.
3. Start the VM → open **Terminal** → `SSH 终端` (guest sshd) or
   `串口控制台` (kernel console / getty on ttyS0).
4. Manage port forwards in **Port Forward** to reach guest services.

## Troubleshooting

- *"exec failed: permission denied"* – the engine APK arm64 libs are missing;
  run `scripts\prepare_assets.ps1` and rebuild.
- VM hangs at black screen – firmware missing (`assets/firmware/`), or the ISO
  is x86-only (this is an **aarch64** machine; use arm64 images, e.g. Alpine/
  Debian arm64).
- Terminal garbled – the serial console outputs raw ANSI; use `SSH 终端`
  inside the guest for a proper shell.

## License

GPL-2.0 (QEMU is linked into this app; see `LICENSE` in the repository root).
