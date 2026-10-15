# Larry Launcher (Gentoo chroot)

An Android launcher that runs Linux as a **real chroot of Gentoo's
official arm64 stage3**, started as root through Magisk.


## Home screen

The home is niri-like, for a phone:

- Each **workspace** is a horizontally scrolling strip of **columns**. A column
  is an app you pinned, or (on workspace 1) an open Linux window.
- The **focused column is centered**. Swipe left/right to scroll it; tap a
  column to focus it, tap the focused one to open it, long-press to unpin it
  (or close the window).
- **Workspaces are stacked vertically**: swipe up/down. There's always one
  empty workspace after the last one; pin an app there from the app list
  (long-press › add to home) to start a new workspace.
- The bar at the bottom searches apps (Enter opens the best match);
  `⚙` opens settings.
- Hardware keyboard: Super+H/L or ←/→ columns, Super+J/K or ↑/↓ workspaces,
  Super+1..9 jump to a workspace, Super+D app list, Super+Enter terminal.
- One theme: Tokyo Night.

## Gentoo desktop (real niri, optional)

Settings › gentoo desktop installs `gui-wm/niri` and `gui-apps/fuzzel` and
runs real niri as a nested Wayland client of the launcher's compositor, shown
as one fullscreen window. It is not part of the default install. Both come
from the GURU overlay (niri is keyworded `~amd64` only, so it's accepted with
`**`) and neither is on the arm64 binhost, so niri **compiles from source**
(Rust ≥ 1.87 via `dev-lang/rust-bin`, plus clang for bindgen): expect hours
and several GB. Nested, niri's Mod key is Alt.

## Galaxy Z Flip4 extras (SM-F721B, rooted)

On a rooted Samsung Galaxy Z Flip4 (`b4q`), settings › kernel can flash a custom Larry kernel built from Samsung's official Android 15 source (firmware `F721BXXSAHYI1`), with PID namespaces for the Gentoo chroot:

- `larry` kernel: stock plus PID namespaces, keeps Magisk.
- `larry-ksun` kernel: adds KernelSU-Next, with a one-tap switch from Magisk and a "back to Magisk" button.

Every flash backs up the boot partition to `/sdcard/LarryLauncher/backup/` first and is refused on any other model, Android version or firmware. Copy the backup to a PC: a bootloop is recovered with Odin or Brokkr. The KernelSU-Next kernel turns off Samsung's kernel protections (RKP, KDP, DEFEX and others), so Samsung Pay, Secure Folder and some banking apps may stop working. Both kernels are confirmed booting on a Flip4.

Settings also has a De-Google screen (any rooted phone): Google apps grouped by risk and disabled reversibly with `pm disable-user`, never uninstalled, with a restore-all button.

Kernel build recipes are in `kernel/b4q/`.

### What the 0.9.3 kernel additions are for

These only add things the Gentoo chroot can do; day-to-day phone behaviour is unchanged.

- **ISO9660** (with Joliet/zisofs): `mount -o loop` CD/DVD images such as Linux ISOs and copy files out of them.
- **UDF**: DVD/Blu-ray images and some large USB drives.
- **SquashFS** (zlib, LZ4, LZO, XZ, zstd): the compressed read-only images inside live ISOs, AppImages and some snapshots; also lets you pack a big folder into one small mountable file.
- **xt_addrtype**: a firewall match that container tools like Docker and LXC use for their networking.

## Building

```sh
./gradlew :app:assembleDebug
# optional: skip the stage3 download (used as-is: no DIGESTS check for a pushed file)
adb push gentoo-stage3-arm64.tar.xz /sdcard/Android/data/dev.legendarymsr.larrylauncher/files/
```

Tests: `./gradlew :owl:test :app:testDebugUnitTest`.

In debug builds you can launch guest commands with
`adb shell am start -n dev.legendarymsr.larrylauncher/.MainActivity --es exec foot`.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the compositor, rendering, GPU
  acceleration, and how updates reach existing installs
- [docs/RELEASING.md](docs/RELEASING.md): Play releases and store assets

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose, and the JNI bridge in `src/main/cpp`)
- `owl/`: a small declarative UI language (legacy; no longer used on the home screen)
- `art/`: SVG masters for the app and store icons
- `store/`: Play listing sources (screenshot frames, privacy policy)
- `fastlane/`: the Play listing and the upload lanes
- `scripts/`: rendering icons and screenshots

## License

MIT, see [LICENSE](LICENSE), for Larry Launcher's own code and artwork. The
symbol font and the theme colors are not
covered; [THIRD_PARTY.md](THIRD_PARTY.md) lists them with their sources and
licenses. The app lists every third-party component it ships under
settings › licenses.

Larry Launcher is an independent project and is not affiliated with or endorsed
by Gentoo or the niri project.
