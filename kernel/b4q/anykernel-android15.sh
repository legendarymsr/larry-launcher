### AnyKernel3 Ramdisk Mod Script
## Larry kernel for the Galaxy Z Flip4 (b4q)

properties() { '
kernel.string=Larry kernel 5.10.226 for Galaxy Z Flip4 (SM-F721B, b4q), Android 15 F721BXXSAHYI1
do.devicecheck=1
do.modules=0
do.systemless=0
do.cleanup=1
do.cleanuponabort=0
device.name1=b4q
device.name2=b4qxxx
device.name3=
supported.versions=15
supported.patchlevels=
supported.vendorpatchlevels=
'; } # end properties

BLOCK=boot;
IS_SLOT_DEVICE=0;
RAMDISK_COMPRESSION=auto;
PATCH_VBMETA_FLAG=auto;

. tools/ak3-core.sh;

# Kernel only: the ramdisk (and Magisk in it) is left exactly as it is.
split_boot;
flash_boot;
