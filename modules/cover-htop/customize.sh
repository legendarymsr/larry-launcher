#!/system/bin/sh
SKIPUNZIP=0
ui_print "- Larry cover screen htop"
ui_print "- Log: /data/local/tmp/larry-cover-htop.log"
ui_print "- Settings: /data/adb/larry-cover-htop.conf (created on first boot)"
pm path dev.legendarymsr.larrylauncher >/dev/null 2>&1 || ui_print "! Larry Launcher isn't installed yet; the module waits for it"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
