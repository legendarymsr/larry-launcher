package dev.legendarymsr.larrylauncher.linux

/** [s] as one single-quoted shell word. */
fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * Installs Portage [atoms] (binary packages first, from the binhost set up by
 * the bootstrap) unless they're already there, then drops the downloads:
 * storage on phones is tight. [sync] refreshes the ebuild tree first.
 */
fun emergeInstall(
    atoms: Collection<String>,
    sync: Boolean = false,
): String {
    // CONFIG_PROTECT_MASK=/etc/portage: autounmask writes its USE/keyword changes in place instead of as
    // ._cfg files that nothing applies. If emerge still stops, any pending ._cfg files are merged and it
    // retries once. --binpkg-respect-use builds from source where no binary package has the needed USE.
    val emerge =
        "CONFIG_PROTECT_MASK=/etc/portage emerge --ask=n --noreplace --getbinpkg --quiet-build=y --color=n " +
            "--autounmask=y --autounmask-write=y --autounmask-continue=y --autounmask-use=y --binpkg-respect-use=y " +
            atoms.joinToString(" ") { shellQuote(it) }
    return (if (sync) "$PORTAGE_SYNC && " else "") +
        "{ $emerge || { echo 'larrylauncher: applying Portage config changes and retrying once'; $APPLY_PORTAGE_CFG; $emerge; }; } && $CLEAN_PORTAGE_CACHE"
}

/** Merges ._cfg files autounmask left under /etc/portage (etc-update's "use new" for every file). */
const val APPLY_PORTAGE_CFG = "{ etc-update --automode -5 /etc/portage >/dev/null 2>&1 || true; }"

/** Syncs the ebuild tree; emerge-webrsync if rsync isn't reachable (it often isn't on mobile data). */
const val PORTAGE_SYNC = "{ emaint sync -a || emerge-webrsync; }"

/** Downloaded binary packages and sources; Portage keeps them forever otherwise. */
const val CLEAN_PORTAGE_CACHE = "rm -rf /var/cache/binpkgs/* /var/cache/distfiles/*"

/** Progress of a guest job started from the UI: installing a package. */
sealed interface JobState {
    data object Idle : JobState

    data class Working(
        val action: String,
        val log: List<String>,
    ) : JobState

    data class Failed(
        val log: List<String>,
    ) : JobState
}

/** The last [max] lines of a command's output. Progress bars redraw with carriage returns; only their latest state is kept. */
class OutputTail(
    private val max: Int = 12,
) {
    private val lines = ArrayDeque<String>()

    /** Adds [raw]; false if it was blank after dropping redrawn progress. */
    fun add(raw: String): Boolean {
        val text = raw.substringAfterLast('\r').trim()
        if (text.isEmpty()) return false
        lines.addLast(text)
        while (lines.size > max) lines.removeFirst()
        return true
    }

    fun toList() = lines.toList()

    fun clear() = lines.clear()

    override fun toString() = lines.joinToString("\n")
}

/**
 * Runs [script] in the guest to completion, blocking, feeding each output
 * line to [onLine]. Returns the exit code; a command that can't start
 * throws.
 */
fun Chroot.run(
    script: String,
    onLine: (String) -> Unit = {},
): Int {
    val process = process(script).start()
    process.inputStream.bufferedReader().forEachLine(onLine)
    return process.waitFor().also { if (it == Chroot.MOUNT_FAILED) onLine("the guest's mounts failed (see the mount error above)") }
}

/** Like [run], reporting the tail of the output to [report] as it comes. Returns success and the tail. */
fun Chroot.runJob(
    script: String,
    tail: OutputTail = OutputTail(),
    report: (List<String>) -> Unit = {},
): Pair<Boolean, OutputTail> {
    val ok =
        runCatching { run(script) { if (tail.add(it)) report(tail.toList()) } == 0 }
            .getOrElse {
                tail.add(it.message ?: it.toString())
                false
            }
    return ok to tail
}
