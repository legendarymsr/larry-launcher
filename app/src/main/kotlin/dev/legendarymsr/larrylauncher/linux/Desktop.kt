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
 * USE niri's dependency chain needs, from the ebuilds: pango wants freetype[harfbuzz,png] and
 * harfbuzz[glib,truetype]; X and introspection are off to keep the build small.
 */
internal val DESKTOP_USE =
    listOf(
        "gui-wm/niri -systemd -screencast dbus",
        "virtual/rust rustfmt",
        "media-libs/freetype harfbuzz png",
        "media-libs/harfbuzz glib truetype",
        "x11-libs/pango -X -introspection",
        "x11-libs/cairo -X",
    ).joinToString(" ") { shellQuote(it) }

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
        "printf '%s\\n' 'gui-wm/niri ~amd64 ~arm64' 'gui-apps/fuzzel ~arm64' 'dev-lang/rust-bin ~arm64' 'virtual/rust ~arm64' > /etc/portage/package.accept_keywords/larrylauncher-desktop",
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

    /** The command that starts niri; seeds the config first if there's none. */
    fun startCommand(): String {
        val config = File(chroot.home, ".config/niri/config.kdl")
        if (!config.exists()) runCatching {
            config.parentFile?.mkdirs()
            config.writeText(NIRI_CONFIG)
        }
        return "niri"
    }

    companion object {
        const val STOP_COMMAND = "pkill -x niri"
    }
}
