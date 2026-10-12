package dev.legendarymsr.larrylauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Atoms the optional Gentoo desktop adds; none of them are in the default install. */
internal val DESKTOP_PACKAGES = listOf("gui-wm/niri", "gui-apps/fuzzel", "app-misc/fastfetch")

/**
 * USE niri's dependency chain needs, matched to the arm64 binhost's own builds so they download
 * instead of compiling: mesa[X] needs libglvnd[X] (the binhost's desktop mesa is X+wayland+vulkan),
 * pango/cairo with X and introspection as built there, freetype[harfbuzz,png] for pango.
 */
internal val DESKTOP_USE_LINES =
    listOf(
        "gui-wm/niri -systemd -screencast dbus",
        "virtual/rust rustfmt",
        // -llvm: Gentoo's mesa then builds gallium softpipe instead of llvmpipe, whose JIT emitted
        // instructions (SVE2) phone kernels disable (niri SIGILL). Compiles from source; lavapipe needs llvm.
        "media-libs/mesa X wayland vulkan -llvm opengl VIDEO_CARDS: -lavapipe",
        "media-libs/libglvnd X",
        "media-libs/freetype harfbuzz png",
        "media-libs/harfbuzz glib truetype",
        "x11-libs/pango X introspection",
        "x11-libs/cairo X",
    )

internal val DESKTOP_USE = DESKTOP_USE_LINES.joinToString(" ") { shellQuote(it) }

/**
 * niri and fuzzel live in the GURU overlay, not ::gentoo, and niri's releases are only keyworded ~amd64 there,
 * so that keyword is accepted for it. Neither is on the arm64 binhost: niri compiles from source (Rust, plus clang for bindgen),
 * which takes a long time and a few GB on a phone. Rust comes from the newest rust-bin.
 */
internal val DESKTOP_INSTALL_SCRIPT =
    listOf(
        emergeInstall(listOf("app-eselect/eselect-repository")),
        "(eselect repository list -i | grep -qw guru || eselect repository enable guru)",
        "emaint sync -r guru",
        "mkdir -p /etc/portage/package.accept_keywords /etc/portage/package.use",
        "mkdir -p /etc/portage/package.mask",
        // A failed earlier run's unapplied autounmask files (they pulled the live niri-9999).
        "find /etc/portage -name '._cfg*' -delete",
        // Released niri only: ~amd64 accepts the tagged ebuilds (they carry that keyword), never the
        // keyword-less live 9999 one, which is masked too.
        "printf '%s\\n' 'gui-wm/niri ~amd64 ~arm64' 'gui-apps/fuzzel ~amd64 ~arm64' 'dev-lang/rust-bin ~arm64' 'virtual/rust ~arm64' > /etc/portage/package.accept_keywords/larrylauncher-desktop",
        "printf '%s\\n' '=gui-wm/niri-9999' > /etc/portage/package.mask/larrylauncher-desktop",
        "printf '%s\\n' $DESKTOP_USE > /etc/portage/package.use/larrylauncher-desktop",
        emergeInstall(DESKTOP_PACKAGES),
    ).joinToString(" && ")

/** First line of a config the launcher owns and rewrites each start; delete it to keep your own edits. */
internal const val NIRI_CONFIG_MARKER = "// larrylauncher: managed"

/** niri accepts fractional output scales; Android's density (2.625 etc.), clamped to sane values. */
internal fun niriScale(density: Float): String {
    val d = if (density.isNaN()) 2f else density.coerceIn(1f, 4f)
    return "%.3f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.').let { if ('.' in it) it else "$it.0" }
}

/**
 * The niri config: the nested winit output gets the phone's density as its scale (niri connects
 * on the launcher's scale-1 socket with a full-resolution window, so nothing double-scales),
 * one full-width column at a time, and every bind on both Mod (Alt when nested) and Super.
 */
