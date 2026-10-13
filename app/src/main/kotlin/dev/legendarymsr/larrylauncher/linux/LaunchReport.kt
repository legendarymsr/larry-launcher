package dev.legendarymsr.larrylauncher.linux

/** A Linux app that exited without staying up: what the linux panel and the toast show. */
data class LaunchFailure(
    val command: String,
    val code: Int,
    val seconds: Long,
    val output: List<String>,
)

/** Exit code of a launch that found no Wayland socket in the guest. */
const val NO_WAYLAND = 126

/** Seconds within which an exit counts as "didn't open", even with code 0 (single-instance apps aside). */
const val QUICK_EXIT_SECONDS = 8L

/**
 * Whether a launch that ended with [code] after [seconds] should be reported. A non-zero exit
 * is, unless the app showed a window and stayed up past [QUICK_EXIT_SECONDS]; a clean, quick exit
 * only if it never put up a window.
 */
fun launchFailed(
    code: Int,
    seconds: Long,
    hadWindow: Boolean,
): Boolean =
    if (hadWindow && seconds >= QUICK_EXIT_SECONDS) false // ran and was closed (a shell's exit code is its last command's)
    else code != 0 || seconds < QUICK_EXIT_SECONDS && !hadWindow

/** One-line summary for a toast: the program, its exit code and its last line of output. */
fun LaunchFailure.summary(): String {
    val prog = command.trim().substringBefore(' ').substringAfterLast('/')
    val noX = output.any { "can't open display" in it.lowercase() || "cannot open display" in it.lowercase() }
    val why =
        when {
            noX && output.any { it.startsWith("larrylauncher: x") || it.startsWith("larrylauncher: X") } ->
                output.last { it.startsWith("larrylauncher: x") || it.startsWith("larrylauncher: X") }.removePrefix("larrylauncher: ")
            noX -> "it's an X11 app: start the Gentoo desktop (niri) first, X apps run through its xwayland-satellite"
            else -> when (code) {
            NO_WAYLAND -> "the launcher's Wayland socket isn't there"
            Chroot.MOUNT_FAILED -> "the guest's mounts failed"
            else -> output.lastOrNull { it.isNotBlank() }?.take(160) ?: "no output"
            }
        }
    return "$prog exited ($code) after ${seconds}s: $why"
}

/** xwayland-satellite's log in the guest's /tmp: the launcher's own starts and niri's hook. */
const val XWS_LOG = "/tmp/.larrylauncher-xws.log"

/**
 * Guest shell that makes sure an X server serves :0 for niri's session (socket in \$nw): a live
 * /tmp/.X0-lock owner with the X0 socket counts; otherwise xwayland-satellite is started on
 * niri's socket, detached so it outlives this app, and waited on for up to ~8 s. Defines
 * ensurex, which fails (Wayland apps still run; X11 ones then can't open a display) and says whether the package is missing (or still building in the background) and prints its log.
 */
internal val ENSURE_X =
    "xup() { [ -S /tmp/.X11-unix/X0 ] && [ -f /tmp/.X0-lock ] && kill -0 \"\$(tr -dc 0-9 < /tmp/.X0-lock)\" 2>/dev/null; }; " +
        "ensurex() { if ! xup; then " +
        "if ! command -v xwayland-satellite >/dev/null 2>&1; then " +
        "if pgrep -f 'emerge.*xwayland-satellite' >/dev/null 2>&1; then echo 'larrylauncher: xwayland-satellite is still installing (progress: settings › linux)'; " +
        "else echo 'larrylauncher: xwayland-satellite is not installed yet (see settings › linux)'; fi; return 1; fi; " +
        "command -v Xwayland >/dev/null 2>&1 || { echo 'larrylauncher: Xwayland (x11-base/xwayland) is not installed'; return 1; }; " +
        "pkill -f 'xwayland-satellite :0' 2>/dev/null; rm -f /tmp/.X0-lock /tmp/.X11-unix/X0; mkdir -p /tmp/.X11-unix; chmod 1777 /tmp/.X11-unix; " +
        "echo \"larrylauncher: starting xwayland-satellite :0 on \$nw\" >> $XWS_LOG; " +
        "( WAYLAND_DISPLAY=\"\$nw\" setsid xwayland-satellite :0 >> $XWS_LOG 2>&1 < /dev/null & ); " +
        "i=0; while [ \$i -lt 40 ] && ! [ -S /tmp/.X11-unix/X0 ]; do sleep 0.2; i=\$((i+1)); done; " +
        "[ -S /tmp/.X11-unix/X0 ] || { echo 'larrylauncher: xwayland-satellite did not open :0'; tail -n 15 $XWS_LOG; return 1; }; fi; }"

/**
 * The guest script for a launch: fails with [NO_WAYLAND] if the socket isn't bound in. While niri
 * runs, the app joins its session (niri's socket and DISPLAY=:0, with [ENSURE_X] first, so X11
 * apps like Dillo work); otherwise it opens on the launcher's compositor, with DISPLAY only if an X server is up.
 */
fun launchCommand(command: String): String =
    "[ -S \"\$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\" ] || { echo \"larrylauncher: no Wayland socket at \$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\"; exit $NO_WAYLAND; }; " +
        "$ENSURE_X; s=\"\$XDG_RUNTIME_DIR/$NIRI_SESSION_FILE\"; if [ -f \"\$s\" ] && pgrep -x niri >/dev/null 2>&1 && read -r nw nd < \"\$s\" && [ -S \"\$XDG_RUNTIME_DIR/\$nw\" ]; then " +
        "export WAYLAND_DISPLAY=\"\$nw\" XDG_CURRENT_DESKTOP=niri XDG_SESSION_DESKTOP=niri; unset DISPLAY; ensurex && export DISPLAY=$NIRI_X_DISPLAY; " +
        "elif [ -z \"\${DISPLAY:-}\" ] && xup; then export DISPLAY=$NIRI_X_DISPLAY; fi; exec $command"
