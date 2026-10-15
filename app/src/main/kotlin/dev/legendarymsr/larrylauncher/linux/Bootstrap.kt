package dev.legendarymsr.larrylauncher.linux

import android.content.Context
import android.util.Log
import dev.legendarymsr.larrylauncher.upgrade.Provisioner
import dev.legendarymsr.larrylauncher.upgrade.StepJournal
import dev.legendarymsr.larrylauncher.upgrade.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "bootstrap"
/** Gentoo's weekly arm64 OpenRC stage3; the text file names the current tarball. */
private const val AUTOBUILDS = "https://distfiles.gentoo.org/releases/arm64/autobuilds/"
private const val LATEST = AUTOBUILDS + "latest-stage3-arm64-openrc.txt"
private const val TARBALL = "gentoo-stage3-arm64.tar.xz"

/** Gentoo's official arm64 binhost: most installs are downloads, not builds. */
private const val BINHOST = "https://distfiles.gentoo.org/releases/arm64/binpackages/23.0/arm64/"

/** Attempts per network step before setup (or an update) reports a failure. */
private const val NETWORK_TRIES = 3

/**
 * Kept small: the terminal, a font, the keymap compiler and a few essentials. No audio
 * stack: pulseaudio's binhost builds pull alsa-plugins USE combinations that fail to resolve. Everything else is one emerge away. Changing this list needs a new id for
 * The packages step.
 */
internal val PACKAGES =
    listOf(
        "gui-apps/foot", "media-fonts/dejavu", "x11-libs/libxkbcommon",
        "sys-process/procps", "net-misc/curl", "dev-vcs/git",
    )

/** Appended to make.conf: binary packages first, and no Portage sandboxes that need namespaces Android kernels lack. */
internal fun makeConf(jobs: Int) =
    """
    # larrylauncher
    FEATURES="${'$'}{FEATURES} getbinpkg binpkg-request-signature -ipc-sandbox -network-sandbox -pid-sandbox -mount-sandbox"
    EMERGE_DEFAULT_OPTS="--getbinpkg --with-bdeps=n"
    MAKEOPTS="-j$jobs -l$jobs"
    """.trimIndent()

/** Parallel compile jobs: one per 2 GiB of RAM, at most one per core, so big C++ builds (mesa, Rust) don't get OOM-killed. */
internal fun makeJobs(memKb: Long, cpus: Int): Int = (memKb / (2L * 1024 * 1024)).toInt().coerceIn(1, cpus.coerceAtLeast(1))

private fun deviceMakeJobs(): Int {
    val kb = runCatching { java.io.File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }.split(Regex("\\s+"))[1].toLong() }.getOrDefault(4L * 1024 * 1024)
    return makeJobs(kb, java.lang.Runtime.getRuntime().availableProcessors())
}

/**
 * USE matching the official arm64 binhost so its packages are used as-is. libxkbcommon adds tools for
 * xkbcli (the keymap step); that one small package builds from source.
 */
internal val PACKAGE_USE =
    listOf(
        "x11-libs/libxkbcommon X tools",
        // Qt apps (nvim-qt...) need the wayland platform plugin, which Qt 6.11 builds into qtbase;
        // these match the binhost's qtbase/gtk+ build that has it, so it still downloads.
        "dev-qt/qtbase X cups gtk icu libproxy opengl vulkan wayland",
        "x11-libs/gtk+ X cups wayland introspection",
    )

/** Keywords for the packages screen's curated apps that arm64 stable lacks. */
internal val PACKAGE_KEYWORDS =
    listOf(
        "app-editors/micro ~arm64",
        "dev-vcs/lazygit ~amd64 ~arm64",
        "app-editors/neovim-qt ~amd64 ~arm64",
    )

