package dev.legendarymsr.larrylauncher.linux

import android.content.Context
import android.util.Log
import dev.legendarymsr.larrylauncher.LauncherData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Paths and process launching for the Gentoo rootfs, a real chroot started as
 * root through Magisk `su`. The app itself is untrusted_app and can't chroot:
 * every guest command is a root child that unshares its mount namespace, binds
 * only what the guest needs and chroots. Guest uid 0 is the phone's real root.
 */
class Chroot(
    private val context: Context,
) {
    val filesDir: File = context.filesDir

    /** A fresh Gentoo stage3. Only this directory is ever written or deleted. */
    val rootfs = File(filesDir, "gentoo")
    val tmpDir = File(filesDir, "tmp")

    /** Bound to XDG_RUNTIME_DIR inside the guest; holds the Wayland socket and the bridges' FIFOs. */
    val runtimeDir = File(filesDir, "xdg")
    val waylandSocket = File(runtimeDir, "wayland-0")

    /** Scale-1 socket for nested niri, which applies the display density itself. */
    val niriSocket = File(runtimeDir, "wayland-niri")
    val keymap = File(filesDir, "keymap.xkb")

    /** The guest user's home, /root, and the launcher's places in it. */
    val home = File(rootfs, "root")
    val applications = File(home, ".local/share/applications")
    val launcherConfig = File(home, ".config/larrylauncher")

    /** Stand-ins for /proc files Android hides from apps; unused as root, kept for the bridges that read it. */
    val procShim = ProcShim(File(filesDir, "proc"))

    /** How guest apps render OpenGL and Vulkan: in software, or on the GPU once [Gpu] turns it on. */
    @Volatile var graphicsEnv: List<String> = Gpu.SOFTWARE_ENV

    /** Launcher data the guest sees in its home, kept outside the rootfs; see [LauncherData]. */
    private val dataBinds =
        listOf(
            "$GUEST_HOME/.local/share/larrylauncher" to LauncherData.themesDir(context),
            "$GUEST_HOME/.config/larrylauncher/widgets" to LauncherData.widgetsDir(context),
            "$GUEST_HOME/.config/larrylauncher/themes" to LauncherData.userThemesDir(context),
            "$GUEST_HOME/.config/larrylauncher/backgrounds" to LauncherData.userBackgroundsDir(context),
        )

    /** Android's Chinese, Japanese and Korean font, bound read-only into the guest when the device has it. */
    private val cjkFont = File("/system/fonts/NotoSansCJK-Regular.ttc")

    /** Host directories bound over guest paths by [command]. Never all of /data: only these. */
    private val binds = listOf("/tmp" to tmpDir, GUEST_RUNTIME_DIR to runtimeDir) + dataBinds

    private val _root = MutableStateFlow<RootState>(RootState.Unknown)

    /**
     * Whether Magisk granted root. Without it Linux stays off: there is no
     * unprivileged fallback. Only a grant is remembered; a denial is asked again.
     */
    val root: StateFlow<RootState> get() = _root

    val rootGranted get() = _root.value == RootState.Granted

    private val rootLock = Any()

    /**
     * Runs `su -c id` so Magisk shows its grant dialog, blocking until it's answered
     * (call it off the main thread). Re-runs su every time unless root was already
     * granted. True if su ran as uid 0.
     */
    fun requestRoot(): Boolean =
        synchronized(rootLock) {
            if (_root.value == RootState.Granted) return true
            _root.value = RootState.Waiting
            val result =
                try {
                    val p = ProcessBuilder(SU, "-c", "id").redirectErrorStream(true).start()
                    // Read on the side: a blocking read here would make the timeout never fire.
                    val buf = StringBuffer()
                    val reader = Thread { runCatching { buf.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
                    val done = p.waitFor(60, TimeUnit.SECONDS)
                    if (!done) p.destroyForcibly()
                    reader.join(2_000)
                    val out = buf.toString()
                    when {
                        done && p.exitValue() == 0 && out.contains("uid=0") -> RootState.Granted
                        !done -> RootState.Denied("Magisk didn't answer in time. Tap retry and grant Larry Launcher root.")
                        else -> RootState.Denied("Magisk denied root. Allow Larry Launcher in Magisk's superuser list, then retry.")
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "su unavailable", e)
                    RootState.Denied("su isn't installed: this phone isn't rooted with Magisk. Linux stays off.")
                }
            _root.value = result
            result == RootState.Granted
        }

    /** True if root is granted, asking Magisk again if it isn't yet. */
    fun checkRoot(): Boolean = rootGranted || requestRoot()

    /** Throws the message the setup screen shows when Magisk root is missing or denied. */
    fun requireRoot() = check(checkRoot()) { ROOT_MISSING }

    /**
     * Where an absolute guest path lives on the Android side, following the
     * directory binds in [command]: /tmp in the guest isn't rootfs/tmp.
     */
    fun hostFile(guestPath: String): File {
        for ((guest, host) in binds) {
            if (guestPath == guest || guestPath.startsWith("$guest/")) return File(host, guestPath.removePrefix(guest).removePrefix("/"))
        }
        return File(rootfs, guestPath.removePrefix("/"))
    }

    /** The guest path of a host file, the reverse of [hostFile]; null if the guest can't see it. */
    fun guestPath(host: File): String? {
        for ((guest, dir) in binds) {
            if (host.startsWith(dir)) return (guest + "/" + host.relativeTo(dir).path).trimEnd('/')
        }
        return if (host.startsWith(rootfs)) "/" + host.relativeTo(rootfs).path else null
    }

    /**
     * Resets what only lives as long as our process (guest processes die with
     * it): /tmp, the runtime dir, a stale Portage lock. Call once, before any guest runs.
     */
    fun prepare() {
        killAll()
        for (dir in listOf(tmpDir, runtimeDir)) {
            // The guest leaves root-owned files here that the app can't delete; root can.
            runCatching { LauncherData.deleteTree(dir) }.onFailure {
                Log.w(TAG, "clearing $dir", it)
                if (rootGranted) runCatching { rootShell("rm -rf ${shellQuote(dir.path)}") }
            }
            dir.mkdirs()
        }
        for ((_, host) in dataBinds) host.mkdirs()
        if (rootfs.isDirectory && checkRoot()) runCatching { relabel() }.onFailure { Log.w(TAG, "relabel", it) }
        allowWayland()
    }

    /** The app's own SELinux domain (untrusted_app, untrusted_app_32, ...), or null if unreadable. */
    private fun appDomain(): String? =
        runCatching { File("/proc/self/attr/current").readText().trim('\u0000', ' ', '\n') }
            .getOrNull()
            ?.split(':')
            ?.getOrNull(2)
            ?.takeIf { it.matches(SAFE_TYPE) }

    /**
     * The SELinux context the guest's /dev/shm is mounted with: app_data_file at the app's own
     * MLS level (s0:cNNN,...). wl_shm buffers foot and friends create there are then files the
     * launcher (untrusted_app with categories) may map; plain tmpfs:s0 fails the MLS check.
     */
    internal fun shmLevel(): String? {
        val parts = runCatching { File("/proc/self/attr/current").readText().trim('\u0000', ' ', '\n') }.getOrNull()?.split(':') ?: return null
        if (parts.size < 4) return null
        val level = parts.drop(3).joinToString(":")
        return level.takeIf { "u:object_r:tmpfs:$it".matches(SAFE_CONTEXT) }
    }

    @Volatile private var waylandAllowed = false

    /**
     * Lets guest clients (root, in Magisk's `magisk` domain) talk Wayland to the launcher (an
     * untrusted_app): connect to its socket, and let the app accept the fds clients pass
     * (wl_shm memfds, dmabufs). Without these SELinux silently drops the fds and every
     * client dies on its first buffer. Each rule is applied on its own, so one unknown type
     * on this phone's policy doesn't drop the rest. Best effort; once per process.
     */
    fun allowWayland() {
        if (waylandAllowed || !rootGranted) return
        val domain = appDomain() ?: "untrusted_app"
        val code = runCatching { rootShell(waylandPolicyScript(domain)) { Log.i(TAG, "sepolicy: $it") } }.getOrElse { -1 }
        Log.i(TAG, "sepolicy for $domain: $code")
        waylandAllowed = true
    }

    /** Recent SELinux denials from the kernel log, for a launch that failed. */
    fun recentDenials(): List<String> {
        val out = mutableListOf<String>()
        runCatching { rootShell("dmesg 2>/dev/null | grep 'avc:.*denied' | tail -n 4") { out += it } }
        return out
    }

    /** The app's own SELinux context and uid, so files root wrote in the guest stay readable by the launcher. */
    private fun relabelScript(paths: List<File>): String {
        val targets = paths.joinToString(" ") { shellQuote(it.path) }
        val uid = context.applicationInfo.uid
        return "ctx=\$(stat -c %C ${shellQuote(filesDir.path)}) && for t in $targets; do [ -e \"\$t\" ] && chcon -hR \"\$ctx\" \"\$t\"; done; " +
            "[ -d ${shellQuote(home.path)} ] && chown -R $uid:$uid ${shellQuote(home.path)}; true"
    }

    /**
     * Gives the launcher back access to what the guest wrote: files a Magisk root process creates
     * don't carry the app's SELinux categories. Runs after setup and after package jobs.
     */
    fun relabel(whole: Boolean = false) {
        val paths = if (whole) listOf(rootfs, runtimeDir, tmpDir) else listOf(home, File(rootfs, "var/db/pkg"), File(rootfs, "var/lib/portage"), runtimeDir, tmpDir)
        rootShell(relabelScript(paths))
    }

    /** Runs [script] as root on the host side, outside any chroot; returns the exit code. */
    fun rootShell(
        script: String,
        onLine: (String) -> Unit = {},
    ): Int {
        if (!checkRoot()) return -1
        val p = ProcessBuilder(SU, "-c", script).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine(onLine)
        return p.waitFor()
    }

    /**
     * One bind of [host] over [guest] in the rootfs, creating the mount point first (a file for a
     * file). Always `mount -o bind SRC DST`: toybox reads a one-path or unknown-option mount as an
     * /etc/fstab lookup.
     */
    private fun bind(
        host: String,
        guest: String,
        file: Boolean = false,
    ): String {
        val target = shellQuote(rootfs.path + guest)
        val point = if (file) "mkdir -p \"\$(dirname $target)\" && { [ -e $target ] || : > $target; }" else "mkdir -p $target"
        return "$point && m -o bind ${shellQuote(host)} $target"
    }

    /**
     * A fresh tmpfs over the guest's /dev/shm (not the host's, which rbind /dev would share),
     * labelled with [shmContext] so the launcher can use the shm fds Wayland clients pass it.
     * If the kernel refuses the context, falls back to a plain tmpfs and says so.
     */
    private fun shmMount(): List<String> {
        val shm = shellQuote(rootfs.path + "/dev/shm")
        val level = shmLevel()
        val plain = "m -t tmpfs -o mode=1777,nosuid,nodev tmpfs $shm"
        if (level == null) return listOf("mkdir -p $shm", plain)
        // tmpfs keeps the type tmpfs may legally be (no "associate" rule needed, unlike app_data_file)
        // and only gains the app's categories. Each try's error is kept for the log.
        val tries =
            shmContexts(level).joinToString(" || ") { ctx ->
                "{ e=\$(\$MOUNT -t tmpfs -o ${shellQuote("mode=1777,nosuid,nodev,context=\"$ctx\"")} tmpfs $shm 2>&1) || { shmerr=\"\$shmerr [$ctx: \$e]\"; false; }; }"
            }
        return listOf(
            "mkdir -p $shm",
            "shmerr=''; $tries || { echo \"larrylauncher: /dev/shm without app SELinux context:\$shmerr\"; $plain; }",
        )
    }

    /** Kills every process whose root is the rootfs: each guest process, whatever its group or session. */
    private val killScript =
        "for p in /proc/[0-9]*; do [ \"\$(readlink \$p/root 2>/dev/null)\" = ${shellQuote(rootfs.path)} ] && kill -9 \${p#/proc/} 2>/dev/null; done; true"

    /**
     * The root side of every guest command: a private mount namespace (nothing leaks to
     * Android's mounts and it all goes away with the last guest process), the binds, a
     * watchdog that kills the guest if the launcher dies, then chroot. No init: the shell
     * or command is the guest's first process.
     */
    private fun command(script: String): List<String> = listOf(SU, "-c", launchScript(script))

    /**
     * The script `su -c` runs: picks the root manager's busybox, then a private mount namespace
     * with the guest's mounts and the chroot. Every mount names its source, target and type, and
     * a failing one prints the whole command and its error and exits 125 once.
     */
    internal fun launchScript(script: String): String {
        val r = shellQuote(rootfs.path)
        val mounts =
            listOf(
                // Private, so nothing leaks to Android's mounts; best effort (not every mount has it).
                "\$MOUNT --make-rprivate / 2>/dev/null || \$MOUNT -o remount,private / 2>/dev/null || true",
                // /data is nosuid, so doas and su in the guest would be plain binaries: bind the rootfs onto
                // itself and remount that bind suid, before every other mount so they land on top of it.
                // The namespace is private, so it goes away with the guest; the guard skips a second bind.
                "grep -q \" ${rootfs.path} \" /proc/self/mountinfo || m -o bind $r $r",
                "m -o remount,bind,suid $r",
                "mkdir -p $r/dev $r/proc $r/sys $r/etc",
                "{ [ -e $r/etc/fstab ] || : > $r/etc/fstab; }",
                "m -o rbind /dev $r/dev",
                "m -t proc proc $r/proc",
                "m -t sysfs sysfs $r/sys",
            ) + (if (cjkFont.isFile) listOf(bind(cjkFont.path, GUEST_CJK_FONT, file = true)) else emptyList()) +
                binds.map { (guest, host) -> bind(host.path, guest) } +
                shmMount() +
                listOf(
                    // The bound /tmp is an app dir (0700, app uid): Portage drops to its own user
                    // (gpg verifying binpkgs, builds), which then can't create files there.
                    // Sticky world-writable like a real /tmp; the app keeps ownership so it can clean up.
                    "chmod 1777 ${shellQuote(tmpDir.path)}",
                    "mkdir -p $r/var/tmp && chmod 1777 $r/var/tmp",
                )
        val env =
            listOf(
                "HOME=$GUEST_HOME",
                "USER=root",
                "LANG=C.UTF-8",
                // Android's zone name (e.g. Europe/Berlin); the guest otherwise runs on UTC.
                "TZ=${java.util.TimeZone.getDefault().id}",
                "TERM=xterm-256color",
                "SHELL=/bin/bash",
                "XDG_DATA_DIRS=/usr/local/share:/usr/share",
                "XDG_CONFIG_DIRS=/etc/xdg",
                // mise-installed tools (node, say) live in the last two.
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin:/root/.local/bin:/root/.local/share/mise/shims",
                "BROWSER=xdg-open",
                // nano is in every stage3.
                "EDITOR=nano",
                "XDG_RUNTIME_DIR=$GUEST_RUNTIME_DIR",
                "WAYLAND_DISPLAY=${waylandSocket.name}",
                "XDG_SESSION_TYPE=wayland",
                // fastfetch's WM line falls back to these when the socket peer is the Android app.
                "XDG_CURRENT_DESKTOP=larrylauncher",
                "XDG_SESSION_DESKTOP=larrylauncher",
                "GDK_BACKEND=wayland",
                "QT_QPA_PLATFORM=wayland",
                "MOZ_ENABLE_WAYLAND=1",
                "SDL_VIDEODRIVER=wayland",
            ) + graphicsEnv
        val app = android.os.Process.myPid()
        val watchdog =
            "me=\$\$; ( while kill -0 $app 2>/dev/null && kill -0 \$me 2>/dev/null; do sleep 2; done; " +
                "kill -0 $app 2>/dev/null || { $killScript; } ) </dev/null >/dev/null 2>&1 &"
        val chroot =
            "exec \$CHROOT ${shellQuote(rootfs.path)} /usr/bin/env -i ${env.joinToString(" ") { shellQuote(it) }} " +
                "/bin/sh -c ${shellQuote("cd $GUEST_HOME 2>/dev/null; $script")}"
        val inner =
            listOf(
                "if [ -n \"\$BB\" ]; then MOUNT=\"\$BB mount\"; CHROOT=\"\$BB chroot\"; else MOUNT=mount; CHROOT=chroot; fi",
                "m() { err=\$(\$MOUNT \"\$@\" 2>&1) || { echo \"larrylauncher: mount \$* failed: \$err\"; exit 125; }; }",
                mounts.joinToString("\n"),
                watchdog,
                chroot,
            ).joinToString("\n")
        return "$PICK_BUSYBOX\n" +
            "if [ -n \"\$BB\" ]; then exec \$BB unshare -m /system/bin/sh -c ${shellQuote(inner)}; " +
            "else exec unshare -m /system/bin/sh -c ${shellQuote(inner)}; fi"
    }

    /**
     * Kills every guest process: when the launcher goes away, before the rootfs is deleted,
     * and when Linux restarts. Guest processes are root's, so only root can.
     */
    fun killAll() {
        if (rootGranted != true) return
        runCatching { rootShell(killScript) }.onFailure { Log.w(TAG, "killAll", it) }
    }

    /** Removes the rootfs as root: its files are root's. The guest's private mounts are gone once it's killed. */
    fun deleteRootfs() {
        killAll()
        if (rootfs.exists()) check(rootShell("rm -rf ${shellQuote(rootfs.path)}") == 0 || !rootfs.exists()) { "couldn't delete ${rootfs.path}" }
    }

    /** A guest `sh -c [script]`, stderr merged into stdout. Needs root; see [requireRoot]. */
    fun process(script: String): ProcessBuilder {
        requireRoot()
        return ProcessBuilder(command(script)).apply { redirectErrorStream(true) }
    }

    companion object {
        private const val TAG = "chroot"
        private const val SU = "su"

        /** Sets and exports BB to the root manager's busybox (Magisk, KernelSU, APatch), or empty for toybox. */
        const val PICK_BUSYBOX =
            "BB=; for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do " +
                "[ -x \"\$b\" ] && { BB=\$b; break; }; done; export BB"

        /** The exit code of a guest launch whose mounts failed. */
        const val MOUNT_FAILED = 125

        const val GUEST_HOME = "/root"

        /** XDG_RUNTIME_DIR in the guest, bound to [runtimeDir]. */
        const val GUEST_RUNTIME_DIR = "/run/user/0"

        const val ROOT_MISSING =
            "Linux needs root from Magisk, and su is missing or was denied. Grant Larry Launcher root in Magisk and retry; until then Linux stays off."

        /** Shown wherever Linux is switched on. */
        const val ROOT_WARNING = "The Gentoo guest runs as the phone's real root (uid 0), not a sandbox: anything run in it can change Android."

        private val SAFE_TYPE = Regex("[a-z0-9_]+")

        private val SAFE_CONTEXT = Regex("u:object_r:(tmpfs|app_data_file):s0(:c[0-9]+(,c[0-9]+)*)?")

        /** Candidate /dev/shm contexts at the app's MLS [level], most likely to be accepted first. */
        internal fun shmContexts(level: String) = listOf("u:object_r:tmpfs:$level", "u:object_r:app_data_file:$level")

        /**
         * The magiskpolicy (or KernelSU ksud) script that lets [domain], the launcher's own domain,
         * serve Wayland to root guest processes. One rule per call: a type this policy lacks only fails its line.
         */
        internal fun waylandPolicyScript(domain: String): String {
            require(domain.matches(SAFE_TYPE)) { "bad domain $domain" }
            val rules =
                listOf(
                    "allow magisk $domain unix_stream_socket { connectto read write getattr getopt setopt shutdown }",
                    "allow $domain magisk unix_stream_socket { read write getattr getopt setopt shutdown accept }",
                    "allow $domain magisk fd use",
                    "allow $domain magisk file { read write getattr map }",
                    "allow $domain tmpfs file { read write getattr map open }",
                    "allow app_data_file tmpfs filesystem associate",
                    "allow $domain appdomain_tmpfs file { read write getattr map }",
                    "allow $domain magisk_tmpfs file { read write getattr map }",
                    "allow $domain app_data_file file { read write getattr map }",
                    "allow $domain dmabuf_system_heap_device chr_file { read write getattr map }",
                    "allow $domain gpu_device chr_file { read write getattr map ioctl }",
                )
            // MLS: files at s0 with no categories fail the app's mlsconstrain even when allowed;
            // trusted object types skip that check (the shm label above is the main fix).
            val mls = listOf("tmpfs", "appdomain_tmpfs", "magisk_tmpfs").map { "typeattribute $it mlstrustedobject" }
            val rulesAll = rules + mls
            // KernelSU and APatch run root as `su`; same rules for it.
            val all = rulesAll + rules.filter { "magisk" in it && "magisk_tmpfs" !in it }.map { it.replace(" magisk ", " su ") }
            val lines = all.joinToString("\n") { r -> "pol ${shellQuote(r)}" }
            return "if command -v magiskpolicy >/dev/null 2>&1; then pol() { magiskpolicy --live \"\$1\" >/dev/null 2>&1 || echo \"skipped: \$1\"; }; " +
                "elif [ -x /data/adb/ksud ]; then pol() { /data/adb/ksud sepolicy patch \"\$1\" >/dev/null 2>&1 || echo \"skipped: \$1\"; }; " +
                "else pol() { :; }; echo 'no magiskpolicy: SELinux rules not applied'; fi\n$lines\ntrue"
        }

        private const val GUEST_CJK_FONT = "/usr/share/fonts/android/NotoSansCJK-Regular.ttc"
    }
}

/** Where the Magisk root request stands. */
sealed interface RootState {
    /** Not asked yet this run. */
    data object Unknown : RootState

    /** su is running; Magisk's dialog should be up. */
    data object Waiting : RootState

    data object Granted : RootState

    data class Denied(
        val message: String,
    ) : RootState
}
