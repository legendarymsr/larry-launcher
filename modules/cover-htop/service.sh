#!/system/bin/sh
# Larry cover screen htop: puts dev.legendarymsr.larrylauncher/.CoverHtopActivity on the
# cover display while the phone is folded, as root (am start --display), so One UI's
# cover-screen allowlist doesn't matter. Unfolding: the app closes it; this asks it to as well.
PKG=dev.legendarymsr.larrylauncher
ACT=$PKG/.CoverHtopActivity
CONF=/data/adb/larry-cover-htop.conf
LOG=/data/local/tmp/larry-cover-htop.log

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 10

if [ ! -f "$CONF" ]; then
  cat > "$CONF" <<'C'
# Display id of the cover screen (Samsung flips: 1).
COVER_DISPLAY=1
# Device states (cmd device_state print-state) that count as folded. See the log for yours.
FOLDED_STATES="0"
# Seconds between checks.
INTERVAL=2
C
fi
. "$CONF"
: "${COVER_DISPLAY:=1}" "${FOLDED_STATES:=0}" "${INTERVAL:=2}"

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 262144 ] && mv "$LOG" "$LOG.old"
log "start: cover display $COVER_DISPLAY, folded states '$FOLDED_STATES'"

# The device state's identifier, e.g. 0 (closed) .. 3 (open); empty if unknown.
state() {
  s=$(cmd device_state print-state 2>/dev/null | tr -dc '0-9' | head -c 3)
  [ -n "$s" ] || s=$(dumpsys device_state 2>/dev/null | sed -n 's/.*mCommittedState=[^0-9]*\([0-9]*\).*/\1/p' | head -n 1)
  echo "$s"
}

# Fallback when there's no device state: the main display off and the cover display on.
displays_folded() {
  d=$(dumpsys display 2>/dev/null)
  main=$(echo "$d" | grep -m1 -A40 'mDisplayId=0' | grep -m1 -o 'state [A-Z]*\|mState=[A-Z]*' | sed 's/.*[= ]//')
  [ "$main" = OFF ] || [ "$main" = DOZE ]
}

folded() {
  s=$(state)
  if [ -n "$s" ]; then
    for f in $FOLDED_STATES; do [ "$s" = "$f" ] && return 0; done
    return 1
  fi
  displays_folded
}

awake() { dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; }

# The app's own "cover screen htop" setting; on unless it's explicitly false.
enabled() { ! grep -q 'name="enabled" value="false"' /data/data/$PKG/shared_prefs/cover_htop.xml 2>/dev/null; }

running() { dumpsys activity activities 2>/dev/null | grep -q 'CoverHtopActivity'; }

last=
while :; do
  if pm path $PKG >/dev/null 2>&1; then
    if folded; then now=folded; else now=open; fi
    if [ "$now" != "$last" ]; then log "state $(state): $now"; last=$now; fi
    if [ "$now" = folded ] && enabled && awake && ! running; then
      out=$(am start --display "$COVER_DISPLAY" -n "$ACT" 2>&1)
      log "am start --display $COVER_DISPLAY: $(echo "$out" | tr '\n' ' ')"
      sleep 3
    elif [ "$now" = open ] && running; then
      # Not force-stop: that would kill the home screen. The activity finishes on this extra.
      am start --display "$COVER_DISPLAY" -n "$ACT" --ez finish true >/dev/null 2>&1
      log "unfolded: asked the cover htop to close"
    fi
  fi
  sleep "$INTERVAL"
done