internal fun niriConfig(density: Float): String =
    """
    $NIRI_CONFIG_MARKER (rewritten every start; delete this line to keep your edits)
    output "winit" {
        scale ${niriScale(density)}
    }
    prefer-no-csd
    spawn-at-startup "foot" "sh" "-c" "command -v fastfetch >/dev/null && fastfetch; exec bash -l"
    environment {
        XDG_CURRENT_DESKTOP "niri"
        XDG_SESSION_DESKTOP "niri"
        XDG_SESSION_TYPE "wayland"
    }
    cursor {
        xcursor-size 24
    }
    layout {
        gaps 8
        center-focused-column "on-overflow"
        default-column-width { proportion 1.0; }
        preset-column-widths {
            proportion 0.5
            proportion 1.0
        }
        focus-ring {
            width 2
            active-color "#7aa2f7"
            inactive-color "#414868"
        }
    }
    binds {
    LINES
    }
    """.trimIndent().replace("LINES", BINDS) + "\n"

private val BINDS = """        Mod+Return { spawn "foot"; }
        Mod+T { spawn "foot"; }
        Mod+D { spawn "fuzzel"; }
        Mod+Q { close-window; }
        Mod+H { focus-column-left; }
        Mod+L { focus-column-right; }
        Mod+J { focus-workspace-down; }
        Mod+K { focus-workspace-up; }
        Mod+Shift+H { move-column-left; }
        Mod+Shift+L { move-column-right; }
        Mod+R { switch-preset-column-width; }
        Mod+F { maximize-column; }
        Mod+Shift+F { fullscreen-window; }
        Mod+Shift+E { quit skip-confirmation=true; }
        Super+Return { spawn "foot"; }
        Super+T { spawn "foot"; }
        Super+D { spawn "fuzzel"; }
        Super+Q { close-window; }
        Super+H { focus-column-left; }
        Super+L { focus-column-right; }
        Super+J { focus-workspace-down; }
        Super+K { focus-workspace-up; }
        Super+Shift+H { move-column-left; }
        Super+Shift+L { move-column-right; }
        Super+R { switch-preset-column-width; }
        Super+F { maximize-column; }
        Super+Shift+F { fullscreen-window; }
        Super+Shift+E { quit skip-confirmation=true; }
        Mod+WheelScrollRight cooldown-ms=150 { focus-column-right; }
        Mod+WheelScrollLeft cooldown-ms=150 { focus-column-left; }
        Mod+WheelScrollDown cooldown-ms=150 { focus-workspace-down; }
        Mod+WheelScrollUp cooldown-ms=150 { focus-workspace-up; }"""

/**
 * The optional Gentoo desktop: real niri, run as a nested Wayland client of the launcher's own
 * compositor (WAYLAND_DISPLAY is already set for guest processes), so it shows as one fullscreen window.
 */
class Desktop(
    private val chroot: Chroot,
    private val portage: Portage,
) {
    var job by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** /usr/bin/niri as the app sees it; the rootfs is relabelled readable after installs. */
    val installed get() = File(chroot.rootfs, "usr/bin/niri").exists()

    suspend fun install(): Boolean =
        portage.lock.withLock {
            withContext(Dispatchers.IO) {
                job = JobState.Working("installing niri", emptyList())
                val (ok, tail) = chroot.runJob(DESKTOP_INSTALL_SCRIPT) { job = JobState.Working("installing niri", it) }
                chroot.relabel()
                job = if (ok) JobState.Idle else JobState.Failed(tail.toList())
                ok
            }
        }

    /** Output of the last niri run that ended badly or never showed a window; shown on the desktop panel. */
    var lastRun by mutableStateOf<List<String>>(emptyList())

    /**
     * The guest command that starts niri nested: seeds the config as the guest's root (the app
     * can't write /root), then runs niri with its winit backend (WAYLAND_DISPLAY is set). The
     * phone's GPU isn't reachable through Mesa here, so unless the GPU option is on, EGL is forced
     * to llvmpipe on the wayland platform, whose swrast path presents through wl_shm, the only
     * buffer type the launcher's compositor takes. Logs and backtraces go to the launch output.
     */
    fun startCommand(
        density: Float,
        gpu: Boolean,
    ): String = niriStartCommand(gpu, density)

    companion object {
        const val STOP_COMMAND = "pkill -x niri"
    }
}

