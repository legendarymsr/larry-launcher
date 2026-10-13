# Changelog

The most important changes in each release. The Play Store notes for a release
are in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Unreleased

- The Gentoo desktop installs Dillo with xwayland-satellite (niri starts it
  and sets DISPLAY); Super+B / Alt+B opens Dillo. Existing desktops get both
  in the background on the next niri start.
- Each niri start clones legenddots into ~/legenddots once and runs its
  gentoo/chroot/dotfiles.sh, linking nvim, tmux, foot, fuzzel, fastfetch and
  Dillo configs.

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
