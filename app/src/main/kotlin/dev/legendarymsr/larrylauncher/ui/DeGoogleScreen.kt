package dev.legendarymsr.larrylauncher.ui

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.degoogle.DeGoogle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › de-google: Google apps by risk, each off/on with pm for user 0, plus restore-all. */
@Composable
fun DeGoogleScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val dg = runtime.degoogle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by dg.busy.collectAsState()
    val log by dg.log.collectAsState()
    var states by remember { mutableStateOf<Map<String, DeGoogle.State>>(emptyMap()) }
    var home by remember { mutableStateOf(true) }
    var module by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<DeGoogle.Tier?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            states = dg.states()
            home = dg.larryIsHome()
            module = dg.moduleInstalled()
        }
    }
    fun run(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh++
        }
    }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    OverlayPanel("de-google", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card {
                    BasicText(
                        "Turns Google apps off for your user with pm disable-user. Nothing is uninstalled or changed in /system, " +
                            "so restore all, or Android's app settings, brings any of them back. Works under Magisk and KernelSU.",
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
            if (!home) {
                item {
                    Row2("set Larry as the home app first", "so the phone never ends up without a launcher", Palette.red) { requestHome(context) }
                }
            }
            busy?.let { b -> item { BasicText("$b…", style = Mono.copy(color = Palette.accent), modifier = Modifier.padding(4.dp)) } }
            for (tier in DeGoogle.Tier.entries) {
                val list = dg.packages.filter { it.tier == tier && states[it.name]?.installed == true }
                if (list.isEmpty()) continue
                item(key = "h-$tier") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(tier.label, style = Mono.copy(color = if (tier == DeGoogle.Tier.Core) Palette.red else Palette.accent, fontSize = TextSize.Item))
                            BasicText(tier.note, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        if (busy == null && home) BracketAction("all off", Palette.accent) { confirm = tier }
                    }
                }
                items(list, key = { it.name }) { p ->
                    val s = states[p.name]
                    val off = s?.disabled == true
                    val guard = s?.guard
                    Row(
                        Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(p.label, style = Mono.copy(fontSize = TextSize.Item, color = if (off) Palette.muted else Palette.foreground))
                            BasicText("${p.name} · ${guard ?: p.why}", style = Mono.copy(color = if (guard != null) Palette.red else Palette.muted, fontSize = TextSize.Small))
                        }
                        when {
                            busy != null -> {}
                            off -> BracketAction("turn on", Palette.accent) { run { dg.enable(listOf(p.name)) } }
                            guard == null && home ->
                                if (p.tier == DeGoogle.Tier.Core) BracketAction("turn off", Palette.red) { confirm = p.tier }
                                else BracketAction("turn off", Palette.muted) { run { dg.disable(listOf(p.name)) } }
                        }
                    }
                }
            }
            item(key = "never") {
                Card {
                    BasicText("never touched", style = Mono.copy(fontSize = TextSize.Item))
                    BasicText(
                        dg.never.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
            if (busy == null) {
                item { Row2("restore all", "turn every app on this list back on") { confirmRestore = true } }
                item {
                    Row2(
                        if (module) "module installed" else "install the keep-off module",
                        "Magisk/KernelSU module: re-applies your list after boot, turns everything back on after two failed boots or when removed",
                    ) { if (!module) run { dg.installModule() } }
                }
            }
            item { Row2("F-Droid", "free app store: f-droid.org") { open("https://f-droid.org/") } }
            item { Row2("Aurora Store", "Play Store apps without Play: on F-Droid") { open("https://f-droid.org/packages/com.aurora.store/") } }
            item {
                Row2("about microG", "a Play Services replacement; needs signature spoofing, which stock One UI doesn't have, so it's for custom ROMs") {
                    open("https://microg.org/")
                }
            }
            if (log.isNotEmpty()) {
                item { Card { BasicText(log.joinToString("\n"), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)) } }
            }
        }
    }
    confirm?.let { tier ->
        ConfirmDialog(
            "turn off ${tier.label} apps?",
            tier.note + (if (tier == DeGoogle.Tier.Core) " Banking and work apps often refuse to run. Restore all undoes it." else " Restore all undoes it."),
            "turn off",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            val names = dg.packages.filter { it.tier == tier && states[it.name]?.let { s -> s.installed && !s.disabled && s.guard == null } == true }.map { it.name }
            run { dg.disable(names) }
        }
    }
    if (confirmRestore) {
        ConfirmDialog("restore all?", "Every app on this list is turned back on.", "restore", onDismiss = { confirmRestore = false }, confirmColor = Palette.accent) {
            confirmRestore = false
            run { dg.restoreAll() }
        }
    }
}

/** Asks Android to make Larry the home app; if it won't show its dialog, opens the default-apps screen. */
internal fun requestHome(context: android.content.Context) {
    val roles = context.getSystemService(RoleManager::class.java)
    val activity = context as? android.app.Activity
    if (activity != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
        @Suppress("DEPRECATION")
        runCatching { activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 2) }.onSuccess { return }
    }
    context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(14.dp)) { content() }
}

@Composable
private fun Row2(
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color = Palette.foreground,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).clickable(onClick = onClick).padding(14.dp)) {
        BasicText(title, style = Mono.copy(fontSize = TextSize.Item, color = color))
        BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}
