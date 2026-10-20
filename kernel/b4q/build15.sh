#!/bin/bash
set -e
K=/workspace/kernel
export PATH=$K/bin:$K/clang/bin:$PATH
export KBUILD_BUILD_USER=legendarymsr KBUILD_BUILD_HOST=larry
. $K/extras.sh
cd $K/a15/kernel_platform/common
chmod -R u+w . || true
rm -f localversion*; printf -- '-android12-9-larry' > localversion
MK="make -j4 O=out ARCH=arm64 LLVM=1 LLVM_IAS=1 CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_COMPAT=arm-linux-gnueabi-"
[ -f out/.config ] || {
 $MK gki_defconfig
 ./scripts/config --file out/.config -e PID_NS -d LTO_CLANG_FULL -e LTO_CLANG_THIN -d LOCALVERSION_AUTO -d KSU
 $MK olddefconfig
}
./scripts/config --file out/.config --set-str CMDLINE "stack_depot_disable=on kasan.stacktrace=off kvm-arm.mode=nvhe cgroup_disable=pressure cgroup.memory=nokmem"
./scripts/config --file out/.config $EXTRAS
$MK olddefconfig
grep -E "CONFIG_(PID_NS|LTO_CLANG|CFI_CLANG|SECURITY_DEFEX|LOCALVERSION_AUTO|MODVERSIONS|KVM|CMDLINE)[= ]" out/.config
grep -E "^CONFIG_(ISO9660_FS|JOLIET|ZISOFS|UDF_FS|SQUASHFS[A-Z0-9_]*|NETFILTER_XT_MATCH_ADDRTYPE|USBIP_CORE|USBIP_VHCI_HCD|USB)=" out/.config
$MK Image
ls -la out/arch/arm64/boot/
strings out/arch/arm64/boot/Image | grep -m1 "Linux version"
echo BUILD_DONE