/** Guest-wide software GL env, so niri (or any GL app) run from foot uses softpipe too. */
internal val SOFTWARE_GL_ENV = listOf("LIBGL_ALWAYS_SOFTWARE=1", "GALLIUM_DRIVER=softpipe", "EGL_PLATFORM=wayland")

/** util-linux unshare makes / private by default, which fails in the chroot (/ isn't a mount point). */
internal const val UNSHARE_MOUNT = "unshare -m --propagation unchanged"

internal fun niriStartCommand(
    gpu: Boolean,
    density: Float = 2f,
): String {
    val env =
        listOf("WAYLAND_DISPLAY=\$w", "XDG_CURRENT_DESKTOP=niri", "XDG_SESSION_DESKTOP=niri", "XDG_SESSION_TYPE=wayland", "EGL_PLATFORM=wayland", "RUST_BACKTRACE=1", "RUST_LOG=niri=info,smithay=warn", "NIRI_DISABLE_SYSTEM_MANAGER_NOTIFY=1", "XCURSOR_SIZE=24", "LP_NATIVE_VECTOR_WIDTH=128") +
            if (gpu) emptyList() else listOf("LIBGL_ALWAYS_SOFTWARE=1")
    val run = "env ${env.joinToString(" ")}"
    // mesa is built -llvm now: softpipe, no JIT. An install still on the old mesa[llvm] (rebuild
    // pending) only has llvmpipe; then niri gets a private mount ns whose /proc/cpuinfo lacks the
    // CPU part lines, so LLVM JITs for generic armv8-a instead of an SVE2 core.
    val llvmNiri = "$run GALLIUM_DRIVER=llvmpipe niri -c \"\$c\""
    val masked =
        "m=/tmp/.larrylauncher-cpuinfo; grep -v -E '^CPU (implementer|part|variant|revision)' /proc/cpuinfo > \"\$m\"; " +
            "if $UNSHARE_MOUNT true 2>/dev/null; then exec $UNSHARE_MOUNT sh -c 'mount --bind \"\$0\" /proc/cpuinfo && " +
            "echo \"larrylauncher: cpuinfo masked, LLVM CPU: generic\" || echo \"larrylauncher: could not mask cpuinfo, llvmpipe may SIGILL\"; " +
            "exec \"\$@\"' \"\$m\" $llvmNiri; fi; echo 'larrylauncher: no mount namespace, llvmpipe may SIGILL'; exec $llvmNiri"
    val software =
        "if portageq has_version / 'media-libs/mesa[llvm]' >/dev/null 2>&1; then echo 'larrylauncher: mesa still has llvm (rebuild pending), llvmpipe'; $masked; " +
            "else echo 'larrylauncher: softpipe'; exec $run GALLIUM_DRIVER=softpipe niri -c \"\$c\"; fi"
    val script =
        "c=\$HOME/.config/niri/config.kdl; mkdir -p \"\${c%/*}\"; " +
            // Ours (managed marker, or the old write-once seed) is rewritten with the current density; a user's own is kept.
            "if [ ! -s \"\$c\" ] || head -n 1 \"\$c\" | grep -q '^// larrylauncher:'; then printf '%s' ${shellQuote(niriConfig(density))} > \"\$c\"; fi; " +
            "w=wayland-niri; [ -S \"\$XDG_RUNTIME_DIR/\$w\" ] || { echo 'larrylauncher: no wayland-niri socket, using the scaled one'; w=\$WAYLAND_DISPLAY; }; " +
            // Older desktop installs predate fastfetch: fetch it in the background (best effort, binpkg if any).
            "command -v fastfetch >/dev/null 2>&1 || { echo 'larrylauncher: installing fastfetch in the background'; (emerge --quiet --noreplace --getbinpkg app-misc/fastfetch > /tmp/.larrylauncher-fastfetch.log 2>&1 &) ; }; " +
            "niri validate -c \"\$c\" 2>&1 | tail -n 5; " +
            (if (gpu) "exec $run niri -c \"\$c\" 2>&1" else "exec 2>&1; $software")
    return "sh -c " + shellQuote(script)
}

