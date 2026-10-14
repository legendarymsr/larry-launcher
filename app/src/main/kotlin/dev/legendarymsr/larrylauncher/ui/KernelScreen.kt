package dev.legendarymsr.larrylauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.kernel.Kernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › kernel: the running kernel, a boot backup, flashing the Larry kernel and restoring. */
@Composable
fun KernelScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val kernel = runtime.kernel
    val scope = rememberCoroutineScope()
    val busy by kernel.busy.collectAsState()
    val log by kernel.log.collectAsState()
    var device by remember { mutableStateOf<Kernel.Device?>(null) }
    var backups by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmFlash by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    var confirmReboot by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            device = kernel.device()
            backups = kernel.backups()
        }
    }
    fun run(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh++
        }
    }
    val d = device
    val refusal = d?.refusal
    val running = d?.kernel == Kernel.VERSION
    OverlayPanel("kernel", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card {
                    BasicText("running ${d?.kernel ?: "…"}", style = Mono.copy(fontSize = TextSize.Item))
                    BasicText(
                        d?.let { "${it.model} · ${it.device} · Android ${it.android} · ${it.build}" } ?: "",
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        when {
                            d == null -> ""
                            refusal != null -> "can't flash here: $refusal"
                            running -> "the Larry kernel is running."
                            else -> "Larry kernel ${Kernel.VERSION}: Samsung's SM-F721B Android 16 kernel, stock config plus PID namespaces. Only the kernel in boot changes; Magisk stays."
                        },
                        style = Mono.copy(color = if (refusal != null) Palette.red else Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
            busy?.let { b -> item { BasicText("$b…", style = Mono.copy(color = Palette.accent), modifier = Modifier.padding(4.dp)) } }
            if (busy == null && d != null && refusal == null) {
                item { Action("back up boot", "copy the boot partition to /sdcard/LarryLauncher/backup") { run { kernel.backup() } } }
                item { Action("flash the Larry kernel", "backs up boot first, then flashes") { confirmFlash = true } }
                items(backups, key = { it }) { b ->
                    Action("restore ${b.substringAfterLast('/')}", "write this backup back to boot") { confirmRestore = b }
                }
                item { Action("reboot", "restart the phone") { confirmReboot = true } }
            }
            if (log.isNotEmpty()) {
                item {
                    Card { BasicText(log.joinToString("\n"), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)) }
                }
            }
        }
    }
    if (confirmFlash) {
        ConfirmDialog(
            "flash a custom kernel?",
            "This rewrites the kernel in your boot partition. A bad kernel can leave the phone stuck on the Samsung logo, " +
                "and getting out of that needs a PC with Odin or a custom recovery to write the backup back. " +
                "Your current boot is backed up to /sdcard/LarryLauncher/backup first; copy it off the phone too. " +
                "It's built for SM-F721B on Android 16 only. Continue?",
            "flash",
            onDismiss = { confirmFlash = false },
        ) {
            confirmFlash = false
            run { kernel.flash() }
        }
    }
    confirmRestore?.let { path ->
        ConfirmDialog(
            "restore this backup?",
            "Writes ${path.substringAfterLast('/')} over your boot partition. Only restore a backup made on this phone and firmware.",
            "restore",
            onDismiss = { confirmRestore = null },
        ) {
            confirmRestore = null
            run { kernel.restore(path) }
        }
    }
    if (confirmReboot) {
        ConfirmDialog("reboot now?", "The phone restarts right away.", "reboot", onDismiss = { confirmReboot = false }) {
            confirmReboot = false
            run { kernel.reboot() }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun Action(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        BasicText(title, style = Mono.copy(fontSize = TextSize.Item))
        BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}
