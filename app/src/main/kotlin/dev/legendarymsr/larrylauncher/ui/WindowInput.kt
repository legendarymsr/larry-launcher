package dev.legendarymsr.larrylauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.legendarymsr.larrylauncher.wayland.AXIS_SOURCE_FINGER
import dev.legendarymsr.larrylauncher.wayland.AXIS_SOURCE_WHEEL
import dev.legendarymsr.larrylauncher.wayland.BTN_LEFT
import dev.legendarymsr.larrylauncher.wayland.BTN_MIDDLE
import dev.legendarymsr.larrylauncher.wayland.BTN_RIGHT
import dev.legendarymsr.larrylauncher.wayland.SurfaceHit
import dev.legendarymsr.larrylauncher.wayland.XdgToplevel
import kotlin.math.roundToInt

private const val WHEEL_STEP = 15f

/**
 * Routes pointer input over a Linux window's view to its surfaces. Mouse input
 * is forwarded as-is. Touch is mapped for a phone: tap = left click, long
 * press = right click (wl_touch clients: touch drag), drag = finger scroll everywhere.
 */
fun Modifier.linuxWindowInput(
    wm: WindowManager,
    window: XdgToplevel,
) = pointerInput(wm, window) {
    handlePointer(wm, window, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
}

private fun XdgToplevel.hitTest(
    x: Float,
    y: Float,
) = renderer?.hitTest(x, y)

private suspend fun PointerInputScope.handlePointer(
    wm: WindowManager,
    window: XdgToplevel,
    touchSlop: Float,
    longPressMs: Long,
) = awaitPointerEventScope {
    var mouseButtons = 0
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        if (change.type == PointerType.Mouse) {
            mouseButtons = handleMouse(wm, window, event, mouseButtons)
            change.consume()
            continue
        }
        if (event.type != PointerEventType.Press || change.isConsumed) continue

        val start = change.position
        val hit = window.hitTest(start.x, start.y)
        change.consume()
        if (!wm.onPress(window, hit) || hit == null) continue

        handleTouch(wm, window, change.id, start, hit, touchSlop, longPressMs)
    }
}

/** Nested niri's winit window: two-finger swipes there scroll niri's columns/workspaces. */
internal fun isNiriWindow(
    title: String,
    appId: String,
) = title.equals("niri", ignoreCase = true) || appId.equals("niri", ignoreCase = true)

/** Finger travel (px) per two-finger wheel step sent to niri. */
private const val NIRI_STEP_PX = 120f

/** evdev KEY_LEFTALT: niri's Mod when nested under winit. */
private const val KEY_LEFTALT = 56

