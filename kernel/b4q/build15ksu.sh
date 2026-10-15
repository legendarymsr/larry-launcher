#!/bin/bash
set -e
K=/workspace/kernel
export PATH=$K/bin:$K/clang/bin:$PATH
export KBUILD_BUILD_USER=legendarymsr KBUILD_BUILD_HOST=larry
. $K/extras.sh
cd $K/a15/kernel_platform/common
# localversion file is shared with the plain build; pass the KSU one on the command line
MK="make -j4 O=out-ksu ARCH=arm64 LLVM=1 LLVM_IAS=1 CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_COMPAT=arm-linux-gnueabi- LOCALVERSION=-ksun"
[ -f out-ksu/.config ] || {
 $MK gki_defconfig
 ./scripts/config --file out-ksu/.config -e PID_NS -d LTO_CLANG_FULL -e LTO_CLANG_THIN -d LOCALVERSION_AUTO \
   -e KSU -d KSU_DEBUG \
   -d UH -d UH_RKP -d UH_LKMAUTH -d UH_LKM_BLOCK -d RKP -d RKP_CFP -d RKP_CFP_JOPP -d RKP_CFP_ROPP \
   -d KDP -d KDP_CRED -d KDP_NS -d SECURITY_DEFEX -d PROCA -d FIVE
 $MK olddefconfig
}
./scripts/config --file out-ksu/.config $EXTRAS
$MK olddefconfig
grep -E "^CONFIG_(KSU|PID_NS|KPROBES|UH|RKP|KDP|KDP_CRED|SECURITY_DEFEX|PROCA|FIVE|LTO_CLANG_THIN)=" out-ksu/.config || true
grep -cE "^CONFIG_(UH|RKP|KDP|SECURITY_DEFEX|PROCA|FIVE)=y" out-ksu/.config || true
grep -E "^CONFIG_(ISO9660_FS|JOLIET|ZISOFS|UDF_FS|SQUASHFS[A-Z0-9_]*|NETFILTER_XT_MATCH_ADDRTYPE)=" out-ksu/.config
$MK Image
strings out-ksu/arch/arm64/boot/Image | grep -m1 "Linux version"
echo BUILD_DONE
