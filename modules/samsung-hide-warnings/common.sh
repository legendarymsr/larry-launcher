#!/system/bin/sh
# Shared helpers for Hide Samsung Warnings (sourced by the other scripts).
# Caller must set MODDIR=${0%/*} before sourcing.
# shellcheck disable=SC2034 # DISABLED_LIST / NOTICE_PACKAGES used by scripts that source this

LOG=/data/local/tmp/samsung-hide-warnings.log
DISABLED_LIST=${MODDIR}/disabled-packages.txt

log() {
  echo "$(date '+%F %T') $*" >> "$LOG"
}

# Keep the log under 256 KiB.
trim_log() {
  if [ -f "$LOG" ]; then
    sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    [ "$sz" -gt 262144 ] && mv "$LOG" "$LOG.old"
  fi
}

find_resetprop() {
  if [ -x /data/adb/ksu/bin/resetprop ]; then
    echo /data/adb/ksu/bin/resetprop
  elif [ -x /data/adb/magisk/resetprop ]; then
    echo /data/adb/magisk/resetprop
  elif command -v resetprop >/dev/null 2>&1; then
    command -v resetprop
  else
    return 1
  fi
}

# Set a prop. With -n in post-fs-data (avoids property_service deadlock).
# Only writes when the current value differs, so we don't thrash.
apply_prop() {
  name=$1
  value=$2
  flags=${3:-}
  cur=$("$RESETPROP" "$name" 2>/dev/null || true)
  if [ "$cur" = "$value" ]; then
    log "prop $name already $value"
    return 0
  fi
  # shellcheck disable=SC2086
  if "$RESETPROP" $flags "$name" "$value"; then
    log "prop $name=$value (was '${cur:-unset}')"
  else
    log "prop FAILED $name=$value"
  fi
}

# Safe props that hide unlocked / unofficial / warranty-void status in Settings
# and to apps that only check these. Skip ro.config.knox: changing it has
# broken Knox-dependent services on some One UI builds.
apply_hide_props() {
  flags=${1:-}
  apply_prop ro.boot.verifiedbootstate green "$flags"
  apply_prop ro.boot.flash.locked 1 "$flags"
  apply_prop ro.boot.vbmeta.device_state locked "$flags"
  apply_prop ro.boot.warranty_bit 0 "$flags"
  apply_prop ro.warranty_bit 0 "$flags"
  apply_prop ro.boot.veritymode enforcing "$flags"
  apply_prop ro.secureboot.lockstate locked "$flags"
  apply_prop ro.boot.fmp_config 1 "$flags"
  apply_prop sys.oem_unlock_allowed 0 "$flags"
  # Vendor copies, same values; safe no-ops when the prop is absent.
  apply_prop ro.vendor.boot.warranty_bit 0 "$flags"
  apply_prop ro.vendor.warranty_bit 0 "$flags"
}

# Packages known to post the in-Android notices. Conservative: only the
# agent behind "Unauthorized actions have been detected". soagent is the
# software-update agent and is left alone.
NOTICE_PACKAGES="
com.samsung.android.securitylogagent
"