/**
 * One finger: tap = click (or a real touch tap for wl_touch clients), drag = smooth finger
 * scroll (pointer axis, source finger) to the surface under the finger, long press = right
 * click, or for wl_touch clients a real touch drag (foot's selection). Two fingers on the
 * niri window = Mod+wheel steps, which niri's binds map to focus-column / focus-workspace.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleTouch(
    wm: WindowManager,
    window: XdgToplevel,
    firstId: androidx.compose.ui.input.pointer.PointerId,
    start: androidx.compose.ui.geometry.Offset,
    hit: SurfaceHit,
    touchSlop: Float,
    longPressMs: Long,
) {
    val touch = wm.wantsTouch(hit.surface)
    val niri = isNiriWindow(window.title, window.appId)
    var scrolling = false
    var last = start
    while (true) {
        val next =
            if (!scrolling) withTimeoutOrNull(longPressMs) { awaitPointerEvent() } else awaitPointerEvent()
        if (next == null) {
            // Long press.
            if (touch) touchDrag(wm, firstId, start, hit) else rightClickHold(wm, firstId, hit)
            return
        }
        val pressed = next.changes.filter { it.pressed }
        if (niri && !scrolling && pressed.size >= 2) {
            next.changes.forEach { it.consume() }
            twoFingerNiri(wm, hit)
            return
        }
        val c = next.changes.firstOrNull { it.id == firstId } ?: break
        if (c.isConsumed) break
        c.consume()
        if (!c.pressed) {
            if (!scrolling) {
                if (touch) {
                    wm.touchDown(hit, 0)
                    wm.touchUp(0)
                } else {
                    wm.click(hit, BTN_LEFT)
                }
            }
            break
        }
        if (!scrolling && (c.position - start).getDistance() > touchSlop) {
            scrolling = true
            wm.pointerMove(hit)
        }
        if (scrolling) {
            val d = c.position - last
            wm.scroll(-d.x / wm.scale, -d.y / wm.scale, AXIS_SOURCE_FINGER)
        }
        last = c.position
    }
    if (scrolling) wm.scrollStop()
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.touchDrag(
    wm: WindowManager,
    id: androidx.compose.ui.input.pointer.PointerId,
    start: androidx.compose.ui.geometry.Offset,
    hit: SurfaceHit,
) {
    val tid = id.value.toInt()
    wm.touchDown(hit, tid)
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
        if (c.isConsumed || !c.pressed) break
        c.consume()
        val d = (c.position - start) / wm.scale.toFloat()
        wm.touchMotion(tid, hit.x + d.x, hit.y + d.y)
    }
    wm.touchUp(tid)
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.rightClickHold(
    wm: WindowManager,
    id: androidx.compose.ui.input.pointer.PointerId,
    hit: SurfaceHit,
) {
    wm.click(hit, BTN_RIGHT)
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
        c.consume()
        if (!c.pressed) break
    }
}

/** Accumulates the two-finger centroid; every [NIRI_STEP_PX] sends one Alt+wheel step. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.twoFingerNiri(
    wm: WindowManager,
    hit: SurfaceHit,
) {
    wm.pointerMove(hit)
    wm.key(KEY_LEFTALT, true)
    var accX = 0f
    var accY = 0f
    try {
        while (true) {
            val e = awaitPointerEvent()
            val down = e.changes.filter { it.pressed }
            e.changes.forEach { it.consume() }
            if (down.isEmpty()) break
            val moved = down.filter { it.previousPressed }
            if (moved.isEmpty()) continue
            accX += moved.sumOf { (it.position.x - it.previousPosition.x).toDouble() }.toFloat() / moved.size
            accY += moved.sumOf { (it.position.y - it.previousPosition.y).toDouble() }.toFloat() / moved.size
            val (sx, sy) = niriSteps(accX, accY, NIRI_STEP_PX)
            if (sx != 0 || sy != 0) {
                // Content follows the fingers: swiping left reveals the column on the right.
                wm.scroll(sx * WHEEL_STEP, sy * WHEEL_STEP, AXIS_SOURCE_WHEEL, sy, sx)
                if (sx != 0) accX = 0f
                if (sy != 0) accY = 0f
            }
        }
    } finally {
        wm.key(KEY_LEFTALT, false)
    }
}

/**
 * Wheel steps for a two-finger swipe of ([accX], [accY]) px: only the dominant axis counts,
 * and the sign is inverted so content follows the fingers (swipe left = column to the right).
 */
internal fun niriSteps(
    accX: Float,
    accY: Float,
    stepPx: Float,
): Pair<Int, Int> {
    val ax = kotlin.math.abs(accX)
    val ay = kotlin.math.abs(accY)
    return if (ax >= ay && ax >= stepPx) {
        Pair(if (accX < 0) 1 else -1, 0)
    } else if (ay > ax && ay >= stepPx) {
        Pair(0, if (accY < 0) 1 else -1)
    } else {
        Pair(0, 0)
    }
}

private fun handleMouse(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    previousButtons: Int,
): Int {
    val change = event.changes.first()
    when (event.type) {
        PointerEventType.Exit -> {
            wm.pointerMove(null)
            return previousButtons
        }
        PointerEventType.Scroll -> {
            val d = change.scrollDelta
            wm.scroll(d.x * WHEEL_STEP, d.y * WHEEL_STEP, AXIS_SOURCE_WHEEL, d.y.roundToInt())
            return previousButtons
        }
        else -> Unit
    }
    val hit: SurfaceHit? = window.hitTest(change.position.x, change.position.y)
    val buttons =
        (if (event.buttons.isPrimaryPressed) 1 else 0) or
            (if (event.buttons.isSecondaryPressed) 2 else 0) or
            (if (event.buttons.isTertiaryPressed) 4 else 0)
    val pressed = buttons and previousButtons.inv()
    if (pressed != 0 && !wm.onPress(window, hit)) return buttons
    // Keep the grab on the pressed surface while dragging.
    if (previousButtons == 0 || wm.server.input.pointerFocus == hit?.surface) wm.pointerMove(hit)
    for ((bit, code) in BUTTONS) {
        if (pressed and bit != 0) wm.pointerButton(code, true)
        if (previousButtons and bit != 0 && buttons and bit == 0) wm.pointerButton(code, false)
    }
    return buttons
}

private val BUTTONS = listOf(1 to BTN_LEFT, 2 to BTN_RIGHT, 4 to BTN_MIDDLE)
