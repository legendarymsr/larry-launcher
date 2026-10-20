#!/system/bin/sh
# Late start: re-apply props after boot (some get overwritten), then disable
# the Samsung packages that post the unofficial / Knox notices.

MODDIR=${0%/*}
# shellcheck source=/dev/null
. "$MODDIR/common.sh"

trim_log
log "service start"

RESETPROP=$(find_resetprop) || {
  log "resetprop not found; skipping props"
  RESETPROP=
}

# Wait for Android to finish booting (up to ~5 minutes).
i=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 60 ]; do
  sleep 5
  i=$((i + 1))
done
log "boot_completed=$(getprop sys.boot_completed) after ${i}x5s"

if [ -n "$RESETPROP" ]; then
  apply_hide_props
fi

# Fresh list of what we disable this boot (uninstall.sh re-enables from it).
: > "$DISABLED_LIST"

for pkg in $NOTICE_PACKAGES; do
  [ -n "$pkg" ] || continue
  if ! pm path "$pkg" >/dev/null 2>&1; then
    log "skip missing package $pkg"
    continue
  fi
  out=$(pm disable-user --user 0 "$pkg" 2>&1)
  rc=$?
  log "pm disable-user $pkg: rc=$rc $out"
  if [ "$rc" -eq 0 ]; then
    echo "$pkg" >> "$DISABLED_LIST"
  fi
done

log "service done"
