### AnyKernel3 Ramdisk Mod Script
## Larry kernel + KernelSU-Next for the Galaxy Z Flip4 (b4q), Android 15 F721BXXSAHYI1

properties() { '
kernel.string=Larry kernel 5.10.226 + KernelSU-Next v3.4.2 for Galaxy Z Flip4 (SM-F721B, b4q), Android 15 F721BXXSAHYI1
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

split_boot;

# KernelSU-Next and Magisk must not both be in the boot image: put back the stock
# ramdisk Magisk saved in .backup (what Magisk's own uninstaller does).
cd $SPLITIMG;
[ -f ramdisk.cpio ] || abort "No ramdisk in boot. Aborting...";
magiskboot cpio ramdisk.cpio test; st=$?;
case $((st & 3)) in
  0) ui_print "Ramdisk is already stock.";;
  1)
    ui_print "Removing Magisk from the ramdisk (restoring Magisk's stock backup)...";
    magiskboot cpio ramdisk.cpio restore || abort "Magisk ramdisk restore failed. Nothing was flashed.";
    magiskboot cpio ramdisk.cpio test; st=$?;
    [ $((st & 3)) = 0 ] || abort "Ramdisk still isn't stock after restore. Nothing was flashed.";
    magiskboot cpio ramdisk.cpio "exists init" || abort "Restored ramdisk has no init. Nothing was flashed.";
    ui_print "Ramdisk is stock again.";;
  *) abort "Ramdisk patched by something other than Magisk. Nothing was flashed.";;
esac;
cd $AKHOME;

flash_boot;
