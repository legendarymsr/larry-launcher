package dev.legendarymsr.larrylauncher

import android.app.ActivityManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.legendarymsr.larrylauncher.linux.AudioBridge
import dev.legendarymsr.larrylauncher.linux.Bootstrap
import dev.legendarymsr.larrylauncher.linux.FILES_COMMAND
import dev.legendarymsr.larrylauncher.linux.LaunchFailure
import dev.legendarymsr.larrylauncher.linux.OutputTail
import dev.legendarymsr.larrylauncher.linux.launchCommand
import dev.legendarymsr.larrylauncher.linux.launchFailed
import dev.legendarymsr.larrylauncher.linux.summary
import dev.legendarymsr.larrylauncher.linux.Gpu
import dev.legendarymsr.larrylauncher.linux.LinuxApp
import dev.legendarymsr.larrylauncher.linux.OpenBridge
import dev.legendarymsr.larrylauncher.linux.Portage
import dev.legendarymsr.larrylauncher.linux.Chroot
import dev.legendarymsr.larrylauncher.linux.LinuxState
import dev.legendarymsr.larrylauncher.linux.SystemUpdates
import dev.legendarymsr.larrylauncher.linux.TerminalFont
import dev.legendarymsr.larrylauncher.linux.bundledIcons
import dev.legendarymsr.larrylauncher.linux.defaultApps
import dev.legendarymsr.larrylauncher.linux.loadLinuxApps
import dev.legendarymsr.larrylauncher.linux.matches
import dev.legendarymsr.larrylauncher.linux.resolveLinuxIcon
import dev.legendarymsr.larrylauncher.linux.run
import dev.legendarymsr.larrylauncher.linux.shellQuote
import dev.legendarymsr.larrylauncher.theme.ThemeStore
import dev.legendarymsr.larrylauncher.ui.Gestures
import dev.legendarymsr.larrylauncher.ui.KeyboardBridge
import dev.legendarymsr.larrylauncher.ui.TerminalTiles
import dev.legendarymsr.larrylauncher.ui.WindowManager
import dev.legendarymsr.larrylauncher.ui.bitmapMask
import dev.legendarymsr.larrylauncher.ui.decodeIcon
import dev.legendarymsr.larrylauncher.upgrade.Provisioner
import dev.legendarymsr.larrylauncher.wayland.AndroidClipboard
import dev.legendarymsr.larrylauncher.wayland.OutputInfo
import dev.legendarymsr.larrylauncher.wayland.WaylandServer
import dev.legendarymsr.larrylauncher.wayland.XdgToplevel
import dev.legendarymsr.larrylauncher.widgets.Shortcut
import dev.legendarymsr.larrylauncher.widgets.WidgetEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Process-wide state: the launcher (themes, widgets, Android apps) and the
 * optional Linux system (the rootfs, the Wayland server, the window manager).
 */