/** Rewritten by configure and again before every package install, so a retry re-applies it. */
/**
 * Portage's binpkg keyring, rebuilt from scratch by getuto as the guest's root. getuto only
 * initialises (imports + lsigns the Gentoo release keys) when /etc/portage/gnupg is absent;
 * an existing partial keyring from an earlier failed run is just "refreshed". Its own perms
 * (dir 0755, files readable) are kept: Portage verifies as the portage user, so a 0700 root
 * keyring reads as empty (NO_PUBKEY). Fails unless the binhost key is present.
 */
internal const val GETUTO_SCRIPT =
    "test -s /usr/share/openpgp-keys/gentoo-release.asc && " +
        "chmod 1777 /tmp && rm -rf /etc/portage/gnupg /etc/portage/gnupg.getuto.tmp && " +
        "getuto && " +
        "gpg --homedir /etc/portage/gnupg --batch --list-keys 2C44695DB9F6043D >/dev/null && " +
        "chown -R root:root /etc/portage/gnupg && chmod -R go+rX /etc/portage/gnupg"

/** Portage's build/temp dirs with the owners and modes it expects, since /var/tmp may have come out wrong. */
internal const val PORTAGE_DIRS_SCRIPT =
    "mkdir -p /var/tmp/portage /var/cache/binpkgs && chmod 1777 /var/tmp && " +
        "chown portage:portage /var/tmp/portage && chmod 775 /var/tmp/portage && " +
        "{ [ ! -d /etc/portage/gnupg ] || { chown -R root:root /etc/portage/gnupg && chmod -R go+rX /etc/portage/gnupg; }; }"

/**
 * Deletes downloaded binpkgs whose signature didn't verify (a failed earlier run leaves them
 * cached and Portage would reject them again instead of re-fetching). Verification stays on:
 * these get downloaded and checked again.
 */
internal const val DROP_BAD_BINPKGS_SCRIPT =
    "{ for f in \$(find /var/cache/binpkgs -name '*.gpkg.tar' -newer /var/lib/larrylauncher/stage3.DIGESTS 2>/dev/null); do " +
        "tar -tf \"\$f\" >/dev/null 2>&1 || rm -f \"\$f\"; done; " +
        "[ ! -f /var/lib/larrylauncher/bad-binpkgs ] || xargs -r rm -f < /var/lib/larrylauncher/bad-binpkgs; rm -f /var/lib/larrylauncher/bad-binpkgs; true; }"

private val BAD_BINPKG = Regex("""Invalid binary package:?\s*'?(/var/cache/binpkgs/[^'\s]+)""")

internal val PACKAGE_USE_SCRIPT =
    "mkdir -p /etc/portage/package.use && rm -f /etc/portage/package.use/zz-autounmask && printf '%s\\n' " +
        PACKAGE_USE.joinToString(" ") { shellQuote(it) } + " > /etc/portage/package.use/larrylauncher && " +
        "mkdir -p /etc/portage/package.accept_keywords && printf '%s\\n' " +
        PACKAGE_KEYWORDS.joinToString(" ") { shellQuote(it) } + " > /etc/portage/package.accept_keywords/larrylauncher"

/**
 * Every USE/keyword line the app writes. Its hash is the guest's config version
 * (/var/lib/larrylauncher/use-version): when an app update changes it, installed packages
 * whose USE changed (qtbase gaining wayland...) are rebuilt, see [USE_REBUILD_SCRIPT].
 */
internal val USE_VERSION: String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest((PACKAGE_USE + PACKAGE_KEYWORDS + DESKTOP_USE_LINES).joinToString("\n").toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)

internal const val USE_VERSION_FILE = "/var/lib/larrylauncher/use-version"

/** Package atoms named in the app's USE lines; the rebuild only touches the installed ones. */
internal val USE_ATOMS = (PACKAGE_USE + DESKTOP_USE_LINES).map { it.substringBefore(' ') }.distinct()

