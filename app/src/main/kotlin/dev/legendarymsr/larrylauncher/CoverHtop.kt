package dev.legendarymsr.larrylauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.legendarymsr.larrylauncher.linux.Chroot
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * htop on a flip phone's cover screen while it's folded. A no-op without a small second
 * built-in display. Folded is: the cover display on, the main display off.
 */
class CoverHtop(
    private val context: Context,
    private val chroot: Chroot,
) : DisplayManager.DisplayListener {
    private val prefs = context.getSharedPreferences("cover_htop", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    var enabled by mutableStateOf(prefs.getBoolean(KEY, true))
        private set

    fun turn(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean(KEY, on).apply()
        check()
    }

    /** Starts watching displays; call once Linux is up. */
    fun start() =
        main.post {
            if (started) return@post
            started = true
            if (coverDisplay() == null) return@post
            dm.registerDisplayListener(this, main)
            check()
        }

    /** The cover display: built in (not presentation or virtual) and small, like the Flip's 260x512. */
    private fun coverDisplay(): Display? =
        dm.displays.firstOrNull { d ->
            d.displayId != Display.DEFAULT_DISPLAY &&
                d.flags and (Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE) == 0 &&
                d.mode.physicalWidth.coerceAtMost(d.mode.physicalHeight) in 1..720
        }

    private fun folded(cover: Display): Boolean =
        cover.state == Display.STATE_ON && dm.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON

    private fun check() {
        val cover = coverDisplay()
        val want = enabled && chroot.rootGranted && cover != null && folded(cover)
        val shown = CoverHtopActivity.current?.get()
        if (!want) {
            shown?.finish()
            return
        }
        if (shown != null && !shown.isFinishing) return
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(cover!!.displayId)
            context.startActivity(
                Intent(context, CoverHtopActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                opts.toBundle(),
            )
        }.onFailure { Log.w(TAG, "launching on the cover display", it) }
    }

    override fun onDisplayAdded(displayId: Int) = check()

    override fun onDisplayRemoved(displayId: Int) = check()

    override fun onDisplayChanged(displayId: Int) = check()

    companion object {
        private const val TAG = "CoverHtop"
        private const val KEY = "enabled"
    }
}

/** A tiny read-only terminal: htop in a guest tmux, its pane drawn once a second. */
class CoverHtopActivity : Activity() {
    private lateinit var view: TextView
    private var process: Process? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                setBackgroundColor(BG)
                setTextColor(FG)
                gravity = Gravity.TOP or Gravity.START
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                text = "starting htop…"
            }
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        view.post { launch() }
    }

    override fun onStop() {
        super.onStop()
        stopGuest()
        finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        stopGuest()
        super.onDestroy()
    }

    private fun launch() {
        if (process != null) return
        val chroot = (application as App).runtime.chroot
        val w = view.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = view.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // Small enough for htop's meters: about 34 columns across.
        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        var px = 6f
        while (px < 40f) {
            paint.textSize = px + 0.5f
            if (paint.measureText("M") * COLS > w) break
            px += 0.5f
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        paint.textSize = px
        val rows = (h / paint.fontSpacing).toInt().coerceAtLeast(10)
        val cols = (w / paint.measureText("M")).toInt().coerceAtLeast(20)
        val p = runCatching { chroot.process(guestScript(cols, rows)).start() }.getOrElse {
            view.text = "cover htop: ${it.message}"
            return
        }
        process = p
        thread(name = "cover-htop", isDaemon = true) {
            val frame = StringBuilder()
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line == FRAME) {
                        val text = ansi(frame.toString().trimEnd('\n'))
                        main.post { view.text = text }
                        frame.setLength(0)
                    } else {
                        frame.append(line).append('\n')
                    }
                }
            }
            if (frame.isNotEmpty()) {
                val text = frame.toString()
                main.post { view.text = text }
            }
        }
    }

    private fun stopGuest() {
        val p = process ?: return
        process = null
        val chroot = (application as App).runtime.chroot
        thread(isDaemon = true) {
            runCatching { chroot.process("tmux kill-session -t $SESSION 2>/dev/null; true").start().waitFor() }
            p.destroy()
        }
    }

    companion object {
        @Volatile var current: WeakReference<CoverHtopActivity>? = null
        private const val SESSION = "larry-cover"
        private const val FRAME = "__larry_frame__"
        private const val COLS = 34

        private val BG = Color.parseColor("#1a1b26")
        private val FG = Color.parseColor("#c0caf5")

        /** Tokyo Night's 16 terminal colors. */
        private val PALETTE =
            listOf(
                "#15161e", "#f7768e", "#9ece6a", "#e0af68", "#7aa2f7", "#bb9af7", "#7dcfff", "#a9b1d6",
                "#414868", "#f7768e", "#9ece6a", "#e0af68", "#7aa2f7", "#bb9af7", "#7dcfff", "#c0caf5",
            ).map { Color.parseColor(it) }

        /** htop as legend (root if there's no legend) in a detached tmux, its pane printed each second; top without tmux or htop. */
        internal fun guestScript(
            cols: Int,
            rows: Int,
        ) = """
            S=$SESSION
            cmd=htop; command -v htop >/dev/null 2>&1 || cmd=top
            if id legend >/dev/null 2>&1; then run="su - legend -c 'TERM=xterm-256color ${'$'}cmd'"; else run="TERM=xterm-256color ${'$'}cmd"; fi
            if ! command -v tmux >/dev/null 2>&1; then
              while :; do echo $FRAME; top -b -n 1 -w $cols 2>/dev/null | head -n $rows || top -b -n 1 | head -n $rows; sleep 2; done
            fi
            tmux kill-session -t ${'$'}S 2>/dev/null
            tmux new-session -d -s ${'$'}S -x $cols -y $rows "${'$'}run" || exit 1
            tmux set -t ${'$'}S status off >/dev/null 2>&1
            tmux resize-window -t ${'$'}S -x $cols -y $rows >/dev/null 2>&1
            while tmux has-session -t ${'$'}S 2>/dev/null; do echo $FRAME; tmux capture-pane -p -e -t ${'$'}S; sleep 1; done
            """.trimIndent()

        private fun xterm256(n: Int): Int =
            when {
                n < 16 -> PALETTE[n]
                n < 232 -> {
                    val i = n - 16
                    val v = { c: Int -> if (c == 0) 0 else 55 + c * 40 }
                    Color.rgb(v(i / 36), v(i / 6 % 6), v(i % 6))
                }
                else -> (8 + (n - 232) * 10).let { Color.rgb(it, it, it) }
            }

        /** Applies SGR colors and bold from tmux's `capture-pane -e`; drops other escapes. */
        internal fun ansi(s: String): CharSequence {
            val out = SpannableStringBuilder()
            var fg: Int? = null
            var bg: Int? = null
            var bold = false
            var i = 0
            fun push(text: String) {
                if (text.isEmpty()) return
                val start = out.length
                out.append(text)
                fg?.let { out.setSpan(ForegroundColorSpan(it), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                bg?.let { out.setSpan(BackgroundColorSpan(it), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                if (bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val run = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\u001b' && i + 1 < s.length && s[i + 1] == '[') {
                    var j = i + 2
                    while (j < s.length && s[j] !in '@'..'~') j++
                    if (j >= s.length) break
                    if (s[j] == 'm') {
                        push(run.toString()); run.setLength(0)
                        val codes = s.substring(i + 2, j).split(';').map { it.toIntOrNull() ?: 0 }
                        var k = 0
                        while (k < codes.size) {
                            when (val n = codes[k]) {
                                0 -> { fg = null; bg = null; bold = false }
                                1 -> bold = true
                                22 -> bold = false
                                in 30..37 -> fg = PALETTE[n - 30]
                                in 90..97 -> fg = PALETTE[n - 90 + 8]
                                39 -> fg = null
                                in 40..47 -> bg = PALETTE[n - 40]
                                in 100..107 -> bg = PALETTE[n - 100 + 8]
                                49 -> bg = null
                                38, 48 -> {
                                    val color =
                                        when (codes.getOrNull(k + 1)) {
                                            5 -> codes.getOrNull(k + 2)?.let { xterm256(it.coerceIn(0, 255)) }.also { k += 2 }
                                            2 -> Color.rgb(codes.getOrElse(k + 2) { 0 }, codes.getOrElse(k + 3) { 0 }, codes.getOrElse(k + 4) { 0 }).also { k += 4 }
                                            else -> null
                                        }
                                    if (n == 38) fg = color else bg = color
                                }
                            }
                            k++
                        }
                    }
                    i = j + 1
                    continue
                }
                if (c == '\u001b') { i += 2; continue }
                run.append(c)
                i++
            }
            push(run.toString())
            return out
        }
    }
}
