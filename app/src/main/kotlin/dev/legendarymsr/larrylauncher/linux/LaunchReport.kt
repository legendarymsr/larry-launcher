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
 * always is; a clean, quick exit only if it never put up a window.
 */
fun launchFailed(
    code: Int,
    seconds: Long,
    hadWindow: Boolean,
): Boolean = code != 0 || (seconds < QUICK_EXIT_SECONDS && !hadWindow)

/** One-line summary for a toast: the program, its exit code and its last line of output. */
fun LaunchFailure.summary(): String {
    val prog = command.trim().substringBefore(' ').substringAfterLast('/')
    val why =
        when (code) {
            NO_WAYLAND -> "the launcher's Wayland socket isn't there"
            Chroot.MOUNT_FAILED -> "the guest's mounts failed"
            else -> output.lastOrNull { it.isNotBlank() }?.take(160) ?: "no output"
        }
    return "$prog exited ($code) after ${seconds}s: $why"
}

/** The guest script for a launch: fails with [NO_WAYLAND] if the socket isn't bound in. */
fun launchCommand(command: String): String =
    "[ -S \"\$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\" ] || { echo \"larrylauncher: no Wayland socket at \$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\"; exit $NO_WAYLAND; }; exec $command"
