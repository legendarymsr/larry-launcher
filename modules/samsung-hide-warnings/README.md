# Hide Samsung Warnings

KernelSU / Magisk module for the Galaxy Z Flip4 (`SM-F721B`, One UI 7 /
Android 15). Hides the **in-Android** Samsung notices about unofficial
software, an unlocked bootloader and Knox tamper.

## What it hides

- Settings / Device care lines that read unlocked or unofficial because of
  boot props (`verifiedbootstate`, `warranty_bit`, and friends).
- The recurring **"Unauthorized actions have been detected"** notification
  from `com.samsung.android.securitylogagent`.

## What it cannot do

- The **boot-screen** unlocked-bootloader warning (drawn by Samsung's signed
  bootloader before Android starts). That needs a blank `up_param` image and
  is not touched here.
- The **Knox fuse**. Once tripped it stays tripped. Samsung Pay, Secure
  Folder and other Knox-backed features stay broken.
- Software-update prompts (`com.sec.android.soagent`). Those are left alone
  on purpose.

## Install

1. Install from the KernelSU-Next (or Magisk) app: pick the
   `samsung-hide-warnings-v1.0.0.zip` release asset.
2. Reboot.
3. Check `/data/local/tmp/samsung-hide-warnings.log` if a notice is still
   there.

## Uninstall

Remove the module in KernelSU-Next / Magisk and reboot. `uninstall.sh`
re-enables any packages this module disabled; the spoofed props drop away
with the module.

## Props set

| Prop | Value |
| --- | --- |
| `ro.boot.verifiedbootstate` | `green` |
| `ro.boot.flash.locked` | `1` |
| `ro.boot.vbmeta.device_state` | `locked` |
| `ro.boot.warranty_bit` | `0` |
| `ro.warranty_bit` | `0` |
| `ro.boot.veritymode` | `enforcing` |
| `ro.secureboot.lockstate` | `locked` |
| `ro.boot.fmp_config` | `1` |
| `sys.oem_unlock_allowed` | `0` |
| `ro.vendor.boot.warranty_bit` | `0` |
| `ro.vendor.warranty_bit` | `0` |

`ro.config.knox` is **not** changed: spoofing it has broken Knox-dependent
services on some One UI builds, and it cannot un-trip the fuse anyway.

## Packages disabled

Only when present, with `pm disable-user --user 0` (never uninstalled):

- `com.samsung.android.securitylogagent`

## Target

Galaxy Z Flip4 SM-F721B, One UI 7 / Android 15, KernelSU-Next, unlocked
bootloader, Knox already tripped. Untested on other Samsung models; the
props and the one package are the same across recent One UI, so it should
be harmless elsewhere, but that has not been verified.
