package dev.legendarymsr.larrylauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.wayland.XdgToplevel
import dev.legendarymsr.larrylauncher.widgets.Folder
import dev.legendarymsr.larrylauncher.widgets.Shortcut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One column of the strip: an open Linux window or a pinned app. */
internal sealed interface StripColumn {
    val key: String
    val title: String
    val kind: String

    data class Window(val window: XdgToplevel, override val title: String) : StripColumn {
        override val key get() = "window:" + System.identityHashCode(window)
        override val kind get() = "window"
    }

    data class Pinned(val shortcut: Shortcut) : StripColumn {
        override val key get() = shortcut.key
        override val title get() = shortcut.label
        override val kind get() = if (shortcut.linux != null) "linux" else "android"
    }
}

/**
 * Workspaces as niri has them: each a page number with pinned apps; workspace 1 also holds the open Linux
 * windows. There's always one empty workspace after the last used one, to pin to.
 */
internal fun workspaces(
    pinned: List<Shortcut>,
): List<Int> {
    val used = (pinned.map { it.page } + 1).distinct().sorted()
    return used + (used.last() + 1)
}

/** Columns on workspace [page], in order: windows first (workspace 1 only), then pins. */
internal fun columnsOn(
    page: Int,
    pinned: List<Shortcut>,
    windows: List<StripColumn.Window>,
): List<StripColumn> = (if (page == 1) windows else emptyList()) + pinned.filter { it.page == page }.map { StripColumn.Pinned(it) }

private fun flatPins(runtime: Runtime): List<Shortcut> =
    runtime.widgets.shortcuts.onGrid().flatMap {
        when (it) {
            is Shortcut -> listOf(it)
            is Folder -> it.apps.map { app -> app.copy(page = it.page) }
            else -> emptyList()
        }
    }

/**
 * A niri-like home: a horizontally scrolling strip of columns per workspace, the focused one centered,
 * workspaces stacked vertically. Tap a column to focus it, tap the focused one to open it, long-press to
 * unpin (or close a window). Super+H/L or ←/→ move between columns, Super+J/K or ↑/↓ between workspaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NiriHome(
    runtime: Runtime,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val pins = flatPins(runtime)
    val windows = runtime.wm.appWindows.map { StripColumn.Window(it, runtime.displayName(it.appId, it.title)) }
    val spaces = workspaces(pins)
    val vertical = rememberPagerState { spaces.size }
    val scope = rememberCoroutineScope()
    val strips = remember { mutableMapOf<Int, PagerState>() }

    LaunchedEffect(vertical.currentPage, spaces) {
        state.currentPage = spaces.getOrElse(vertical.currentPage) { 1 }
        runtime.widgets.activePage = state.currentPage
    }
    LaunchedEffect(state.requestedPage) {
        val i = state.requestedPage ?: return@LaunchedEffect
        state.requestedPage = null
        vertical.animateScrollToPage(i.coerceIn(0, spaces.lastIndex))
    }
    LaunchedEffect(state.nav) {
        val (dx, dy) = state.nav ?: return@LaunchedEffect
        state.nav = null
        if (dy != 0) vertical.animateScrollToPage((vertical.currentPage + dy).coerceIn(0, spaces.lastIndex))
        if (dx != 0) {
            strips[spaces.getOrElse(vertical.currentPage) { 1 }]?.let { it.animateScrollToPage((it.currentPage + dx).coerceIn(0, (it.pageCount - 1).coerceAtLeast(0))) }
        }
    }

    Column(modifier) {
        TopBar(spaces.size, vertical.currentPage)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val colWidth = maxWidth * 0.72f
            val side = (maxWidth - colWidth) / 2
            VerticalPager(vertical, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { spaces[it] }) { index ->
                val page = spaces[index]
                val columns = columnsOn(page, pins, windows)
                if (columns.isEmpty()) {
                    EmptyWorkspace(page, onApps = { state.open() })
                } else {
                    val strip = rememberPagerState { columns.size }
                    strips[page] = strip
                    HorizontalPager(
                        strip,
                        Modifier.fillMaxSize(),
                        pageSize = PageSize.Fixed(colWidth),
                        contentPadding = PaddingValues(horizontal = side),
                        pageSpacing = 14.dp,
                        key = { columns.getOrNull(it)?.key ?: it },
                    ) { i ->
                        val column = columns[i]
                        val focused = i == strip.currentPage && index == vertical.currentPage
                        ColumnCard(
                            column = column,
                            icon = iconOf(runtime, column),
                            focused = focused,
                            onTap = {
                                if (!focused) {
                                    scope.launch { strip.animateScrollToPage(i) }
                                } else {
                                    when (column) {
                                        is StripColumn.Window -> runtime.showWindow(column.window)
                                        is StripColumn.Pinned -> runtime.open(column.shortcut)
                                    }
                                }
                            },
                            onLongPress = {
                                when (column) {
                                    is StripColumn.Window -> runtime.wm.close(column.window)
                                    is StripColumn.Pinned -> runtime.widgets.shortcuts.remove(column.shortcut.key)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun iconOf(
    runtime: Runtime,
    column: StripColumn,
): ImageBitmap? =
    when (column) {
        is StripColumn.Pinned -> runtime.shortcutIcon(column.shortcut, mono = false)
        is StripColumn.Window -> runtime.iconNamed(column.title)
    }

/** Workspace indicator at the left, like a bar's workspaces module; the clock at the right. */
@Composable
private fun TopBar(
    count: Int,
    current: Int,
) {
    val clock by produceState(now()) {
        while (true) {
            value = now()
            delay(1000L * (60 - (System.currentTimeMillis() / 1000) % 60))
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until count) {
                val on = i == current
                Box(
                    Modifier
                        .size(width = if (on) 22.dp else 8.dp, height = 8.dp)
                        .background(if (on) Palette.accent else Palette.border, LauncherStyle.shape(4.dp)),
                )
            }
        }
        BasicText(clock, style = Mono.copy(color = Palette.foreground, fontWeight = FontWeight.Bold))
    }
}

