SKIPUNZIP=0
ui_print "- Larry De-Google: nothing in /system is changed."
ui_print "- The list lives in /data/adb/larry-degoogle/disabled.txt"
mkdir -p /data/adb/larry-degoogle
touch /data/adb/larry-degoogle/disabled.txt
set_perm_recursive "$MODPATH" 0 0 0755 0644