/**
 * Re-applies the app's Portage config (the desktop's too, once it's installed), then rebuilds
 * installed packages whose USE changed: `emerge -1u --changed-use` on those atoms with the same
 * autounmask/binpkg flags as installs. Not all of @world: -u there would also pull every pending upgrade.
 * Stamps [USE_VERSION] last, so a failure retries.
 */
internal val USE_REBUILD_SCRIPT =
    "$PORTAGE_DIRS_SCRIPT && $DROP_BAD_BINPKGS_SCRIPT && $PACKAGE_USE_SCRIPT && " +
        "{ [ ! -f /etc/portage/package.use/larrylauncher-desktop ] || printf '%s\\n' $DESKTOP_USE > /etc/portage/package.use/larrylauncher-desktop; } && " +
        "LL_ATOMS=\$(for a in ${USE_ATOMS.joinToString(" ")}; do portageq has_version / \$a >/dev/null 2>&1 && echo \$a; done; true) && " +
        "{ [ -z \"\$LL_ATOMS\" ] || { echo \"larrylauncher: rebuilding for changed USE: \"\$LL_ATOMS; " +
        emergeInstall(listOf("\$LL_ATOMS"), mode = "--oneshot --update --changed-use") + "; }; } && " +
        "mkdir -p /var/lib/larrylauncher && echo $USE_VERSION > $USE_VERSION_FILE"

/** What the bootstrap installs; the packages screen doesn't offer to remove these. */
val CORE_PACKAGES = PACKAGES.toSet()

/** The phases of a fresh install, in order, as the setup screen lists them. */
enum class Stage(
    val label: String,
) {
    Download("download gentoo"),
    Extract("unpack the system"),
    Configure("configure portage"),
    Packages("install the default apps"),
}

/** Where the optional Linux system stands. The launcher works in every state; Linux features only when [Installed]. */
sealed interface LinuxState {
    data object Checking : LinuxState

    /** Never installed, or removed: nothing of it is on disk. */
    data object NotInstalled : LinuxState

    data class Installing(
        val stage: Stage,
        val step: String,
        val progress: Float?,
        val log: List<String>,
    ) : LinuxState

    /** An install, or an update that must finish before Linux runs, stopped; [Bootstrap.run] retries it. */
    data class Failed(
        val stage: Stage,
        val message: String,
        val log: List<String>,
    ) : LinuxState

    data object Installed : LinuxState

    data object Removing : LinuxState

    companion object {
        /**
         * What a state shows once the job that owned it is gone (an exception escaped it, it was
         * cancelled, or the process died mid-step): Checking or Installing become Failed, so the
         * panel always has the step list and a retry.
         */
        fun stopped(
            state: LinuxState,
            reason: String,
        ): LinuxState =
            when (state) {
                Checking -> Failed(Stage.Download, reason, emptyList())
                is Installing -> Failed(state.stage, "${state.step}: $reason", state.log)
                else -> state
            }
    }
}

/**
 * One idempotent change to the rootfs. Once it succeeds its [id] is recorded
 * and it doesn't run again; to bring a changed step to existing installs,
 * give it a new id. [network] steps of an update run in the background, so
 * an offline phone still reaches the home screen. [isDone], when set, reads
 * The step's state from disk instead of the journal.
 */
private class Step(
    val id: String,
    val stage: Stage,
    val label: String,
    val network: Boolean,
    /** A failure doesn't stop a fresh install: the system works without it, and it's retried later. */
    val optional: Boolean = false,
    val isDone: (() -> Boolean)? = null,
    val run: suspend () -> Unit,
)

/**
 * Sets up the Gentoo stage3 rootfs and keeps it current. A fresh install
 * runs every [steps] entry behind the setup screen; after an app update only
 * The new ones run, see [Step].
 */
/**
 * Creates the user legend (password legend) unless it exists, so a changed password is kept, and
 * installs doas for :wheel. doas needs setuid, which works because [Chroot] remounts the rootfs suid.
 */
