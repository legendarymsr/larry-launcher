# Larry kernel for the Galaxy Z Flip4 (SM-F721B, b4q)

- Source: Samsung Open Source `SM-F721B_16_Opensource` (Kernel.tar.gz, BUILD_TARGET b4q_eur_openx), Linux 5.10.236, GPL-2.0.
- Toolchain: AOSP clang-r416183b (same as stock).
- Config: `gki_defconfig` from that source, plus `CONFIG_PID_NS=y`, ThinLTO instead of full LTO (build memory only), localversion `-android12-9-larry`. Everything else stock, so the vendor modules keep loading.
- Packed with AnyKernel3 as a kernel-only zip (`split_boot; flash_boot`): only the kernel in `boot` changes; the ramdisk with Magisk stays. `vendor_boot` and `dtbo` aren't touched.

`build.sh` is the build; `anykernel.sh` goes into a stock AnyKernel3 checkout with `Image` next to it.

## Android 15 (One UI 7)

- Source: Samsung Open Source `SM-F721B_15_Opensource.zip` (F721BXXUAHYD9) with the `F721BXXSAHYGB` and `F721BXXSAHYI1` update zips laid over it in that order, Linux 5.10.226. Same compiler (clang-r416183b) and config changes; built by `build15.sh`, packed with `anykernel-android15.sh` (supported.versions=15).
- The app only flashes it on firmware F721BXXSAHYI1.

## Android 15 + KernelSU-Next

- Same source as above, plus KernelSU-Next v3.4.2 (tag 4c706b5) built in (`CONFIG_KSU=y`, kprobe hooks, no SUSFS), version `5.10.226-android12-9-larry-ksun`. Built by `build15ksu.sh`.
- Samsung's UH, RKP (+CFP), KDP (+CRED, NS), DEFEX, PROCA and FIVE are off, the same set other Samsung KernelSU builds turn off; v3.4.2 has no Samsung RKP/KDP compatibility code.
- `anykernel-android15-ksun.sh` swaps the kernel and, if the ramdisk is Magisk-patched, restores the stock ramdisk from Magisk's `.backup` (`magiskboot cpio restore`, what Magisk's uninstaller does). It aborts before writing if the result isn't stock or has no `init`.
