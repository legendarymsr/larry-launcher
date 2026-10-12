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
 * Kept small: the terminal, a font, the keymap compiler and what the default and example
 * widgets run. Everything else is one emerge away. Changing this list needs a new id for
 * The packages step.
 */
private val PACKAGES =
    listOf(
        "gui-apps/foot", "media-fonts/dejavu", "x11-libs/libxkbcommon", "media-libs/libpulse",
        "sys-process/procps", "sys-apps/gawk", "app-misc/jq", "net-misc/curl", "dev-vcs/git",
    )

/** the Winamp-style player; upstream ships an arm64 build. */
private const val CLIAMP_VERSION = "v2.3.0"
private const val CLIAMP_URL = "https://github.com/bjarneo/cliamp/releases/download/$CLIAMP_VERSION/cliamp-linux-arm64"

/** Appended to make.conf: binary packages first, and no Portage sandboxes that need namespaces Android kernels lack. */
private val MAKE_CONF =
    """
    # larrylauncher
    FEATURES="${'$'}{FEATURES} getbinpkg binpkg-request-signature -ipc-sandbox -network-sandbox -pid-sandbox -mount-sandbox"
    EMERGE_DEFAULT_OPTS="--getbinpkg --with-bdeps=n"
    """.trimIndent()

/**
 * USE matching the official arm64 binhost so its packages are used as-is (libpulse is built
 * with X and dbus there, and pulseaudio-daemon needs libpulse[X]). libxkbcommon adds tools for
 * xkbcli (the keymap step); that one small package builds from source.
 */
private val PACKAGE_USE =
    listOf(
        "x11-libs/libxkbcommon X tools",
        "media-libs/libpulse X dbus",
        "media-sound/pulseaudio-daemon X dbus",
    )

/** Rewritten by configure and again before every package install, so a retry re-applies it. */
internal val PACKAGE_USE_SCRIPT =
    "mkdir -p /etc/portage/package.use && printf '%s\\n' " +
        PACKAGE_USE.joinToString(" ") { shellQuote(it) } + " > /etc/portage/package.use/larrylauncher"

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
    Agents("install coding agents"),
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
class Bootstrap(
    private val context: Context,
    private val chroot: Chroot,
    private val agents: AgentStore,
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

    /** The agents picked when installing Linux, one bin per line; its existence means an install was started. */
    private val choiceFile get() = File(chroot.filesDir, "gentoo-setup-agents")

    /** The coding agents setup installs, in the order they were listed. */
    val chosenAgents: List<Agent>
        get() = runCatching { choiceFile.readLines() }.getOrDefault(emptyList()).mapNotNull { bin -> AGENTS.find { it.bin == bin } }

    fun choose(picked: List<Agent>) {
        chroot.filesDir.mkdirs()
        choiceFile.writeText(picked.joinToString("") { it.bin + "\n" })
    }

    private var stage = Stage.Download
    private var step = ""

    private val steps =
        listOf(
            Step("rootfs", Stage.Download, "Downloading Gentoo", network = true) { installRootfs() },
            Step("signature-1", Stage.Configure, "Checking the stage3 signature", network = false, optional = true) { verifySignature() },
            Step("etc-1", Stage.Configure, "Configuring the system", network = false) {
                writeHostConfig()
                guest(configureScript())
            },
            Step("sync-1", Stage.Configure, "Fetching the Portage tree", network = true) { guest("emerge-webrsync") },
            Step("getuto-1", Stage.Configure, "Trusting the binhost's key", network = true) { guest("getuto") },
            Step("packages-1", Stage.Packages, "Installing packages", network = true) { guest("$PACKAGE_USE_SCRIPT && " + emergeInstall(PACKAGES)) },
            Step("keymap-1", Stage.Packages, "Compiling keymap", network = false, isDone = { chroot.keymap.exists() }) { compileKeymap() },
            Step("cliamp-$CLIAMP_VERSION", Stage.Packages, "Installing cliamp", network = true) { installCliamp() },
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
            } catch (e: Exception) {
                Log.e(TAG, "setup failed", e)
                _state.value = LinuxState.Failed(stage, e.message ?: e.toString(), log.toList())
            }
        }

    /** Starts installing Linux with [picked] coding agents; see [run]. */
    suspend fun install(picked: List<Agent>) {
        choose(picked)
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
     * retry picks up at the failed step. Agents are extras: the system
     * works without them, and settings can retry.
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
        installAgents()
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
            val todo = backlog.filterNot { it.done() }
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

    /**
     * Installs the agents picked on the welcome screen. A failed agent doesn't
     * fail setup: it can be installed again from settings.
     */
    private suspend fun installAgents() {
        val chosen = chosenAgents
        for (agent in chosen) {
            if (agents.isInstalled(agent)) continue
            try {
                report(Stage.Agents, "Installing ${agent.name}")
                guest(agents.installScript(agent))
                agents.onInstalled(agent)
            } catch (e: IllegalStateException) {
                log.add("${agent.name} didn't install; try again in settings › coding agents")
            }
        }
        chosen.firstOrNull { agents.isInstalled(it) }?.let(agents::choose)
    }

    private fun report(
        stage: Stage,
        step: String,
        progress: Float? = null,
    ) {
        this.stage = stage
        this.step = step
        if (journal.setupComplete && _state.value == LinuxState.Installed) return
        _state.value = LinuxState.Installing(stage, step, progress, log.toList())
    }

    /** Always a fresh unpack: this step only runs until one completed, so a rootfs here is a half-unpacked one. */
    private fun installRootfs() {
        // Unpacking replaces the rootfs: the user's home, packages and agents. Only ever before a setup finished.
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
            "printf '%s\\n' ${shellQuote(MAKE_CONF)} >> /etc/portage/make.conf",
        ).joinToString(" && ")

    private fun compileKeymap() {
        guest(
            "xkbcli compile-keymap --layout us > /tmp/keymap.xkb || " +
                "/usr/libexec/xkbcommon/xkbcli-compile-keymap --layout us > /tmp/keymap.xkb",
        )
        // Clients mmap the keymap and parse it as a C string.
        writeAtomically(chroot.keymap, chroot.hostFile("/tmp/keymap.xkb").readBytes() + 0)
    }

    private fun installCliamp() {
        val bytes = URL(CLIAMP_URL).openStream().use { it.readBytes() }
        // /usr/local/bin is root's: written to the guest's /tmp, installed from inside.
        writeAtomically(chroot.hostFile("/tmp/cliamp"), bytes, executable = true)
        guest("install -Dm755 /tmp/cliamp /usr/local/bin/cliamp && rm -f /tmp/cliamp")
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
