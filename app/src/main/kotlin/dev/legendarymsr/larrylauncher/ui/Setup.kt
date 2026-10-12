package dev.legendarymsr.larrylauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.linux.LinuxState
import dev.legendarymsr.larrylauncher.linux.Stage
import dev.legendarymsr.larrylauncher.theme.BundledTheme
import dev.legendarymsr.larrylauncher.theme.ThemeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The first-run screen: pick a theme and, optionally, set up Linux. Start
 * applies the theme, then the home screen opens; a Linux install
 * carries on in the background.
 */
@Composable
internal fun SetupScreen(runtime: Runtime) {
    val themes = runtime.themes
    var linux by rememberSaveable { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val install = rememberLinuxInstall(runtime)
    val scope = rememberCoroutineScope()
    // First launch: ask Magisk right away, with the real-root warning on screen (RootPanel).
    LaunchedEffect(Unit) { runtime.requestRoot() }
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = Gutter, vertical = 32.dp),
            ) {
                Wordmark()
                if (!started) {
                    Welcome(runtime, themes, linux) { linux = !linux }
                }
            }
        }
        if (!started) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = Gutter, end = Gutter, top = 12.dp, bottom = 16.dp)) {
                Appear(3) {
                    StartButton(linux) {
                        // Linux stays off without root: ask Magisk (again) instead of starting.
                        if (linux && !runtime.chroot.rootGranted) {
                            runtime.requestRoot()
                            return@StartButton
                        }
                        started = true
                        if (linux) install()
                        scope.launch {
                            themes.rescan()
                            themes.applyPicked()
                            runtime.finishWelcome()
                        }
                    }
                }
            }
        }
    }
}