class Runtime(
    private val context: Context,
) {
    val chroot = Chroot(context)
    val wm = WindowManager()
    val server = WaylandServer(chroot.waylandSocket.path, wm, chroot.niriSocket.path).also { wm.server = it }
    val keyboard = KeyboardBridge(wm)
    val terminals = TerminalTiles(this)
    val terminalFont = TerminalFont(context, chroot)
    val gestures = Gestures(context)
    val linuxApps = mutableStateListOf<LinuxApp>()

    /** Icons of [linuxApps] by desktop id; apps without a PNG icon have no entry. */
    val linuxIcons = mutableStateMapOf<String, ImageBitmap>()

    /** Silhouettes of [linuxIcons] for the monochrome icon style. */
    val linuxMasks = mutableStateMapOf<String, ImageBitmap>()
    val androidApps = AndroidAppCatalog(context)
    val usage = AppUsage(context)
    val themes = ThemeStore(context, chroot, isLinux = { linux }, onConfigChanged = ::syncGuest)
    private val audio = AudioBridge(chroot)
    private val openBridge = OpenBridge(context, chroot, onOpenFiles = ::openFiles)
    val portage = Portage(chroot)
    val desktop = dev.legendarymsr.larrylauncher.linux.Desktop(chroot, portage)
    val widgets = WidgetEngine(context, chroot, this)
    val updates = SystemUpdates(chroot, portage)
    val gpu = Gpu(context, chroot)
    val swap = dev.legendarymsr.larrylauncher.linux.Swap(context, chroot)

    /** The launcher's own provided files (default widgets, their docs); see [Provisioner]. */
    private val launcherFiles = Provisioner(chroot.filesDir, LauncherData.LAUNCHER_MANIFEST, listOf(widgets::managed))

    /** Every launcher-provided file in the guest; synced only while Linux is installed. */
    private val provisioner: Provisioner =
        Provisioner(
            chroot.filesDir,
            LauncherData.GUEST_MANIFEST,
            listOf(
                { defaultApps(chroot.applications) },
                { bundledIcons(context, chroot.home) },
                openBridge::managed,
                themes::managed,
                widgets::guestManaged,
            ),
        )
    val bootstrap = Bootstrap(context, chroot, portage, provisioner)

    /** Its jobs write files and start the guest; one that fails (storage full) mustn't take the home screen down. */
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "background job failed", e) })
    private var started = false
    private var prepared = false

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /**
     * The first-run screen is done: from then on the home screen shows, with
     * or without Linux. Installs from before Linux was optional count as done.
     */
    var launcherReady by mutableStateOf(prefs.getBoolean(SETUP_DONE, false) || bootstrap.wanted)
        private set

    /** Linux is installed and its services run: guest commands (like the update check) can go. */
    private var linux = false

    /** The Linux install and removal; one at a time. */
    private var linuxJob: Job? = null

    @Volatile private var linuxChecked = false

    /** A job is (or is about to be) moving [Bootstrap.state] on; when false, Checking is stale. */
    val linuxBusy get() = linuxJob?.isActive == true || !linuxChecked

    /** What the Linux job is doing right now, under "checking linux…". */
    var linuxPhase by mutableStateOf("")

    /** Set when something that needs Linux was asked for without it; the home screen opens the install sheet and clears it. */
    var linuxWanted by mutableStateOf(false)

    init {
        // Saved, so removing Linux later doesn't bring the first-run screen back.
        if (launcherReady) prefs.edit().putBoolean(SETUP_DONE, true).apply()
        themes.loadStyle()
        androidApps.start()
        // the Super bindings inside Linux windows; the home screen handles its own (see MainActivity).
        keyboard.onShortcut = { code ->
            when (code) {
                KeyEvent.KEYCODE_ENTER -> true.also { terminal(null, "Terminal") }
                KeyEvent.KEYCODE_W -> wm.focused?.let { wm.close(it) } != null
                else -> false
            }
        }
        // A terminal shown again after the text size changed catches up, with foot's own zoom keys.
        wm.created = { window -> if (followsTerminalFont(window.appId)) terminalFont.track(window) }
        wm.focusGained = { window -> wm.zoom(window, terminalFont.catchUp(window)) }
        wm.openAppWindow = { handle ->
            // Recents uses the activity's manifest label, not the task description's; see the aliases in the manifest.
            val window = wm.windowByHandle(handle)
            val name = window?.let { displayName(it.appId, it.title) }
            val activity =
                when (name) {
                    "Terminal" -> "TerminalWindow"
                    "btop" -> "BtopWindow"
                    else -> "LinuxWindowActivity"
                }
            context.startActivity(
                Intent()
                    .setClassName(context, "dev.legendarymsr.larrylauncher.$activity")
                    .setData(Uri.parse("larrylauncher://window/$handle"))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                    ),
            )
        }
    }

    /** The launcher first (it never waits for Linux), then Linux if it's installed or an install was started. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            withContext(Dispatchers.IO) {
                launcherFiles.sync()
            }
            themes.load()
            widgets.start()
            if (bootstrap.wanted) runLinux { bootstrap.run() } else bootstrap.run()
            linuxChecked = true
        }
    }

    /** Asks Magisk for root off the main thread; [Chroot.root] shows the answer. Re-runs su unless granted. */
    fun requestRoot() {
        if (chroot.root.value == dev.legendarymsr.larrylauncher.linux.RootState.Waiting) return
        scope.launch(Dispatchers.IO) { chroot.requestRoot() }
    }

    /** The first-run screen is done; the home screen shows from now on. */
    fun finishWelcome() {
        prefs.edit().putBoolean(SETUP_DONE, true).apply()
        launcherReady = true
    }

    /** Installs Linux in the background; progress shows in [Bootstrap.state] and the notification. */
    fun installLinux() = runLinux { bootstrap.install() }

    /** Continues a failed install, or a failed update of an installed system. */
    fun retryLinux() = runLinux { bootstrap.run() }

    /** Runs [work] with the foreground service up, so it goes on outside the app; then starts Linux if it's installed. */
    private fun runLinux(work: suspend () -> Unit) {
        if (linuxJob?.isActive == true) return
        linuxJob =
            scope.launch {
                try {
                    setService(true)
                    if (!prepared) {
                        // Leftovers of an old run must not keep Linux from starting; a hung su must not either.
                        linuxPhase = "cleaning up the last run"
                        val prep = scope.async(Dispatchers.IO) { runCatching { chroot.prepare() }.onFailure { Log.e(TAG, "prepare", it) } }
                        if (kotlinx.coroutines.withTimeoutOrNull(90_000) { prep.await() } == null) Log.w(TAG, "prepare timed out, starting anyway")
                        prepared = true
                        // Its own job: writing GiBs of zeros must never hold up Linux; progress shows in the swap row.
                        if (bootstrap.isInstalled || chroot.rootfs.isDirectory) {
                            scope.launch(Dispatchers.IO) { runCatching { swap.apply() }.onFailure { Log.e(TAG, "swap", it) } }
                        }
                    }
                    linuxPhase = "starting linux"
                    work()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    bootstrap.markStopped("stopped before it finished")
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "linux job failed", e)
                    bootstrap.markStopped(e.message ?: e.toString())
                } finally {
                    // Nothing may leave the panel on Checking/Installing with no job behind it.
                    bootstrap.markStopped("stopped before it finished")
                }
                if (bootstrap.isInstalled) onLinuxReady() else setService(false)
            }
    }

    /**
     * Deletes Linux: its apps, packages and home. Themes, widgets and
     * The home screen stay; widgets that need Linux turn back into placeholders.
     */
    fun removeLinux() {
        if (!bootstrap.isInstalled || linuxJob?.isActive == true) return
        linuxJob =
            scope.launch {
                linux = false
                widgets.linuxChanged(false)
                audio.stop()
                withContext(Dispatchers.IO) { chroot.killAll() }
                bootstrap.remove()
                withContext(Dispatchers.IO) {
                    updates.forget()
                }
                gpu.turnOff()
                linuxApps.clear()
                linuxIcons.clear()
                linuxMasks.clear()
                setService(false)
            }
    }

    /**
     * Kills the root guest: the launcher is leaving (its activity finished, or the service
     * stopped). Every guest process has the rootfs as its root, so none is missed; the
     * watchdog each launch starts covers the launcher being killed outright.
     */
    fun stopGuest() {
        audio.stop()
        Thread({ chroot.killAll() }, "guest-kill").start()
    }

    /** The foreground service keeps the process, and with it Linux, alive; only while Linux is installed or installing. */
    private fun setService(on: Boolean) {
        val intent = Intent(context, LinuxService::class.java)
        // Android 12+ refuses a foreground service started from the background; the work then goes on without it.
        if (on) runCatching { context.startForegroundService(intent) }.onFailure { Log.w(TAG, "cannot start the service", it) } else context.stopService(intent)
    }

    /** Guest configs follow the theme; only while there is a guest. */
    private fun syncGuest() {
        if (linux) provisioner.sync()
    }

    /** Whether the bridges and the Wayland server are up; they run until the process ends, even if Linux is removed. */
    private var servicesStarted = false

    private suspend fun onLinuxReady() {
        if (linux) return
        if (!servicesStarted) {
            servicesStarted = true
            updateOutput()
            synchronized(server) { server.selection.system = AndroidClipboard(context, server::clipboardChanged) }
            context.registerComponentCallbacks(
                object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) = updateOutput()

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = Unit
                },
            )
            withContext(Dispatchers.IO) {
                server.start()
                chroot.procShim.start()
                openBridge.start()
            }
        } else {
            // Linux installed again: its new home has no foot-font.ini yet.
            terminalFont.write()
        }
        linux = true
        // The recent apps strip on home lists Linux apps before the app list is ever opened.
        scope.launch { refreshApps() }
        // Themes picked without Linux are written into the guest now.
        themes.renderGuest()
        withContext(Dispatchers.IO) {
            provisioner.sync()
            audio.start()
            synchronized(server) { server.input.setKeymap(chroot.keymap.path) }
        }
        widgets.linuxChanged(true)
        refreshApps()
        withContext(Dispatchers.IO) { updates.load() }
        scope.launch {
            // Update steps that need the network; the home screen doesn't wait for them.
            bootstrap.runBacklog()
            updates.check()
        }
        // Apps render in software until the GPU passed its check on this system build.
        scope.launch { gpu.recheck() }
    }

    /** Turns GPU acceleration on (installing and checking the driver) or off, for Linux apps started from then on. */
    fun setGpu(on: Boolean) {
        if (!linux) return
        if (on) scope.launch { gpu.turnOn() } else gpu.turnOff()
    }

    /** Sets the swap file size in GiB (0 turns it off) and applies it now. */
    fun setSwap(gb: Int) {
        swap.setSize(gb)
        scope.launch(Dispatchers.IO) { swap.apply() }
    }

    fun removeSwap() {
        swap.setSize(0)
        scope.launch(Dispatchers.IO) { swap.remove() }
    }

    fun cancelSwap() {
        scope.launch(Dispatchers.IO) { swap.cancel() }
    }

    fun refreshSwap() {
        scope.launch(Dispatchers.IO) { swap.refresh() }
    }

    /** Runs every pending update (packages); the drawer then lists what changed. */
    fun updateSystem() {
        scope.launch {
            updates.updateAll()
            refreshApps()
        }
    }

    /**
     * Tells Wayland clients the screen's size and scale; they get it again on
     * rotation, a density change or a new Android font size, which also sizes
     * The terminal font.
     */
    private fun updateOutput() {
        val metrics = context.resources.displayMetrics
        val mmPerPx = 25.4f / metrics.xdpi
        val scale = outputScale()
        server.updateOutput(
            OutputInfo(
                metrics.widthPixels,
                metrics.heightPixels,
                scale,
                (metrics.widthPixels * mmPerPx).toInt(),
                (metrics.heightPixels * mmPerPx).toInt(),
            ),
        )
        terminalFont.update(metrics.density, context.resources.configuration.fontScale, scale, metrics.widthPixels)
    }

    /** wl_output scales are integers, so the density rounds down (2.625 -> 2); [TerminalFont] makes up the rest. */
    private fun outputScale() =
        context.resources.displayMetrics.density
            .toInt()
            .coerceIn(1, 4)

    /** Re-runs update steps that failed, e.g. offline; see [Bootstrap.updates]. */
    fun retryUpdates() {
        if (linux) scope.launch { bootstrap.runBacklog() }
    }

    /**
     * Launcher screens showing now: the home screen and Linux windows. Guest
     * helpers that only matter on screen (the /proc stand-ins) pause at zero.
     */
    private var screens = 0

    fun screenStarted() {
        if (screens++ == 0) chroot.procShim.setActive(true)
        // Only checks when the last check is hours old; see SystemUpdates.
        if (linux) scope.launch { updates.check() }
    }

    fun screenStopped() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens == 0) chroot.procShim.setActive(false)
    }

    /** Rescans .desktop entries; the list only changes (and recomposes) when entries do. */
    suspend fun refreshApps() {
        if (!linux) return
        val apps = withContext(Dispatchers.IO) { loadLinuxApps(chroot) }
        if (apps != linuxApps.toList()) {
            linuxApps.clear()
            linuxApps += apps
        }
        val ids = apps.mapTo(HashSet()) { it.id }
        linuxIcons.keys.retainAll(ids)
        linuxMasks.keys.retainAll(ids)
        val missing = apps.filter { it.icon != null && it.id !in linuxIcons }
        if (missing.isEmpty()) return
        val size = (52 * context.resources.displayMetrics.density).toInt()
        val loaded =
            withContext(Dispatchers.IO) {
                missing.mapNotNull { app ->
                    val file = resolveLinuxIcon(chroot.rootfs, app.icon!!) ?: return@mapNotNull null
                    decodeIcon(file, size)?.let { Triple(app.id, it.asImageBitmap(), bitmapMask(it).asImageBitmap()) }
                }
            }
        for ((id, icon, mask) in loaded) {
            linuxIcons[id] = icon
            linuxMasks[id] = mask
        }
    }

    /** A pinned app's icon as the drawer shows it, or its silhouette; null until loaded, or if it has none. */
    fun shortcutIcon(
        shortcut: Shortcut,
        mono: Boolean,
    ): ImageBitmap? {
        shortcut.component?.let { androidAppKey(it, shortcut.profile) }?.let { return if (mono) androidApps.masks[it] else androidApps.icons[it] }
        val id = shortcut.linux?.id ?: return null
        return if (mono) linuxMasks[id] else linuxIcons[id]
    }

    /** Wayland app_id -> name for windows we started, so their Android tasks get a proper label. */
    private val launchedNames = ConcurrentHashMap<String, String>()

    /** Whether a window's foot takes its size from foot.ini, as its own terminals, plain foot and most tiles do. */
    private fun followsTerminalFont(appId: String) =
        when {
            appId == "foot" -> true
            launchedNames.containsKey(appId) -> !terminalFont.hasOwnSize(appId)
            appId.startsWith(TerminalTiles.APP_ID_PREFIX) -> !terminals.hasOwnFont(appId)
            else -> false
        }

    /**
     * Opens [command] (or a shell) in a terminal named [name]. Each gets its own
     * foot app_id, so Recents shows "btop" or "Editor" rather than one "foot" per window.
     */
    fun terminal(
        command: String?,
        name: String,
    ) {
        if (!linux) {
            linuxWanted = true
            return
        }
        val slug = name
        val appId = "larrylauncher." + slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        launchedNames[appId] = name
        val args = command?.let { " -e bash -c " + shellQuote(it) }.orEmpty()
        launch("foot --app-id=$appId${terminalFont.optionFor(appId, command)}$args")
    }

    /** Opens a pinned app from the home screen. A Linux one runs its current desktop entry, which an update may have changed. */
    fun open(shortcut: Shortcut) {
        shortcut.linux?.let { pinned -> launch(linuxApps.firstOrNull { it.id == pinned.id } ?: pinned) }
        shortcut.component?.let { androidApps.open(it, shortcut.profile) }
    }

    fun launch(app: LinuxApp) {
        // Our own Files app: open it directly rather than round-tripping through the guest.
        if (app.exec == FILES_COMMAND) return openFiles()
        if (app.terminal) return terminal(app.exec, app.name)
        // Like an Android icon: an app that already has a window comes back to it. Single-instance
        // apps would otherwise just exit on a second launch, showing nothing. Quickshell apps (App Finder)
        // all share the app_id org.quickshell, so their window title, which is their name, decides.
        wm.appWindows
            .firstOrNull { (it.appId.isNotEmpty() && app.matches(it.appId)) || it.title.equals(app.name, ignoreCase = true) }
            ?.let { if (bringToFront(it)) return }
        launch(app.exec)
    }

    /** niri's window, if the Gentoo desktop is running. */
    val niriWindow get() = wm.appWindows.firstOrNull { it.appId.startsWith("niri") || it.title.startsWith("niri") }

    fun startDesktop() {
        niriWindow?.let { return showWindow(it) }
        desktop.lastRun = emptyList()
        launch(desktop.startCommand(density = context.resources.displayMetrics.density, gpu = chroot.graphicsEnv != dev.legendarymsr.larrylauncher.linux.Gpu.SOFTWARE_ENV), onFailure = { desktop.lastRun = it })
    }

    fun stopDesktop() = launch(dev.legendarymsr.larrylauncher.linux.Desktop.STOP_COMMAND)

    fun installDesktop() {
        scope.launch { desktop.install() }
    }

    /** Shows a Linux window from the home screen's column strip: its task, or a new one. */
    fun showWindow(window: XdgToplevel) {
        if (bringToFront(window)) return
        wm.handleOf(window)?.let(wm.openAppWindow)
    }

    /** Moves [window]'s Android task to the front; false if it has none (yet). */
    private fun bringToFront(window: XdgToplevel): Boolean {
        val handle = wm.handleOf(window) ?: return false
        val task =
            context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                runCatching { it.taskInfo?.baseIntent?.data?.lastPathSegment }.getOrNull() == handle
            } ?: return false
        // The task may have gone since it was listed.
        return runCatching { task.moveToFront() }.isSuccess
    }

    /** The name to show for a Linux window: launch name, then .desktop entry, then its title. */
    fun displayName(
        appId: String,
        title: String,
    ): String {
        launchedNames[appId]?.let { return it }
        linuxApps.firstOrNull { it.matches(appId) }?.let { return it.name }
        return title.ifEmpty { appId.ifEmpty { "Linux" } }
    }

    /** The drawer icon of the app called [name]; null until loaded, or if it has none. */
    fun iconNamed(name: String): ImageBitmap? = linuxApps.firstOrNull { it.name == name }?.let { linuxIcons[it.id] }

    /** Opens the Files app, at [path] (a guest path) if given. */
    fun openFiles(path: String? = null) {
        context.startActivity(
            Intent(context, FilesActivity::class.java)
                .putExtra(FilesActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Starts a command inside the guest; its output goes to logcat. Returns at once; [onExit] runs on the launch thread. */
    fun launch(
        command: String,
        onExit: () -> Unit = {},
        /** Gets the output (and SELinux denials) when the launch counts as failed. */
        onFailure: ((List<String>) -> Unit)? = null,
    ) {
        if (!linux) {
            linuxWanted = true
            onExit()
            return
        }
        Log.i(TAG, "launch: $command")
        thread(name = "guest-launch", isDaemon = true) {
            chroot.allowWayland()
            themes.ensureGuest()
            val tail = OutputTail()
            val windowsBefore = wm.toplevelsCreated
            val started = System.currentTimeMillis()
            val code =
                runCatching {
                    chroot.run(launchCommand(command)) {
                        Log.i(TAG, "[${command.take(24)}] $it")
                        tail.add(it)
                    }
                }.getOrElse {
                    tail.add(it.message ?: it.toString())
                    -1
                }
            val seconds = (System.currentTimeMillis() - started) / 1000
            // Some window came up while it ran (another app's could too: good enough to not cry wolf).
            val sawWindow = wm.toplevelsCreated > windowsBefore
            Log.i(TAG, "exited $code after ${seconds}s: $command")
            if (launchFailed(code, seconds, sawWindow)) {
                val denials = if (code != 0) chroot.recentDenials() else emptyList()
                val failure = LaunchFailure(command, code, seconds, tail.toList() + denials.map { "selinux: $it" })
                Log.w(TAG, failure.summary())
                main.post {
                    onFailure?.invoke(listOf("exit ${failure.code} after ${failure.seconds}s") + failure.output)
                    launchFailures.add(0, failure)
                    while (launchFailures.size > 5) launchFailures.removeAt(launchFailures.lastIndex)
                    android.widget.Toast.makeText(context, failure.summary(), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            onExit()
        }
    }

    /** Linux apps that exited without opening, newest first; shown on the linux panel. */
    val launchFailures = androidx.compose.runtime.mutableStateListOf<LaunchFailure>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "runtime"
        private const val SETUP_DONE = "setup_done"
    }
}