private fun now() = SimpleDateFormat("EEE d  HH:mm", Locale.getDefault()).format(Date())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnCard(
    column: StripColumn,
    icon: ImageBitmap?,
    focused: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .frame(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Palette.border, Palette.surface.copy(alpha = 0.92f))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(16.dp),
    ) {
        // A title bar, as a window's: kind on the left, name on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(column.kind, Modifier.weight(1f), style = Mono.copy(color = if (focused) Palette.accent else Palette.muted, fontSize = TextSize.Small))
            BasicText(if (column is StripColumn.Window) "hold: close" else "hold: unpin", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(icon, column.title, Modifier.size(96.dp))
            } else {
                BasicText(column.title.take(1).uppercase(), style = Mono.copy(color = Palette.accent, fontSize = 64.sp, fontWeight = FontWeight.Bold))
            }
        }
        BasicText(
            column.title,
            Modifier.fillMaxWidth(),
            style = Mono.copy(fontSize = TextSize.Title, fontWeight = FontWeight.Bold, color = if (focused) Palette.foreground else Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        BasicText(if (focused) "tap to open" else "tap to focus", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}

@Composable
private fun EmptyWorkspace(
    page: Int,
    onApps: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("workspace $page", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            BasicText("empty · long-press an app in the list to pin it here", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            Spacer(Modifier.height(16.dp))
            BracketAction("open apps", Palette.accent, onApps)
        }
    }
}

/** The bottom bar: find an app (Enter opens the best match), and settings. Swiping up on it opens the list. */
@Composable
internal fun SearchBar(
    state: LauncherState,
    search: BarSearch,
    highlighted: Int,
    onLaunch: (AppHit) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchField(
            state.query,
            onChange = {
                state.query = it
                if (it.isNotEmpty() && state.drawer.targetValue < 0.5f) state.open()
            },
            onGo = { search.hits.getOrNull(highlighted)?.let(onLaunch) },
            placeholder = "apps…",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        BracketAction("⚙", Palette.muted, onSettings)
    }
}
