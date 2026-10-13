package dev.legendarymsr.larrylauncher.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Where the extras install stands; shown under Linux install and in the session notification. */
sealed interface ExtrasState {
    data object Idle : ExtrasState

    data class Working(
        val pkg: String?,
        val n: Int,
        val of: Int,
        val progress: Float?,
        val log: List<String>,
    ) : ExtrasState

    data object Done : ExtrasState

    data class Failed(
        val log: List<String>,
    ) : ExtrasState
}

/** emerge's per-package lines: ">>> Emerging (2 of 3) gui-apps/xwayland-satellite-0.7::guru", binary or not, and "Installing". */
private val EMERGE_LINE = Regex(""">>> (Emerging|Installing)(?: binary)? \((\d+) of (\d+)\) (\S+?)(?:::\S+)?$""")

/** Progress from one emerge line, or null if it isn't one: package, n, of, fraction done. */
internal fun parseEmergeLine(line: String): ExtrasState.Working? {
    val m = EMERGE_LINE.find(line.trim()) ?: return null
    val (what, n, of, pkg) = m.destructured
    val i = n.toInt()
    val total = of.toInt().coerceAtLeast(1)
    val done = (i - 1 + if (what == "Installing") 0.8f else 0f) / total
    return ExtrasState.Working(pkg, i, total, done.coerceIn(0f, 1f), emptyList())
}

/** Guest files the extras provide; any missing one means the install is pending. */
internal val EXTRAS_BINARIES = listOf("usr/bin/dillo", "usr/bin/xwayland-satellite", "usr/bin/Xwayland")

/**
 * Keywords and USE for the extras, a wait for any older launcher's silent background emerge of
 * them, the emerge itself, then the legenddots clone and link (best effort).
 */
internal val EXTRAS_SCRIPT =
    listOf(
        "mkdir -p /etc/portage/package.accept_keywords /etc/portage/package.use",
        "printf '%s\\n' ${BROWSER_KEYWORDS.joinToString(" ") { shellQuote(it) }} > /etc/portage/package.accept_keywords/larrylauncher-browser",
        "printf '%s\\n' ${BROWSER_USE_LINES.joinToString(" ") { shellQuote(it) }} > /etc/portage/package.use/larrylauncher-browser",
        "{ while pgrep -f 'emerge.*xwayland-satellite' >/dev/null 2>&1; do echo 'waiting for an earlier background emerge'; sleep 10; done; true; }",
        emergeInstall(BROWSER_PACKAGES),
        "{ command -v git >/dev/null 2>&1 && { [ -d \"\$HOME/legenddots/.git\" ] || git clone -q --depth 1 $LEGENDDOTS_URL \"\$HOME/legenddots\"; } && " +
            "sh \"\$HOME/legenddots/gentoo/chroot/dotfiles.sh\" && echo 'legenddots linked' || echo 'legenddots: skipped'; true; }",
    ).joinToString(" && ")

/**
 * The desktop's extras (Dillo, Xwayland, xwayland-satellite, legenddots), installed as a tracked
 * job under the Portage lock instead of silently at niri start. One run at a time.
 */
class DesktopExtras(
    private val chroot: Chroot,
    private val portage: Portage,
) {
    var state by mutableStateOf<ExtrasState>(ExtrasState.Idle)
        private set

    private val running = AtomicBoolean(false)

    val missing get() = EXTRAS_BINARIES.any { !File(chroot.rootfs, it).exists() }

    /** Installs the extras if niri is installed and any is missing; a no-op while a run is going. */
    suspend fun ensure(desktopInstalled: Boolean) {
        if (desktopInstalled && missing) run()
    }

    suspend fun run(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            state = ExtrasState.Working(null, 0, 0, null, listOf("waiting for other Portage jobs"))
            return portage.lock.withLock {
                withContext(Dispatchers.IO) {
                    var cur = ExtrasState.Working(null, 0, 0, null, emptyList())
                    state = cur
                    val tail = OutputTail(max = 40)
                    val ok =
                        runCatching {
                            chroot.run(EXTRAS_SCRIPT) { line ->
                                parseEmergeLine(line)?.let { cur = it.copy(log = cur.log) }
                                if (tail.add(line)) cur = cur.copy(log = tail.toList())
                                state = cur
                            } == 0
                        }.getOrElse {
                            tail.add(it.message ?: it.toString())
                            false
                        }
                    chroot.relabel()
                    state = if (ok && !missing) ExtrasState.Done else ExtrasState.Failed(tail.toList().ifEmpty { listOf("emerge stopped") })
                    ok
                }
            }
        } finally {
            running.set(false)
        }
    }
}