/** The theme's colors as a strip of blocks, like fastfetch's palette, over the name. */
@Composable
private fun Wordmark() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (name in listOf("red", "yellow", "green", "cyan", "blue", "magenta")) {
            Box(Modifier.size(width = 22.dp, height = 8.dp).background(Palette.named(name) ?: Palette.accent))
        }
    }
    Spacer(Modifier.height(20.dp))
    BasicText("◆ larry launcher", style = Mono.copy(color = Palette.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun Welcome(
    runtime: Runtime,
    themes: ThemeStore,
    linux: Boolean,
    onToggleLinux: () -> Unit,
) {
    Appear(0) {
        Column {
            Spacer(Modifier.height(10.dp))
            BasicText("Larry Launcher on your home screen", style = Mono.copy(fontSize = TextSize.Large))
            Spacer(Modifier.height(10.dp))
            BasicText(
                "A scrolling, tiling home for your Android apps. Gentoo Linux adds " +
                    "a Wayland terminal and Portage, now or later in settings.",
                style = Mono.copy(color = Subtle, lineHeight = 20.sp),
            )
        }
    }
    Spacer(Modifier.height(32.dp))
    Appear(1) { ThemeStrip(themes) }
    Spacer(Modifier.height(32.dp))
    Appear(2) {
        Column {
            Label("linux")
            Spacer(Modifier.height(12.dp))
            Choice("also set up Gentoo Linux", "needs magisk root · runs as real root · portage", linux, onToggleLinux)
            Spacer(Modifier.height(12.dp))
            RootPanel(runtime)
        }
    }
}

@Composable
private fun StartButton(
    linux: Boolean,
    onStart: () -> Unit,
) {
    Column {
        PrimaryButton(if (linux) "start and set up linux" else "start", onStart)
        BasicText(
            if (linux) {
                "Linux downloads about 1 GB, so Wi-Fi is best. It installs in the background in 10 to 20 minutes; " +
                    "The home screen is ready right away."
            } else {
                "The home screen opens right away in the picked theme."
            },
            Modifier.padding(top = 10.dp).fillMaxWidth(),
            style = Mono.copy(color = Subtle, fontSize = TextSize.Small, lineHeight = 16.sp),
        )
    }
}

/** A toggle as a bordered row: [title], a one-line [description], and a check box. */
@Composable
private fun Choice(
    title: String,
    description: String,
    on: Boolean,
    onToggle: () -> Unit,
) {
    val border by animateColorAsState(if (on) Palette.accent else Palette.border, label = "border")
    Row(
        Modifier
            .fillMaxWidth()
            .frame(if (on) 2.dp else 1.dp, border, if (on) Palette.accent.copy(alpha = 0.08f) else Palette.background, radius = 12.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mono.copy(fontSize = TextSize.Body, color = if (on) Palette.foreground else Palette.foreground.copy(alpha = 0.8f)))
            BasicText(description, style = Mono.copy(color = Subtle, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Check(on)
    }
}

@Composable
private fun Check(on: Boolean) {
    val fill by animateColorAsState(if (on) Palette.accent else Color.Transparent, label = "check")
    Box(
        Modifier.size(22.dp).frame(1.5.dp, if (on) Palette.accent else Palette.border, fill, radius = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (on) BasicText("✓", style = Mono.copy(color = Palette.background, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .frame(0.dp, Palette.accent, Palette.accent, radius = 14.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Mono.copy(color = Palette.background, fontSize = TextSize.Item, fontWeight = FontWeight.Bold))
    }
}

/** Linux setup's stages as a checklist: done, running (with its step and progress), failed or still to come. */
@Composable
internal fun LinuxProgress(
    state: LinuxState,
    onRetry: () -> Unit,
) {
    val failed = state as? LinuxState.Failed
    val working = state as? LinuxState.Installing
    val current = working?.stage ?: failed?.stage ?: Stage.Download
    val log = working?.log ?: failed?.log.orEmpty()
    BasicText(
        if (failed != null) "setup stopped" else "setting up linux",
        style = Mono.copy(fontSize = TextSize.Large, color = if (failed != null) Palette.red else Palette.foreground),
    )
    Spacer(Modifier.height(24.dp))
    val stages = Stage.entries
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        for (stage in stages) {
            val status =
                when {
                    stage.ordinal < current.ordinal -> StageStatus.Done
                    stage != current -> StageStatus.Pending
                    failed != null -> StageStatus.Failed
                    else -> StageStatus.Running
                }
            StageRow(
                label = stage.label,
                status = status,
                detail = working?.takeIf { stage == current }?.step ?: failed?.takeIf { stage == current }?.message,
                progress = working?.takeIf { stage == current }?.progress,
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    if (failed != null) {
        PrimaryButton("try again", onRetry)
        Spacer(Modifier.height(16.dp))
    }
    LogTail(log, expandedByDefault = failed != null)
    if (failed == null) {
        BasicText(
            "You can close this and use the launcher; setup keeps going in the background.",
            Modifier.padding(top = 16.dp),
            style = Mono.copy(color = Subtle, fontSize = TextSize.Small),
        )
    }
}

private enum class StageStatus { Done, Running, Failed, Pending }

@Composable
private fun StageRow(
    label: String,
    status: StageStatus,
    detail: String?,
    progress: Float?,
) {
    Row {
        Box(Modifier.width(28.dp)) {
            when (status) {
                StageStatus.Done -> Symbol(Glyph.Check, Palette.green)
                StageStatus.Running -> Spinner()
                StageStatus.Failed -> Symbol(Glyph.Close, Palette.red)
                StageStatus.Pending -> BasicText("·", style = Mono.copy(color = Palette.border))
            }
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                label,
                style =
                    Mono.copy(
                        fontSize = TextSize.Body,
                        color =
                            when (status) {
                                StageStatus.Pending -> Subtle
                                StageStatus.Done -> Palette.foreground.copy(alpha = 0.7f)
                                else -> Palette.foreground
                            },
                    ),
            )
            if (detail != null && status != StageStatus.Done) {
                BasicText(
                    detail,
                    Modifier.padding(top = 4.dp),
                    style = Mono.copy(color = if (status == StageStatus.Failed) Palette.red else Subtle, fontSize = TextSize.Small),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null && status == StageStatus.Running) {
                val shown by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(Palette.surface)) {
                    Box(Modifier.fillMaxWidth(shown).height(4.dp).background(Palette.accent))
                }
            }
        }
    }
}

/** A braille spinner, as terminal apps draw them. */
@Composable
private fun Spinner() {
    val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            i = (i + 1) % frames.length
        }
    }
    BasicText(frames[i].toString(), style = Mono.copy(color = Palette.accent, fontWeight = FontWeight.Bold))
}

/** The last lines of setup's output; tap for more. */
@Composable
internal fun LogTail(
    log: List<String>,
    expandedByDefault: Boolean,
) {
    if (log.isEmpty()) return
    var expanded by remember(expandedByDefault) { mutableStateOf(expandedByDefault) }
    Column(
        Modifier
            .fillMaxWidth()
            .frame(1.dp, Palette.border, Palette.surface, radius = 10.dp)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row {
            Label("output")
            Spacer(Modifier.weight(1f))
            BasicText(if (expanded) "less" else "more", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
        }
        Spacer(Modifier.height(6.dp))
        for (line in log.takeLast(if (expanded) 40 else 3)) {
            BasicText(line, style = Mono.copy(color = Subtle, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The themes as swatches in a horizontal row; tapping one recolors everything right away.
 * [gutter] is the parent's side padding, which the row scrolls under. By default a pick only
 * recolors, as on the first-run screen.
 */
@Composable
internal fun ThemeStrip(
    themes: ThemeStore,
    gutter: Dp = Gutter,
    onPick: (BundledTheme) -> Unit = themes::pick,
) {
    RescanThemes(themes)
    val list = themes.choices
    if (list.isEmpty()) return
    Column {
        Row {
            Label("theme")
            Spacer(Modifier.weight(1f))
            BasicText(
                list.firstOrNull { it.name == themes.picked }?.title?.lowercase().orEmpty(),
                style = Mono.copy(color = Subtle, fontSize = TextSize.Small),
            )
        }
        Spacer(Modifier.height(12.dp))
        val start = list.indexOfFirst { it.name == themes.picked }.coerceAtLeast(0)
        LazyRow(
            // Scrolls under the gutters to the screen edges, starting aligned with the text above.
            Modifier.bleed(gutter),
            state = rememberLazyListState(initialFirstVisibleItemIndex = start),
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.name }) { theme -> ThemeSwatch(theme, theme.name == themes.picked) { onPick(theme) } }
        }
    }
}

/** A theme drawn in its own colors: its background, name and a row of its accents. */
@Composable
private fun ThemeSwatch(
    theme: BundledTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    fun color(key: String) = hexColor(theme.colors[key]) ?: Color.Gray
    val border by animateColorAsState(if (selected) Palette.accent else Palette.border, label = "swatch")
    Column(
        Modifier
            .width(112.dp)
            .frame(if (selected) 2.dp else 1.dp, border, color("background"), radius = 12.dp)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        BasicText(theme.title.lowercase(), style = Mono.copy(color = color("foreground"), fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (key in listOf("accent", "red", "yellow", "green", "magenta")) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).background(color(key)))
            }
        }
    }
}

@Composable
internal fun Label(text: String) {
    BasicText(text.uppercase(), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small, letterSpacing = 1.5.sp))
}

/** Fades and slides content in, [index] steps after the screen appears; its space is kept from the start. */
@Composable
private fun Appear(
    index: Int,
    content: @Composable () -> Unit,
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 110L)
        shown.animateTo(1f, tween(450))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * 24.dp.toPx()
        },
    ) { content() }
}

/**
 * Secondary text on these screens. Some themes' dim colors match their surfaces
 * (white's dark_foreground is its lighter_background), so fade the foreground instead.
 */
private val Subtle get() = Palette.foreground.copy(alpha = 0.6f)

/** Side padding of the setup screens. */
private val Gutter = 24.dp

