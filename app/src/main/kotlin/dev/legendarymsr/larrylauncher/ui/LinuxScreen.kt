package dev.legendarymsr.larrylauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.linux.ExtrasState
import dev.legendarymsr.larrylauncher.linux.JobState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import dev.legendarymsr.larrylauncher.linux.LinuxState
import dev.legendarymsr.larrylauncher.linux.RootState
import dev.legendarymsr.larrylauncher.linux.Chroot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Everything about the optional Linux system in one place: what it adds and
 * an install button, the install's progress, or, once installed, its size
 * and a way to remove it. Every feature that needs Linux opens this.
 */
@Composable
fun LinuxScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val state by runtime.bootstrap.state.collectAsState()
    OverlayPanel("linux", onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            when (val s = state) {
                LinuxState.Checking ->
                    if (runtime.linuxBusy) {
                        val root by runtime.chroot.root.collectAsState()
                        BasicText("checking linux…", style = Mono.copy(color = Palette.muted))
                        val phase = if (root == RootState.Waiting) "asking for root (check for a Magisk prompt)" else runtime.linuxPhase
                        if (phase.isNotEmpty()) BasicText(phase, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                    } else {
                        // No job will move it on: show the steps and a retry instead of nothing.
                        LinuxProgress(LinuxState.stopped(s, "linux didn't start"), runtime::retryLinux)
                    }
                LinuxState.NotInstalled -> InstallOffer(runtime)
                is LinuxState.Installing, is LinuxState.Failed -> LinuxProgress(s, runtime::retryLinux)
                LinuxState.Installed -> {
                    Installed(runtime)
                    SetupUpdates(runtime)
                    LaunchFailures(runtime)
                }
                LinuxState.Removing -> BasicText("removing linux…", style = Mono.copy(color = Palette.accent))
            }
        }
    }
}

/**
 * Starts the Linux install and asks (Android 13+) to post notifications: the foreground
 * service's notification shows the install's progress and that Linux is running.
 * The install doesn't wait for the answer, which may never come back: on the welcome
 * screen, becoming the home app moves home to a new task, away from the dialog.
 */
@Composable
fun rememberLinuxInstall(runtime: Runtime): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        runtime.installLinux()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun InstallOffer(runtime: Runtime) {
    val install = rememberLinuxInstall(runtime)
    BasicText("gentoo linux, optional", style = Mono.copy(fontSize = TextSize.Large))
    Spacer(Modifier.height(8.dp))
    BasicText(
        "A Wayland terminal (foot), Portage and Files. Needs Magisk root. A few hundred MB to download; installs in the background.",
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
    Spacer(Modifier.height(12.dp))
    RootPanel(runtime)
    Spacer(Modifier.height(16.dp))
    val root by runtime.chroot.root.collectAsState()
    // Without root, Linux stays off.
    if (root == RootState.Granted) {
        PrimaryButton("install linux") {
            install()
        }
    }
}

@Composable
private fun Installed(runtime: Runtime) {
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { size = withContext(Dispatchers.IO) { diskUsage(runtime.chroot.rootfs) } }
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("gentoo linux", style = Mono.copy(fontSize = TextSize.Item))
            BasicText(
                "installed · " + (size?.let(::formatSize) ?: "measuring…"),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
    ExtrasRow(runtime)
    Spacer(Modifier.height(8.dp))
    SwapRow(runtime)
    CoverHtopRow(runtime)
    if (runtime.gpu.supported) {
        Spacer(Modifier.height(8.dp))
        GpuRow(runtime)
    }
    Spacer(Modifier.height(16.dp))
    if (!confirm) {
        BracketAction("remove linux", Palette.red) { confirm = true }
    } else {
        Column(
            Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("remove linux?", style = Mono.copy(color = Palette.red))
            BasicText(
                "Deletes Linux apps, packages and everything in /root, like Downloads. " +
                    "Wallpapers and your home screen stay.",
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            Row {
                BracketAction("remove", Palette.red, runtime::removeLinux)
                BracketAction("cancel", Palette.muted) { confirm = false }
            }
        }
    }
}

/**
 * The desktop extras' background install: the package emerge is on (n of m), a progress bar,
 * the live log tail, then done, or the error tail with retry. Hidden when there's nothing to show.
 */
@Composable
private fun ExtrasRow(runtime: Runtime) {
    val state = runtime.extras.state
    if (state == ExtrasState.Idle) return
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp)) {
        BasicText("desktop extras · dillo, xwayland", style = Mono.copy(fontSize = TextSize.Item))
        val small = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)
        when (state) {
            is ExtrasState.Working -> {
                BasicText(
                    state.pkg?.let { "installing ${state.n} of ${state.of} · $it" } ?: "starting…",
                    style = small,
                    maxLines = 1,
                )
                val shown by animateFloatAsState((state.progress ?: 0f).coerceIn(0f, 1f), label = "extras")
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(Palette.background)) {
                    Box(Modifier.fillMaxWidth(shown).height(4.dp).background(Palette.accent))
                }
                Spacer(Modifier.height(6.dp))
                for (line in state.log.takeLast(6)) BasicText(line, style = small, maxLines = 1)
            }
            ExtrasState.Done -> BasicText("installed", style = small.copy(color = Palette.green))
            is ExtrasState.Failed -> {
                BasicText("failed:", style = small.copy(color = Palette.red))
                for (line in state.log.takeLast(8)) BasicText(line, style = small.copy(color = Palette.red), maxLines = 3)
                BracketAction("retry", Palette.accent) { runtime.retryExtras() }
            }
            ExtrasState.Idle -> {}
        }
    }
}

