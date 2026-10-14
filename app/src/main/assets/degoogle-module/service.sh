#!/system/bin/sh
MODDIR=${0%/*}
D=/data/adb/larry-degoogle
LIST=$D/disabled.txt
LOG=$D/log.txt
LARRY=dev.legendarymsr.larrylauncher
ONEUI=com.sec.android.app.launcher
until cmd package list packages >/dev/null 2>&1; do sleep 2; done

restore() {
  [ -f $LIST ] && while read -r p; do [ -n "$p" ] && pm enable --user 0 "$p" >/dev/null 2>&1; done < $LIST
}

# Two boots in a row that never finished: put everything back and stand down.
if [ "$(cat $D/boots 2>/dev/null || echo 0)" -ge 3 ]; then
  echo "$(date) boots didn't finish, re-enabled everything and disabled the module" >> $LOG
  restore
  pm enable --user 0 $ONEUI >/dev/null 2>&1
  : > $LIST
  touch $MODDIR/disable
  echo 0 > $D/boots
  exit 0
fi

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
echo 0 > $D/boots

# A launcher must always exist: if Larry is gone or disabled, One UI Home comes back.
if ! pm list packages -e 2>/dev/null | grep -qx "package:$LARRY"; then
  pm enable --user 0 $ONEUI >/dev/null 2>&1
fi

# Re-apply the list (an update or Play can turn apps back on). Never the protected ones.
[ -f $LIST ] && while read -r p; do
  case "$p" in
    ""|com.google.android.ext.services|com.google.android.permissioncontroller|com.google.android.modulemetadata|com.google.android.webview|com.google.android.setupwizard|$ONEUI|$LARRY) continue ;;
  esac
  pm disable-user --user 0 "$p" >/dev/null 2>&1
done < $LIST
