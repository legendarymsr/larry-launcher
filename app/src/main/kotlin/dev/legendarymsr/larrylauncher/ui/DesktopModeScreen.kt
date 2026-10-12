package dev.legendarymsr.larrylauncher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.linux.JobState

/** The optional Gentoo desktop: install real niri, start it fullscreen, stop it. */
@Composable
internal fun DesktopModeScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val desktop = runtime.desktop
    val job = desktop.job
    OverlayPanel("gentoo desktop", onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            "Real niri, nested in the launcher's Wayland display and shown as one fullscreen window. " +
                "Optional: it's not in the default install. niri and fuzzel come from the GURU overlay and aren't on " +
                "the arm64 binhost, so niri compiles from source (Rust): expect hours and a few GB. Inside niri, Mod is Alt: " +
                "Alt+Return terminal, Alt+D launcher, Alt+H/L columns, Alt+J/K workspaces, Alt+Shift+E quit.",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Spacer(Modifier.height(20.dp))
        when {
            job is JobState.Working -> {
                BasicText("${job.action}…", style = Mono.copy(color = Palette.accent))
                Spacer(Modifier.height(12.dp))
                LogTail(job.log, expandedByDefault = true)
            }
            !desktop.installed -> {
                PrimaryButton("install niri (builds from source)") { runtime.installDesktop() }
                if (job is JobState.Failed) {
                    Spacer(Modifier.height(12.dp))
                    BasicText("install failed", style = Mono.copy(color = Palette.red))
                    LogTail(job.log, expandedByDefault = true)
                }
            }
            else -> {
                val running = runtime.niriWindow != null
                PrimaryButton(if (running) "show niri" else "start niri") {
                    onDismiss()
                    runtime.startDesktop()
                }
                if (running) {
                    Spacer(Modifier.height(12.dp))
                    BracketAction("stop niri", Palette.red) { runtime.stopDesktop() }
                } else if (desktop.lastRun.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    BasicText("niri didn't start", style = Mono.copy(color = Palette.red))
                    LogTail(desktop.lastRun.takeLast(40), expandedByDefault = true)
                }
            }
        }
    }
}
