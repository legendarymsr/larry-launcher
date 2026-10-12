package dev.legendarymsr.larrylauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Atoms the optional Gentoo desktop adds; none of them are in the default install. */
internal val DESKTOP_PACKAGES = listOf("gui-wm/niri", "gui-apps/fuzzel")

/**
 * USE niri's dependency chain needs, matched to the arm64 binhost's own builds so they download
 * instead of compiling: mesa[X] needs libglvnd[X] (the binhost's desktop mesa is X+wayland+vulkan),
 * pango/cairo with X and introspection as built there, freetype[harfbuzz,png] for pango.
 */
internal val DESKTOP_USE_LINES =
    listOf(
        "gui-wm/niri -systemd -screencast dbus",
        "virtual/rust rustfmt",
        "media-libs/mesa X wayland vulkan llvm opengl",
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

/** A small niri config: foot and fuzzel, niri-style column/workspace keys. Nested, niri's Mod is Alt. */
internal val NIRI_CONFIG =
    """
    // larrylauncher: written once; edit freely.
    prefer-no-csd
    layout {
        gaps 12
        center-focused-column "on-overflow"
        focus-ring {
            width 3
            active-color "#7aa2f7"
            inactive-color "#414868"
        }
    }
    binds {
        Mod+Return { spawn "foot"; }
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
        Mod+Shift+E { quit skip-confirmation=true; }
    }
    """.trimIndent() + "\n"

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
    fun startCommand(gpu: Boolean): String = niriStartCommand(gpu)

    companion object {
        const val STOP_COMMAND = "pkill -x niri"
    }
}

internal fun niriStartCommand(gpu: Boolean): String {
    val env =
        listOf("EGL_PLATFORM=wayland", "RUST_BACKTRACE=1", "RUST_LOG=niri=info,smithay=warn", "NIRI_DISABLE_SYSTEM_MANAGER_NOTIFY=1", "XCURSOR_SIZE=24", "LP_NATIVE_VECTOR_WIDTH=128") +
            if (gpu) emptyList() else listOf("LIBGL_ALWAYS_SOFTWARE=1")
    // llvmpipe JITs for the CPU LLVM names from /proc/cpuinfo's "CPU part". On arm64 Mesa passes
    // no feature list, so that name alone decides: newer Cortex parts (X3/A715/A510...) imply SVE2,
    // which Qualcomm and Android kernels turn off, so the JIT'd code dies with SIGILL. niri gets a
    // private mount namespace where /proc/cpuinfo has no CPU implementer/part lines: LLVM then
    // returns "generic" (plain armv8-a + NEON, which every phone has). Gentoo's mesa[llvm] has no
    // softpipe (the ebuild builds one or the other), so there is no non-JIT fallback to retry on.
    val run = "env ${env.joinToString(" ")}"
    val niri = "$run GALLIUM_DRIVER=llvmpipe niri -c \"\$c\""
    val masked =
        "m=/tmp/.larrylauncher-cpuinfo; grep -v -E '^CPU (implementer|part|variant|revision)' /proc/cpuinfo > \"\$m\"; " +
            "if unshare -m true 2>/dev/null; then exec unshare -m sh -c 'mount --bind \"\$0\" /proc/cpuinfo && " +
            "echo \"larrylauncher: cpuinfo masked, LLVM CPU: generic\" || echo \"larrylauncher: could not mask cpuinfo, llvmpipe may SIGILL\"; " +
            "exec \"\$@\"' \"\$m\" $niri; fi; echo 'larrylauncher: no mount namespace, llvmpipe may SIGILL'; exec $niri"
    val script =
        "c=\$HOME/.config/niri/config.kdl; [ -s \"\$c\" ] || { mkdir -p \"\${c%/*}\" && printf '%s' ${shellQuote(NIRI_CONFIG)} > \"\$c\"; }; " +
            "niri validate -c \"\$c\" 2>&1 | tail -n 5; " +
            "grep -m3 -E '^(CPU part|Features|CPU architecture)' /proc/cpuinfo; " +
            (if (gpu) "exec $run niri -c \"\$c\" 2>&1" else
                "exec 2>&1; $masked")
    return "sh -c " + shellQuote(script)
}
