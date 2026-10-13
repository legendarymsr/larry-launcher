# Changelog

The most important changes in each release. The Play Store notes for a release
are in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

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
