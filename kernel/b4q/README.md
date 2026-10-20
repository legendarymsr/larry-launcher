# Larry kernel for the Galaxy Z Flip4 (SM-F721B, b4q)

- Source: Samsung Open Source `SM-F721B_16_Opensource` (Kernel.tar.gz, BUILD_TARGET b4q_eur_openx), Linux 5.10.236, GPL-2.0.
- Toolchain: AOSP clang-r416183b (same as stock).
- Config: `gki_defconfig` from that source, plus `CONFIG_PID_NS=y`, ThinLTO instead of full LTO (build memory only), localversion `-android12-9-larry`. Everything else stock, so the vendor modules keep loading.
- Packed with AnyKernel3 as a kernel-only zip (`split_boot; flash_boot`): only the kernel in `boot` changes; the ramdisk with Magisk stays. `vendor_boot` and `dtbo` aren't touched.

`build.sh` is the build; `anykernel.sh` goes into a stock AnyKernel3 checkout with `Image` next to it.

## Android 15 (One UI 7)

- Source: Samsung Open Source `SM-F721B_15_Opensource.zip` (F721BXXUAHYD9) with the `F721BXXSAHYGB` and `F721BXXSAHYI1` update zips laid over it in that order, Linux 5.10.226. Same compiler (clang-r416183b) and config changes (including `kvm-arm.mode=nvhe` in `CONFIG_CMDLINE`); built by `build15.sh`, packed with `anykernel-android15.sh` (supported.versions=15).
- The app only flashes it on firmware F721BXXSAHYI1.

## Android 15 + KernelSU-Next

- KVM: `CONFIG_KVM=y` (stock) with `CONFIG_CMDLINE` using `kvm-arm.mode=nvhe` instead of Samsung's `protected`, so `/dev/kvm` is usable by QEMU host guests. DTB bootargs are unchanged; the mode comes from the baked `CONFIG_CMDLINE` (`CONFIG_CMDLINE_EXTEND=y`). There is no separate `CONFIG_ARM64_PROTECTED_KVM` on this 5.10 GKI tree.
- Same source as above, plus KernelSU-Next v3.4.2 (tag 4c706b5) built in (`CONFIG_KSU=y`, kprobe hooks, no SUSFS), version `5.10.226-android12-9-larry-ksun`. Built by `build15ksu.sh`.
- Samsung's UH, RKP (+CFP), KDP (+CRED, NS), DEFEX, PROCA and FIVE are off, the same set other Samsung KernelSU builds turn off; v3.4.2 has no Samsung RKP/KDP compatibility code.
- `anykernel-android15-ksun.sh` swaps the kernel and, if the ramdisk is Magisk-patched, restores the stock ramdisk from Magisk's `.backup` (`magiskboot cpio restore`, what Magisk's uninstaller does). It aborts before writing if the result isn't stock or has no `init`.

## Extras (Android 15 kernels)

`extras.sh` adds, built in: ISO9660 (Joliet, zisofs), UDF, SquashFS (zlib, LZ4, LZO, XZ, zstd, xattr, file-direct, per-CPU decompressors), `NETFILTER_XT_MATCH_ADDRTYPE`, and USB/IP (`CONFIG_USBIP_CORE=y`, `CONFIG_USBIP_VHCI_HCD=y`) so the ios-tools qemu-t8030 restore can attach Inferno's usb-tcp device into the Debian chroot via `/sys/devices/platform/vhci_hcd.0` without a second QEMU. Comparing `vmlinux.symvers` against the previous builds: 0 of ~14,640 exported symbol CRCs changed and none were removed for the filesystem extras. Only `crc_itu_t` and `crc_itu_t_table` (from UDF) are new; USB/IP adds its own exported symbols.

Left out on purpose: `CGROUP_PIDS`, `CGROUP_DEVICE` and `CGROUP_PERF` each add an entry to `enum cgroup_subsys_id` (devices and perf_event sit before freezer/net_cls/net_prio/hugetlb in `cgroup_subsys.h`, shifting their ids) and grow `CGROUP_SUBSYS_COUNT`, which sizes the `subsys[]` / `e_cset_node[]` / `e_csets[]` arrays in `struct css_set` and `struct cgroup`. That's a layout change Samsung's prebuilt vendor modules weren't built against. `SYSVIPC` and `USER_NS` stay off for the same kind of reason. `SQUASHFS_4K_DEVBLK_SIZE` stays off; the default 1K block size works on loop devices.
