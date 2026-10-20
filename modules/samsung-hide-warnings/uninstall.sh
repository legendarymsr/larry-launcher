#!/system/bin/sh
# Re-enable packages this module disabled. Props revert on their own after
# the module is gone and the phone reboots.

MODDIR=${0%/*}
# shellcheck source=/dev/null
. "$MODDIR/common.sh"

trim_log
log "uninstall start"

LIST=$DISABLED_LIST
# Fall back to the known notice list if the file is missing (old install).
if [ ! -f "$LIST" ] || [ ! -s "$LIST" ]; then
  LIST=
fi

if [ -n "$LIST" ]; then
  while IFS= read -r pkg || [ -n "$pkg" ]; do
    [ -n "$pkg" ] || continue
    out=$(pm enable --user 0 "$pkg" 2>&1)
    log "pm enable $pkg: $out"
  done < "$LIST"
else
  for pkg in $NOTICE_PACKAGES; do
    [ -n "$pkg" ] || continue
    out=$(pm enable --user 0 "$pkg" 2>&1)
    log "pm enable $pkg: $out"
  done
fi

log "uninstall done"
