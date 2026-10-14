# Larry kernel for the Galaxy Z Flip4 (SM-F721B, b4q)

- Source: Samsung Open Source `SM-F721B_16_Opensource` (Kernel.tar.gz, BUILD_TARGET b4q_eur_openx), Linux 5.10.236, GPL-2.0.
- Toolchain: AOSP clang-r416183b (same as stock).
- Config: `gki_defconfig` from that source, plus `CONFIG_PID_NS=y`, ThinLTO instead of full LTO (build memory only), localversion `-android12-9-larry`. Everything else stock, so the vendor modules keep loading.
- Packed with AnyKernel3 as a kernel-only zip (`split_boot; flash_boot`): only the kernel in `boot` changes; the ramdisk with Magisk stays. `vendor_boot` and `dtbo` aren't touched.

`build.sh` is the build; `anykernel.sh` goes into a stock AnyKernel3 checkout with `Image` next to it.

## Android 15 (One UI 7)

- Source: Samsung Open Source `SM-F721B_15_Opensource.zip` (F721BXXUAHYD9) with the `F721BXXSAHYGB` and `F721BXXSAHYI1` update zips laid over it in that order, Linux 5.10.226. Same compiler (clang-r416183b) and config changes; built by `build15.sh`, packed with `anykernel-android15.sh` (supported.versions=15).
- The app only flashes it on firmware F721BXXSAHYI1.
