#!/system/bin/sh
# Counts boots that start; service.sh resets it once a boot finishes.
D=/data/adb/larry-degoogle
mkdir -p $D
n=$(cat $D/boots 2>/dev/null || echo 0)
echo $((n + 1)) > $D/boots
