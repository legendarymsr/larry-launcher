# Changelog

The most important changes in each release. The Play Store notes for a release
are in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## 1.0.7 (versionCode 23) – 2026-10-19

- Settings › kernel offers the Android 15 KernelSU-Next kernel with KVM in
  nVHE host mode (kvm-arm.mode=nvhe instead of Samsung's protected pKVM), so
  QEMU can use /dev/kvm for arm64 guests. It's the same kernel otherwise,
  still SM-F721B on F721BXXSAHYI1 only, from the kernel-kvm-nvhe-v1.0.0
  release (sha256-checked).
- The screen shows the running kvm-arm.mode and whether /dev/kvm exists. On
  KernelSU-Next with protected KVM there's an "install the KVM kernel (nVHE)"
  action; it backs up boot first and keeps the saved Magisk boot as the way
  back. Switching from Magisk to KernelSU-Next now flashes the KVM kernel.

## 0.9.3 (versionCode 21) – 2026-10-15

- Both Android 15 Larry kernels (plain and KernelSU-Next) now have ISO9660
  (Joliet, zisofs), UDF and SquashFS (zlib, LZ4, LZO, XZ, zstd, xattrs,
  direct file reads) built in, plus the netfilter addrtype match for
  container networking. The exported symbol CRCs are unchanged from 0.9.2
  (only crc_itu_t is new), so Samsung's vendor modules see the same ABI.
- On KernelSU-Next, settings › kernel can update the KernelSU-Next kernel in
  place; the saved Magisk boot stays the way back.

## 0.9.2 (versionCode 20) – 2026-10-14

- Settings › kernel can switch from Magisk to KernelSU-Next (Android 15,
  F721BXXSAHYI1). It backs up the Magisk boot as the way back, switches off
  Magisk modules (keeping a list), installs the KernelSU Next v3.4.2
  manager (sha256-checked) while Magisk root still works, then flashes the
  Larry kernel with KernelSU-Next built in. That zip puts the stock ramdisk
  back from Magisk's own .backup and refuses to write if the result isn't
  stock.
- "back to Magisk" writes the saved Magisk boot back and turns the modules
  on again. Flashing the plain Larry kernel is refused while KernelSU-Next
  runs, so the phone can't end up without root.
## 0.9.1 (versionCode 19) – 2026-10-14

- New settings › de-google screen. Google apps are listed by risk (safe,
  careful, core) and turned off for user 0 with `pm disable-user`; nothing
  is uninstalled or changed in /system, and restore all turns them back on.
  Default apps (keyboard, SMS, dialer, browser, home, WebView provider) are
  skipped, mainline/setup-wizard/WebView packages and One UI Home are never
  touched, and nothing is turned off until Larry is the home app.
- Optional Larry De-Google module for Magisk or KernelSU(-Next): re-applies
  the list after boot, turns everything back on after two unfinished boots
  or when the module is removed, and re-enables One UI Home if Larry is gone.
- New settings › larry wm row: shows whether Larry is the home app and sets
  it as default.

## 0.8.1 (versionCode 17) – 2026-10-14

- The Larry kernel now comes per Android version, picked from the phone's
  Android version: Android 15 gets a new build from Samsung's SM-F721B
  Android 15 source with the F721BXXSAHYI1 updates (5.10.226), and it only
  flashes on firmware F721BXXSAHYI1. Android 16 keeps the 0.8.0 build.
  Other versions are refused.

## 0.8.0 (versionCode 16) – 2026-10-14

- New settings › kernel screen for the Galaxy Z Flip4 (SM-F721B, b4q) on
  Android 16. It shows the running kernel and can back up the boot
  partition to /sdcard/LarryLauncher/backup, flash the Larry kernel, restore
  any backup and reboot, all through Magisk root.
- The Larry kernel is Samsung's SM-F721B Android 16 kernel source
  (5.10.236) with stock config plus PID namespaces, packed as a kernel-only
  AnyKernel3 zip: the ramdisk, and Magisk in it, are left alone.
- Flashing refuses unless the phone is a b4q SM-F721* on Android 16 with a
  5.10 kernel, checks the downloaded zip's sha256, always backs up boot first
  (checked against the partition by size and sha256) and asks for
  confirmation with a warning.

## 0.7.3 (versionCode 15) – 2026-10-13

- The desktop extras (Dillo, Xwayland, xwayland-satellite, legenddots) now
  install as a tracked background job instead of silently at niri start.
  Settings › linux shows the package being installed (n of m), a progress
  bar and the live log, then done, or the error with a retry button; the
  session notification shows the progress too.
- It starts on its own whenever niri is installed and an extra is missing
  (when Linux comes up, after installing the desktop, and when opening it),
  runs one at a time under the Portage lock, and waits for an emerge an
  older version left running.

## 0.7.2 (versionCode 14) – 2026-10-13

- X11 apps launched into the niri session no longer get DISPLAY=:0 with no
  X server behind it: if nothing live serves :0, the launcher starts
  xwayland-satellite on niri's socket (detached), waits for
  /tmp/.X11-unix/X0, and only then sets DISPLAY. A stale X0 socket no longer
  counts as a running X server.
- When that fails, the panel says why: xwayland-satellite not installed,
  still building in the background, Xwayland missing, or the tail of its log
  (/tmp/.larrylauncher-xws.log, also used by niri's startup hook).

## 0.7.1 (versionCode 13) – 2026-10-13

- X11 apps (Dillo) launched from the app list failed with "Can't open
  display": they ran on the launcher's own compositor with no DISPLAY. While
  niri runs, launches now join its session (niri's Wayland socket and
  DISPLAY=:0); niri's managed config starts xwayland-satellite on :0 when
  niri doesn't already, and exports DISPLAY to everything it spawns.
- Launching an X11 app without the desktop running now says to start the
  Gentoo desktop first.
- FLTK's UI designer (fluid) is hidden from the app list.

## 0.7.0 (versionCode 12) – 2026-10-13

- The Gentoo desktop installs Dillo with xwayland-satellite (niri starts it
  and sets DISPLAY); Super+B / Alt+B opens Dillo. Existing desktops get both
  in the background on the next niri start.
- Each niri start clones legenddots into ~/legenddots once and runs its
  gentoo/chroot/dotfiles.sh, linking nvim, tmux, foot, fuzzel, fastfetch and
  Dillo configs.
- Fixed a startup ordering bug in the desktop package list that broke the
  build after the Dillo change.

## 0.6.0 (versionCode 11) – 2026-10-12

First release of larry-launcher.

- Optional Linux is the official Gentoo arm64 stage3, run as a real chroot
  through Magisk root in a private mount namespace. The guest is real root,
  and setup says so. Without root, Linux stays off.
- Portage manages packages, with Gentoo's official arm64 binhost first, and
  the default package set is small (foot and what the bundled widgets need).
- Widgets declare their packages as Portage atoms in `requires { portage: [...] }`.
- Leaving the launcher kills every guest process, and a root watchdog
  cleans up if the app is killed.
- Bundled themes and example widgets; no online theme or widget store.
