#!/system/bin/sh
# Runs early in boot after removal, before the package manager is up, so wait for it in the background.
(
  D=/data/adb/larry-degoogle
  until cmd package list packages >/dev/null 2>&1; do sleep 2; done
  [ -f $D/disabled.txt ] && while read -r p; do [ -n "$p" ] && pm enable --user 0 "$p" >/dev/null 2>&1; done < $D/disabled.txt
  rm -rf $D
) &
