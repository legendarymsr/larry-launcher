# Larry kernel for the Galaxy Z Flip4 (SM-F721B, b4q)

- Source: Samsung Open Source `SM-F721B_16_Opensource` (Kernel.tar.gz, BUILD_TARGET b4q_eur_openx), Linux 5.10.236, GPL-2.0.
- Toolchain: AOSP clang-r416183b (same as stock).
- Config: `gki_defconfig` from that source, plus `CONFIG_PID_NS=y`, ThinLTO instead of full LTO (build memory only), localversion `-android12-9-larry`. Everything else stock, so the vendor modules keep loading.
- Packed with AnyKernel3 as a kernel-only zip (`split_boot; flash_boot`): only the kernel in `boot` changes; the ramdisk with Magisk stays. `vendor_boot` and `dtbo` aren't touched.

`build.sh` is the build; `anykernel.sh` goes into a stock AnyKernel3 checkout with `Image` next to it.
