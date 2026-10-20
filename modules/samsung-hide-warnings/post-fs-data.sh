#!/system/bin/sh
# Early boot: spoof bootloader / warranty props before Zygote starts.
# Use resetprop -n so we do not talk to property_service (deadlock risk).

MODDIR=${0%/*}
# shellcheck source=/dev/null
. "$MODDIR/common.sh"

trim_log
log "post-fs-data start"

RESETPROP=$(find_resetprop) || {
  log "resetprop not found; skipping props"
  exit 0
}
log "using resetprop at $RESETPROP"

apply_hide_props -n
log "post-fs-data done"
