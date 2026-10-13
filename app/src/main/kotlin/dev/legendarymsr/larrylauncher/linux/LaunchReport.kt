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
            noX -> "it's an X11 app: start the Gentoo desktop (niri) first, X apps run through its xwayland-satellite"
            else -> when (code) {
            NO_WAYLAND -> "the launcher's Wayland socket isn't there"
            Chroot.MOUNT_FAILED -> "the guest's mounts failed"
            else -> output.lastOrNull { it.isNotBlank() }?.take(160) ?: "no output"
            }
        }
    return "$prog exited ($code) after ${seconds}s: $why"
}

/**
 * The guest script for a launch: fails with [NO_WAYLAND] if the socket isn't bound in. While niri
 * runs, the app joins its session (niri's socket and its X display, so X11 apps like Dillo work);
 * otherwise it opens on the launcher's compositor, with DISPLAY only if an X server is up.
 */
fun launchCommand(command: String): String =
    "[ -S \"\$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\" ] || { echo \"larrylauncher: no Wayland socket at \$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\"; exit $NO_WAYLAND; }; " +
        "s=\"\$XDG_RUNTIME_DIR/$NIRI_SESSION_FILE\"; if [ -f \"\$s\" ] && pgrep -x niri >/dev/null 2>&1 && read -r nw nd < \"\$s\" && [ -S \"\$XDG_RUNTIME_DIR/\$nw\" ]; then " +
        "export WAYLAND_DISPLAY=\"\$nw\" DISPLAY=\"\${nd:-$NIRI_X_DISPLAY}\" XDG_CURRENT_DESKTOP=niri XDG_SESSION_DESKTOP=niri; " +
        "elif [ -z \"\${DISPLAY:-}\" ] && [ -S /tmp/.X11-unix/X0 ]; then export DISPLAY=$NIRI_X_DISPLAY; fi; exec $command"