internal val USER_SCRIPT =
    "{ id legend >/dev/null 2>&1 || { useradd -m -G wheel,audio,video -s /bin/bash legend && echo 'legend:legend' | chpasswd; }; } && " +
        "{ command -v doas >/dev/null 2>&1 || " + emergeInstall(listOf("app-admin/doas")) + " || echo 'larrylauncher: warning: doas did not install'; } ; " +
        "if [ -e /usr/bin/doas ]; then printf 'permit persist :wheel\\n' > /etc/doas.conf && chown root:root /etc/doas.conf && chmod 0400 /etc/doas.conf && " +
        "chown root:root /usr/bin/doas && chmod u+s /usr/bin/doas; " +
        "awk '\$2 == \"/\" && \$4 ~ /nosuid/ { f = 1 } END { exit !f }' /proc/self/mounts 2>/dev/null && " +
        "echo 'larrylauncher: warning: the rootfs is nosuid, so doas cannot elevate (log in as root instead)'; fi; " +
        "id legend >/dev/null 2>&1"

class Bootstrap(
    private val context: Context,
    private val chroot: Chroot,
    private val portage: Portage,
    /** The guest's files; see [Provisioner]. */
    private val provisioner: Provisioner,
) {
    private val _state = MutableStateFlow<LinuxState>(LinuxState.Checking)
    val state = _state.asStateFlow()

    val isInstalled get() = _state.value == LinuxState.Installed

    /** An install was started (and may still need to finish), or one finished. */
    val wanted get() = journal.setupComplete || choiceFile.exists()

    /** Update steps running after the home screen is up; Idle when there are none. */
    private val _updates = MutableStateFlow<JobState>(JobState.Idle)
    val updates = _updates.asStateFlow()

    private val log = OutputTail(200)
    private val journal = StepJournal(File(chroot.filesDir, "gentoo-steps.json"))

    /** Written when an install starts; its existence means one was started. (Old name kept for existing installs.) */
    private val choiceFile get() = File(chroot.filesDir, "gentoo-setup-agents")

    private var stage = Stage.Download
    private var step = ""

    private val steps =
        listOf(
            Step("rootfs", Stage.Download, "Downloading Gentoo", network = true) { installRootfs() },
            Step("signature-1", Stage.Configure, "Checking the stage3 signature", network = false, optional = true) { verifySignature() },
            Step("etc-2", Stage.Configure, "Configuring the system", network = false) {
                writeHostConfig()
                guest(configureScript())
            },
            Step("sync-1", Stage.Configure, "Fetching the Portage tree", network = true) { guest("emerge-webrsync") },
            Step("getuto-3", Stage.Configure, "Trusting the binhost's key", network = true) { guest(GETUTO_SCRIPT) },
            Step("packages-3", Stage.Packages, "Installing packages", network = true) {
                guest("$PORTAGE_DIRS_SCRIPT && $DROP_BAD_BINPKGS_SCRIPT && $PACKAGE_USE_SCRIPT && " + emergeInstall(PACKAGES))
            },
            // Id carries the version so the journal never skips a new one; the guest stamp is the truth.
            Step("use-$USE_VERSION", Stage.Packages, "Rebuilding for changed USE flags", network = true, isDone = {
                "use-$USE_VERSION" in journal || runCatching { chroot.hostFile(USE_VERSION_FILE).readText().trim() == USE_VERSION }.getOrDefault(false)
            }) { guest(USE_REBUILD_SCRIPT) },
            // The default non-root user, with doas for root; also for installs from before it existed.
            Step("user-1", Stage.Packages, "Adding the user legend", network = true) { guest(USER_SCRIPT) },
            // legenddots (nvim, tmux, foot, fuzzel, fastfetch, dillo) for root and legend, terminal or desktop.
            Step("legenddots-1", Stage.Packages, "Linking legenddots", network = true) { guest(LEGENDDOTS_SCRIPT) },
            Step("keymap-1", Stage.Packages, "Compiling keymap", network = false, isDone = { chroot.keymap.exists() }) { compileKeymap() },
        )

    private fun Step.done() = isDone?.invoke() ?: (id in journal)

    /**
     * Runs the step; one that needs the network gets a few tries, since phone connections drop.
     * Retrying is cheap: Portage keeps what it already downloaded, and downloads resume.
     */
    private suspend fun Step.attempt() {
        repeat(if (network) NETWORK_TRIES - 1 else 0) { n ->
            try {
                return run()
            } catch (e: GuestMountException) {
                // Not a flaky connection: the same mounts would fail again.
                throw e
            } catch (e: Exception) {
                log.add("${e.message}; trying again (${n + 2} of $NETWORK_TRIES)")
                kotlinx.coroutines.delay(5_000L * (n + 1))
            }
        }
        run()
    }

    /**
     * Brings Linux to [LinuxState.Installed] if it's wanted: updates an
     * installed system, or continues an install that was started (also
     * after the app was killed during it). Otherwise reports NotInstalled.
     */
    suspend fun run() =
        withContext(Dispatchers.IO) {
            try {
                when {
                    journal.setupComplete -> {
                        chroot.requireRoot()
                        update()
                    }
                    !choiceFile.exists() -> {
                        _state.value = LinuxState.NotInstalled
                        // Left by a removal that didn't finish (the app was killed, or a file resisted).
                        if (chroot.rootfs.exists()) runCatching { chroot.deleteRootfs() }.onFailure { Log.e(TAG, "leftover rootfs", it) }
                        return@withContext
                    }
                    else -> {
                        chroot.requireRoot()
                        setup()
                    }
                }
                _state.value = LinuxState.Installed
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = LinuxState.stopped(_state.value, "stopped before it finished")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "setup failed", e)
                _state.value = LinuxState.Failed(stage, e.message ?: e.toString(), log.toList())
            }
        }

    /** The job driving [state] ended without settling it (see [LinuxState.stopped]). */
    fun markStopped(reason: String) {
        _state.value = LinuxState.stopped(_state.value, reason)
    }

    /** Starts installing Linux; see [run]. */
    suspend fun install() {
        chroot.filesDir.mkdirs()
        choiceFile.writeText("")
        run()
    }

    /**
     * Deletes the rootfs and what we recorded about it. Callers stop guest
     * processes first. Launcher data (themes, widgets) lives outside it and stays.
     */
    suspend fun remove() =
        withContext(Dispatchers.IO) {
            _state.value = LinuxState.Removing
            backlog = emptyList()
            portage.lock.withLock {
                try {
                    chroot.deleteRootfs()
                } catch (e: Exception) {
                    // Whatever is left can't run; a later install unpacks over a fresh directory anyway.
                    Log.e(TAG, "rootfs not fully deleted", e)
                }
                journal.reset()
                provisioner.forgetAll()
                choiceFile.delete()
                chroot.keymap.delete()
                context.cacheDir.listFiles { f -> f.name.startsWith(TARBALL) }?.forEach { it.delete() }
                log.clear()
            }
            _state.value = LinuxState.NotInstalled
        }

    /**
     * A fresh install: every step, in order. A failure stops here and a
     * retry picks up at the failed step.
     */
    private suspend fun setup() {
        for (step in steps) {
            if (step.done()) continue
            report(step.stage, step.label)
            try {
                step.attempt()
                journal.mark(step.id)
            } catch (e: Exception) {
                if (!step.optional) throw e
                log.add("${step.label} failed: ${e.message}; retry in settings")
            }
        }
        chroot.relabel(whole = true)
        journal.completeSetup()
    }

    /**
     * After an app update: offline steps run before the home screen, so it
     * never shows a half-configured system; from the first step that needs
     * The network on, the rest continue in the background (see [updates]).
     */
    private suspend fun update() {
        val pending = steps.filterNot { it.done() }
        val blocking = pending.takeWhile { !it.network }
        for (step in blocking) {
            report(step.stage, step.label)
            step.run()
            journal.mark(step.id)
        }
        backlog = pending.drop(blocking.size)
    }

    private var backlog: List<Step> = emptyList()

    private val backlogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs update steps that need the network; call once the home screen is up. Safe to call again to retry. */
    suspend fun runBacklog() {
        if (!backlogRunning.compareAndSet(false, true)) return
        try {
            runSteps()
        } finally {
            backlogRunning.set(false)
        }
    }

    private suspend fun runSteps() =
        withContext(Dispatchers.IO) {
            // Every pending step, not just the ones left at startup: a retry also picks up
            // steps that failed before the home screen or after an app update.
            val todo = (backlog + steps.filter { it.network }).distinct().filterNot { it.done() }
            if (todo.isEmpty()) return@withContext
            for (step in todo) {
                _updates.value = JobState.Working(step.label, emptyList())
                try {
                    // Package steps run emerge; nothing else may hold its lock meanwhile.
                    portage.lock.withLock { step.attempt() }
                    journal.mark(step.id)
                } catch (e: Exception) {
                    Log.w(TAG, "update step ${step.id} failed", e)
                    _updates.value = JobState.Failed(log.toList().takeLast(12) + (e.message ?: e.toString()))
                    return@withContext
                }
            }
            _updates.value = JobState.Idle
        }

    private fun report(
        stage: Stage,
        step: String,
        progress: Float? = null,
    ) {
        this.stage = stage
        this.step = step
        if (journal.setupComplete && _state.value == LinuxState.Installed) {
            (_updates.value as? JobState.Working)?.let { _updates.value = JobState.Working(it.action, log.toList().takeLast(20)) }
            return
        }
        _state.value = LinuxState.Installing(stage, step, progress, log.toList())
    }

    /** Always a fresh unpack: this step only runs until one completed, so a rootfs here is a half-unpacked one. */
    private fun installRootfs() {
        // Unpacking replaces the rootfs: the user's home and packages. Only ever before a setup finished.
        check(!journal.setupComplete) { "refusing to replace a set-up system" }
        val tarball = obtainTarball()
        try {
            extract(tarball)
        } catch (e: Exception) {
            // A truncated or bogus download (a captive portal's page) would fail every retry otherwise.
            if (tarball.parentFile == context.cacheDir) tarball.delete()
            throw e
        }
        tarball.delete() // a few hundred MB we no longer need
    }

    /**
     * Prefers a tarball pushed via adb to the app's external files dir; downloads otherwise.
     * An interrupted download resumes where it stopped, as long as the file on the server is
     * still the same one (If-Range): "latest" moves, and two versions mustn't be spliced.
     */
    private fun obtainTarball(): File {
        val external = context.getExternalFilesDir(null)?.let { File(it, TARBALL) }
        if (external != null && external.exists()) return external
        val target = File(context.cacheDir, TARBALL)
        if (target.exists()) return target
        val partial = File(context.cacheDir, "$TARBALL.part")
        val validator = File(context.cacheDir, "$TARBALL.version")
        report(Stage.Download, "Downloading Gentoo", 0f)
        val url = latestStage3()
        val digests = String(dev.legendarymsr.larrylauncher.httpGet("$url.DIGESTS", readTimeout = 30_000))
        digestsFile.writeText(digests)
        val expected = sha512Of(digests, url.substringAfterLast('/')) ?: error("no SHA512 for the stage3 in its DIGESTS")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val have = if (partial.exists() && validator.exists()) partial.length() else 0L
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=$have-")
            conn.setRequestProperty("If-Range", validator.readText())
        }
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (conn.responseCode !in 200..299) error("download failed: HTTP ${conn.responseCode}")
        val start = if (resumed) have else 0L
        val total = conn.contentLengthLong.let { if (it > 0) it + start else -1L }
        (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))?.let(validator::writeText) ?: validator.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = start
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 4 shl 20) {
                        lastReport = done
                        report(
                            Stage.Download,
                            if (total > 0) "${done shr 20} of ${total shr 20} MB" else "${done shr 20} MB",
                            if (total > 0) done.toFloat() / total else null,
                        )
                    }
                }
            }
        }
        // Only a complete download is kept for a retry.
        check(total <= 0 || partial.length() == total) { "The download was cut short; retry to continue it" }
        val got = sha512(partial)
        if (got != expected) {
            partial.delete()
            validator.delete()
            error("The stage3 doesn't match its DIGESTS (sha512 $got); retry to download it again")
        }
        partial.renameTo(target)
        validator.delete()
        return target
    }

    /** The DIGESTS file of the stage3 we unpacked, clearsigned by Gentoo's release key. */
    private val digestsFile get() = File(chroot.filesDir, "stage3.DIGESTS")

    /** The current tarball's URL, from the clearsigned latest-*.txt: its first `path size` line. */
    private fun latestStage3(): String {
        val text = String(dev.legendarymsr.larrylauncher.httpGet(LATEST, readTimeout = 30_000))
        val path =
            text.lineSequence().map { it.trim() }.firstOrNull { !it.startsWith("#") && !it.startsWith("-") && it.substringBefore(' ').endsWith(".tar.xz") }
                ?.substringBefore(' ') ?: error("no stage3 listed in $LATEST")
        return AUTOBUILDS + path
    }

    /** The hex SHA512 for [name] in a DIGESTS file, from its `# SHA512 HASH` section (BLAKE2B hashes are as long). */
    private fun sha512Of(
        digests: String,
        name: String,
    ): String? {
        var inSha512 = false
        for (line in digests.lines()) {
            val t = line.trim()
            if (t.startsWith("# ")) {
                inSha512 = t.startsWith("# SHA512")
                continue
            }
            if (!inSha512) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size == 2 && parts[1] == name && parts[0].length == 128) return parts[0].lowercase()
        }
        return null
    }

    private fun sha512(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unpacks as root with Magisk's busybox, so owners, modes, setuid bits and device
     * nodes survive (the app can't create them), then hands the launcher back read access.
     */
    private fun extract(tarball: File) {
        report(Stage.Extract, "Unpacking as root", null)
        // Nothing we recorded about the old rootfs holds for this one.
        journal.reset()
        provisioner.forgetAll()
        chroot.deleteRootfs()
        val root = shellQuote(chroot.rootfs.path)
        val script =
            "bb=/data/adb/magisk/busybox; [ -x \$bb ] || bb=busybox; mkdir -p $root && cd $root && " +
                "\$bb xz -dc ${shellQuote(tarball.path)} | \$bb tar -xpf - && " +
                "mkdir -p var/lib/larrylauncher && cp ${shellQuote(digestsFile.path)} var/lib/larrylauncher/stage3.DIGESTS"
        val code = chroot.rootShell(script) { line -> if (log.add(line)) report(Stage.Extract, "Unpacking as root") }
        check(code == 0) { "unpacking the stage3 failed (exit $code)" }
        chroot.relabel(whole = true)
    }

    /**
     * The DIGESTS we checked the tarball against, verified with the release key the stage3
     * ships. The hash was already checked over HTTPS; this adds Gentoo's signature on top.
     */
    private fun verifySignature() =
        runCatching {
            guest(
            "export GNUPGHOME=/tmp/stage3-gpg && mkdir -p -m 700 \$GNUPGHOME && " +
                "gpg --batch --import /usr/share/openpgp-keys/gentoo-release.asc && " +
                "gpg --batch --verify /var/lib/larrylauncher/stage3.DIGESTS",
            )
        }.onFailure {
            // Optional: the sha512 was already checked over HTTPS.
            Log.w(TAG, "stage3 signature not checked", it)
            log.add("stage3 signature not checked: ${it.message}")
        }.getOrThrow()

    /** DNS, hosts, locale and Portage's binhost; idempotent, run as the guest's root. */
    private fun configureScript() =
        listOf(
            "printf '127.0.0.1 localhost\\n::1 localhost\\n' > /etc/hosts",
            "echo 'LANG=C.UTF-8' > /etc/locale.conf",
            "mkdir -p /etc/portage/binrepos.conf /etc/portage/package.use",
            "printf '[gentoobinhost]\\npriority = 9999\\nsync-uri = $BINHOST\\n' > /etc/portage/binrepos.conf/gentoobinhost.conf",
            PACKAGE_USE_SCRIPT,
            "sed -i '/^# larrylauncher\$/,\$d' /etc/portage/make.conf",
            "printf '%s\\n' ${shellQuote(makeConf(deviceMakeJobs()))} >> /etc/portage/make.conf",
        ).joinToString(" && ")

    private fun compileKeymap() {
        guest(
            "xkbcli compile-keymap --layout us > /tmp/keymap.xkb || " +
                "/usr/libexec/xkbcommon/xkbcli-compile-keymap --layout us > /tmp/keymap.xkb",
        )
        // Clients mmap the keymap and parse it as a C string.
        writeAtomically(chroot.keymap, chroot.hostFile("/tmp/keymap.xkb").readBytes() + 0)
    }

    /** Paths from Portage's "Invalid binary package" errors, deleted before the next emerge so they're re-fetched. */
    private fun rememberBadBinpkgs(lines: List<String>) {
        val bad = lines.mapNotNull { BAD_BINPKG.find(it)?.groupValues?.get(1) }.filter { it.startsWith("/var/cache/binpkgs/") }.distinct()
        if (bad.isEmpty()) return
        runCatching {
            val f = chroot.hostFile("/var/lib/larrylauncher/bad-binpkgs")
            chroot.rootShell("mkdir -p ${shellQuote(f.parent)} && printf '%s\\n' ${bad.joinToString(" ") { shellQuote(it) }} >> ${shellQuote(f.path)}")
        }.onFailure { Log.w(TAG, "remembering bad binpkgs", it) }
    }

    private fun guest(script: String) {
        log.add("$ $script")
        val out = OutputTail(20)
        val code =
            chroot.run(script) { line ->
                out.add(line)
                if (log.add(line)) report(stage, step)
            }
        if (code == Chroot.MOUNT_FAILED) {
            throw GuestMountException(out.toList().filter { it.startsWith("larrylauncher: mount") }.ifEmpty { out.toList() }.joinToString("\n"))
        }
        if (code != 0) rememberBadBinpkgs(out.toList() + log.toList())
        check(code == 0) { "`$script` failed (exit $code):\n" + out.toList().takeLast(8).joinToString("\n") }
    }

    /**
     * DNS for the guest, written from the host side as root: needs none of the guest's mounts.
     * The stage3's resolv.conf is a symlink, so it's removed first. An empty fstab goes in too.
     */
    private fun writeHostConfig() {
        val etc = shellQuote(File(chroot.rootfs, "etc").path)
        val code =
            chroot.rootShell(
                "mkdir -p $etc && rm -f $etc/resolv.conf && printf 'nameserver 8.8.8.8\\nnameserver 1.1.1.1\\n' > $etc/resolv.conf && " +
                    "chmod 644 $etc/resolv.conf && { [ -e $etc/fstab ] || : > $etc/fstab; }",
            ) { log.add(it) }
        check(code == 0) { "writing the guest's resolv.conf failed (exit $code)" }
    }
}

/** A guest launch whose mounts failed (exit [Chroot.MOUNT_FAILED]); [message] is the failing mount and its error. */
class GuestMountException(
    message: String,
) : IllegalStateException(message)