/** htop on a Galaxy Z Flip's cover screen while folded; does nothing on other phones. */
@Composable
private fun CoverHtopRow(runtime: Runtime) {
    val cover = runtime.coverHtop
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp)) {
        BasicText("cover screen htop", style = Mono.copy(fontSize = TextSize.Item))
        BasicText(
            "htop as legend on the cover display while folded (flip phones only)",
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            BracketAction("on", if (cover.enabled) Palette.green else Palette.accent) { cover.turn(true) }
            BracketAction("off", if (!cover.enabled) Palette.green else Palette.accent) { cover.turn(false) }
        }
    }
}

/** The root swap file on /data; see [dev.legendarymsr.larrylauncher.linux.Swap]. */
@Composable
private fun SwapRow(runtime: Runtime) {
    val swap = runtime.swap
    LaunchedEffect(Unit) { runtime.refreshSwap() }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp)) {
        BasicText("swap file", style = Mono.copy(fontSize = TextSize.Item))
        BasicText(
            if (swap.busy && !swap.status.startsWith("writing") && !swap.status.startsWith("resuming")) "working… ${swap.status}" else swap.status,
            style = Mono.copy(color = if (swap.status.startsWith("on")) Palette.green else Palette.muted, fontSize = TextSize.Small),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (gb in dev.legendarymsr.larrylauncher.linux.Swap.SIZES) {
                val label = if (gb == 0) "off" else "${gb}g"
                BracketAction(label, if (gb == swap.sizeGb) Palette.green else Palette.accent) {
                    if (!swap.busy) runtime.setSwap(gb)
                }
            }
        }
        if (swap.busy) {
            BracketAction("stop writing (resume later)", Palette.muted) { runtime.cancelSwap() }
        } else if (!confirm) {
            BracketAction("remove swap file", Palette.red) { confirm = true }
        } else {
            Row {
                BracketAction("delete it", Palette.red) { confirm = false; if (!swap.busy) runtime.removeSwap() }
                BracketAction("cancel", Palette.muted) { confirm = false }
            }
        }
    }
}

/** Opt-in GPU acceleration for Linux apps; see [dev.legendarymsr.larrylauncher.linux.Gpu]. */
@Composable
private fun GpuRow(runtime: Runtime) {
    val gpu = runtime.gpu
    val state = gpu.state
    val status =
        when {
            state is JobState.Working -> "${state.action}…"
            gpu.active -> "on · ${gpu.device ?: "GPU"} · for apps opened from now on"
            gpu.wanted && state is JobState.Failed -> "off: the GPU failed its check after a system update"
            gpu.wanted -> "on · waiting for its check"
            state is JobState.Failed -> "the GPU failed its check; apps stay in software"
            else -> "experimental · faster OpenGL and Vulkan apps and games; terminal apps don't change"
        }
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("gpu acceleration", style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    status,
                    style = Mono.copy(color = if (gpu.active) Palette.green else Palette.muted, fontSize = TextSize.Small),
                )
            }
            when {
                state is JobState.Working -> {}
                gpu.wanted -> {
                    if (state is JobState.Failed) BracketAction("retry", Palette.accent) { runtime.setGpu(true) }
                    BracketAction("turn off", Palette.red) { runtime.setGpu(false) }
                }
                else -> BracketAction("turn on", Palette.accent) { runtime.setGpu(true) }
            }
        }
        JobLog(state)
    }
}

/** Bytes in [dir], without following symlinks (the rootfs is full of absolute guest links). */
private fun diskUsage(dir: File): Long {
    var total = 0L
    runCatching {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ) = FileVisitResult.CONTINUE
            },
        )
    }
    return total
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"

/**
 * The real-root warning and the Magisk request: a "grant root" button, "waiting for
 * superuser…" while su runs (Magisk's dialog is up), and on a denial or missing su
 * the reason and a retry, which runs su again.
 */
@Composable
internal fun RootPanel(runtime: Runtime) {
    val root by runtime.chroot.root.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("⚠ " + Chroot.ROOT_WARNING, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
        when (val s = root) {
            RootState.Unknown -> {
                BasicText("Linux needs superuser from Magisk.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                BracketAction("grant root", Palette.accent) { runtime.requestRoot() }
            }
            RootState.Waiting ->
                BasicText("waiting for superuser… answer Magisk's dialog", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
            RootState.Granted -> BasicText("✓ root granted", style = Mono.copy(color = Palette.green, fontSize = TextSize.Small))
            is RootState.Denied -> {
                BasicText(s.message, style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
                BracketAction("retry", Palette.accent) { runtime.requestRoot() }
            }
        }
    }
}

/** Linux apps that exited without opening: exit code and the end of their output. */
@Composable
private fun LaunchFailures(runtime: Runtime) {
    val failures = runtime.launchFailures
    if (failures.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    BasicText("apps that didn't open", style = Mono.copy(color = Palette.red, fontSize = TextSize.Item))
    for (f in failures) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(12.dp)) {
            BasicText("${f.command.take(80)} · exit ${f.code} · ${f.seconds}s", style = Mono.copy(fontSize = TextSize.Small))
            BasicText(
                f.output.takeLast(12).joinToString("\n").ifEmpty { "(no output)" },
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
}
