package dev.legendarymsr.larrylauncher.widgets

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Renders widgets to PNGs off screen, for agents editing them in the guest:
 * they can't see the phone, so `.preview/<file>.png` shows them what the
 * tile looks like. The tile is drawn exactly as on the home screen, on a
 * private virtual display the size of the tile, so it works while the home
 * screen is hidden behind the agent's terminal.
 */
internal class Previews(
    @Suppress("unused") private val context: Context,
) {
    /** Off-screen previews were for widget authoring; there is no widget UI any more. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun render(
        spec: WidgetSpec,
        instance: dev.legendarymsr.larrylauncher.widgets.runtime.WidgetInstance,
        cells: Pair<Int, Int>,
        widthPx: Int,
        heightPx: Int,
        out: File,
        ready: suspend () -> Unit,
    ): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun renderScreen(
        spec: WidgetSpec,
        instance: dev.legendarymsr.larrylauncher.widgets.runtime.WidgetInstance,
        widthPx: Int,
        heightPx: Int,
        out: File,
        ready: suspend () -> Unit,
    ): Boolean = false
}
