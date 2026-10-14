package dev.legendarymsr.larrylauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.legendarymsr.larrylauncher.Runtime
import dev.legendarymsr.larrylauncher.widgets.render.LocalHomeCovered
import dev.legendarymsr.larrylauncher.widgets.render.LocalInputFocus

@Composable
fun LauncherRoot(
    runtime: Runtime,
    state: LauncherState,
) {
    state.bindScope()
    val ready = runtime.launcherReady
    // With our live wallpaper active the window is transparent and Android draws the wallpaper behind it.
    val showOwnBackground = !ready || !runtime.themes.liveWallpaperActive
    Box(
        Modifier
            .fillMaxSize()
            .then(if (showOwnBackground) Modifier.background(Palette.background) else Modifier),
    ) {
        if (ready) DesktopScreen(runtime, state) else SetupScreen(runtime)
    }
}

@Composable
private fun DesktopScreen(
    runtime: Runtime,
    state: LauncherState,
) {
    val context = LocalContext.current
    // Something that needs Linux was asked for (a terminal, Super+Enter): offer to install it.
    LaunchedEffect(runtime.linuxWanted) {
        if (runtime.linuxWanted) {
            runtime.linuxWanted = false
            state.close()
            state.linuxOpen = true
        }
    }
    BackHandler {
        when {
            // Screens opened from settings return to it; settings itself is last.
            state.linuxOpen -> state.linuxOpen = false
            state.licensesOpen -> state.licensesOpen = false
            state.kernelOpen -> state.kernelOpen = false
            state.degoogleOpen -> state.degoogleOpen = false
            state.gesturesOpen -> state.gesturesOpen = false
            state.desktopOpen -> state.desktopOpen = false
            state.styleOpen -> state.styleOpen = false
            state.packagesOpen -> state.packagesOpen = false
            state.settingsOpen -> state.settingsOpen = false
            runtime.widgets.openScreen != null -> runtime.widgets.openScreen = null
            else -> state.close()
        }
    }

    if (!runtime.themes.liveWallpaperActive) {
        runtime.themes.wallpaper?.let { bmp ->
            // Read while drawing, so swiping pages only redraws this.
            Canvas(Modifier.fillMaxSize()) {
                val src = wallpaperCrop(bmp.width, bmp.height, size.width.toInt(), size.height.toInt(), state.wallpaperOffset)
                drawImage(
                    bmp,
                    srcOffset = IntOffset(src.left, src.top),
                    srcSize = IntSize(src.width(), src.height()),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
    if (LauncherStyle.framedSystemBars) SystemBarFrames()
    val covered = remember(state) { { state.homeCovered || runtime.widgets.openScreen != null } }
    CompositionLocalProvider(LocalHomeCovered provides covered) {
        Column(Modifier.fillMaxSize().safePadding()) {
            NiriHome(runtime, state, Modifier.weight(1f))
        }
    }

    // Only whether it shows is read here; the animation itself runs in the layer, without recomposing.
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    // Closing it forgets the search, like stock drawers: the next swipe up shows every app, not the old results.
    LaunchedEffect(panelShown) { if (!panelShown) state.query = "" }
    val search by remember(runtime, state) { derivedStateOf { searchApps(runtime, state.query) } }
    val highlighted = search.highlighted(state.picked)

    fun launch(hit: AppHit) {
        runtime.usage.record(hit.key)
        state.query = ""
        state.closeNow()
        when (hit) {
            is AppHit.Linux -> runtime.launch(hit.app)
            is AppHit.Android -> runtime.androidApps.open(hit.app)
        }
    }

    val handlers =
        remember(runtime, state, context) {
            AppHandlers(
                launch = ::launch,
                // Pins to the page that's showing; the panel closes so the new tile is visible.
                // Pins to the workspace that's showing, as a new column at the end of its strip.
                pin = { hit ->
                    when (hit) {
                        is AppHit.Linux -> runtime.widgets.shortcuts.addLinux(hit.app, state.currentPage)
                        is AppHit.Android -> runtime.widgets.shortcuts.addAndroid(hit.app, state.currentPage)
                    }
                    state.close()
                },
                info = { app ->
                    state.closeNow()
                    runtime.androidApps.openInfo(app)
                },
                uninstall = { app -> runtime.androidApps.uninstall(app) },
            )
        }

    if (panelShown) {
        // Near-opaque like stock drawers: home widgets bleeding through made icons hard to read.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.drawer.value.coerceAtMost(1f) }
                .background(Palette.background.copy(alpha = 0.97f))
                .clickable { state.close() }
                .drawerSwipe(state)
                .swipeTabs(runtime, state),
        )
    }
    // The bar and the panel above it; the keyboard lifts both. Nothing here takes touches while the panel is closed,
    // except the bar. A phone's landscape folds them into a rail beside the grid, and unfolds them over it, centered.
    Column(
        Modifier
            .fillMaxSize()
            .safePadding()
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (panelShown) {
                AppPanel(
                    runtime = runtime,
                    state = state,
                    search = search,
                    highlighted = highlighted,
                    handlers = handlers,
                    onSettings = {
                        state.closeNow()
                        state.settingsOpen = true
                    },
                    modifier =
                        Modifier.graphicsLayer {
                            val progress = state.drawer.value
                            alpha = progress.coerceAtMost(1f)
                            translationY = (1f - progress) * size.height * 0.6f
                        },
                )
            }
        }
        if (runtime.terminals.active == null || runtime.terminals.keyboardLeaving) {
            SearchBar(
                state,
                search,
                highlighted,
                onLaunch = handlers.launch,
                onSettings = {
                    state.closeNow()
                    state.settingsOpen = true
                },
                modifier = Modifier.homeSwipe(runtime, state),
            )
        }
    }

    // Full-screen panels draw over the bar.
    ThemesOverlay(runtime, state)
}

@Composable
private fun ThemesOverlay(
    runtime: Runtime,
    state: LauncherState,
) {
    // Settings first so the screens it opens draw on top of it.
    if (state.settingsOpen) {
        SettingsScreen(
            runtime,
            onDismiss = { state.settingsOpen = false },
            onStyle = { state.styleOpen = true },
            onPackages = { state.packagesOpen = true },
            onLinux = { state.linuxOpen = true },
            onDesktop = { state.desktopOpen = true },
            onLicenses = { state.licensesOpen = true },
            onKernel = { state.kernelOpen = true },
            onDeGoogle = { state.degoogleOpen = true },
            onGestures = { state.gesturesOpen = true },
        )
    }
    if (state.packagesOpen) PackagesScreen(runtime, onDismiss = { state.packagesOpen = false })
    if (state.styleOpen) StyleScreen(runtime, onDismiss = { state.styleOpen = false })
    if (state.degoogleOpen) DeGoogleScreen(runtime, onDismiss = { state.degoogleOpen = false })
    if (state.kernelOpen) KernelScreen(runtime, onDismiss = { state.kernelOpen = false })
    if (state.licensesOpen) LicensesScreen(onDismiss = { state.licensesOpen = false })
    if (state.gesturesOpen) GesturesScreen(runtime, onDismiss = { state.gesturesOpen = false })
    if (state.linuxOpen) LinuxScreen(runtime, onDismiss = { state.linuxOpen = false })
    if (state.desktopOpen) DesktopModeScreen(runtime, onDismiss = { state.desktopOpen = false })
}

/** Clear of the system bars and of the camera cutout, which landscape puts at a side. */
@Composable
private fun Modifier.safePadding() = windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))

/**
 * Bands behind Android's status bar and navigation bar, so its clock, notification icons and gesture handle sit on
 * the same glass as home's tiles rather than on the wallpaper. Each fills its bar out to the screen's edges, with the
 * tiles' border only along the side facing them, a gap away. Under the drawer and full-screen panels, which cover the
 * bars themselves.
 */
@Composable
private fun SystemBarFrames() {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val status = WindowInsets.statusBars
    val nav = WindowInsets.navigationBars
    with(density) {
        val top = status.getTop(density).toDp()
        val bottom = nav.getBottom(density).toDp()
        // Three-button navigation stands at a side in a phone's landscape.
        val left = nav.getLeft(density, direction).toDp()
        val right = nav.getRight(density, direction).toDp()
        Box(Modifier.fillMaxSize()) {
            // Thinner than that leaves no room for a band: a gesture bar hidden in the system's settings, say.
            fun Dp.band() = takeIf { it >= 8.dp }
            val across = Modifier.fillMaxWidth()
            top.band()?.let { Box(across.align(Alignment.TopCenter).height(it).bar { w -> Rect(0f, size.height - w, size.width, size.height) }) }
            bottom.band()?.let { Box(across.align(Alignment.BottomCenter).height(it).bar { w -> Rect(0f, 0f, size.width, w) }) }
            // Between the top and bottom ones, a gap from each. Left and right of the screen, whichever way text runs.
            val between = Modifier.fillMaxSize().padding(top = top + HomeGap, bottom = bottom + HomeGap)
            left.band()?.let { Box(between.wrapContentWidth(AbsoluteAlignment.Left).width(it).bar { w -> Rect(size.width - w, 0f, size.width, size.height) }) }
            right.band()?.let { Box(between.wrapContentWidth(AbsoluteAlignment.Right).width(it).bar { w -> Rect(0f, 0f, w, size.height) }) }
        }
    }
}

/** A tile's glass, and its border only where [line] says: the side facing the tiles. */
private fun Modifier.bar(line: DrawScope.(width: Float) -> Rect) =
    background(Palette.glass).drawBehind {
        val r = line(2.dp.toPx())
        drawRect(Palette.border, r.topLeft, r.size)
    }
