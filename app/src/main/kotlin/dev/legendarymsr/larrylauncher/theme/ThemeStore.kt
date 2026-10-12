package dev.legendarymsr.larrylauncher.theme

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.legendarymsr.larrylauncher.LauncherData
import dev.legendarymsr.larrylauncher.linux.Chroot
import dev.legendarymsr.larrylauncher.ui.Corners
import dev.legendarymsr.larrylauncher.ui.LauncherStyle
import dev.legendarymsr.larrylauncher.ui.Palette
import dev.legendarymsr.larrylauncher.ui.decodeForScreen
import dev.legendarymsr.larrylauncher.upgrade.appStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "themes"

/** A theme's colors bundled with the app. */
class BundledTheme(
    val name: String,
    val colors: Map<String, String>,
) {
    val title get() = titleOf(name)
}

/**
 * themes, in the launcher's own data (see [LauncherData]); the guest
 * sees them where a desktop install keeps them (~/.local/share/larrylauncher/themes), and the
 * user's own in ~/.config/larrylauncher/themes (see [ThemeDirs]).
 * Applying a theme recolors the launcher and swaps the wallpaper and, when
 * Linux is installed, writes the guest's themed configs.
 */
class ThemeStore(
    private val context: Context,
    chroot: Chroot,
    /** Whether Linux is installed; without it, nothing is written into the guest. */
    private val isLinux: () -> Boolean,
    /** Called when the guest configs depending on the theme changed; see [managed]. */
    private val onConfigChanged: () -> Unit,
) {
    private val themesDir = LauncherData.themesDir(context)
    private val dirs = themeDirs(context)
    private val guest = GuestThemeConfig(chroot, themesDir)
    private val prefs = prefs(context)

    var themes by mutableStateOf<List<LauncherTheme>>(emptyList())
        private set
    var current by mutableStateOf<LauncherTheme?>(null)
        private set
    var background by mutableStateOf<File?>(null)
        private set
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Every theme's colors, from the app's assets . Read off the main thread. */
    val bundled: List<BundledTheme> by lazy {
        context.assets.list("themes").orEmpty().sorted().map { file ->
            val text = context.assets.open("themes/$file").bufferedReader().use { it.readText() }
            BundledTheme(file.removeSuffix(".toml"), ThemeColors.resolve(ThemeColors.parse(text)))
        }
    }

    /**
     * The themes to pick from: [bundled], with the colors of the user's
     * override where there is one, then the user's own themes.
     */
    val choices: List<BundledTheme> by derivedStateOf {
        val own = themes.filter { it.own != null }.associateBy { it.name }
        bundled.map { b -> own[b.name]?.let { BundledTheme(it.name, it.colors) } ?: b } +
            own.values.filter { t -> bundled.none { it.name == t.name } }.map { BundledTheme(it.name, it.colors) }
    }

    /** The theme picked. */
    var picked by mutableStateOf(prefs.getString(THEME, null) ?: DEFAULT_THEME)
        private set

    /** Recolors the launcher right away and remembers the choice. */
    fun pick(theme: BundledTheme) {
        picked = theme.name
        Palette.apply(theme.colors)
        prefs.edit().putString(THEME, theme.name).remove(BACKGROUND).apply()
    }

    /** Applies the picked theme: launcher colors, wallpaper and guest configs. */
    suspend fun applyPicked() {
        themes.firstOrNull { it.name == picked }?.let { apply(it) }
    }

    /**
     * The launcher's style, and the picked theme's bundled colors until the
     * themes are loaded. Reads one small
     * asset, so it's fine before the first frame.
     */
    fun loadStyle() {
        // A user's own theme has no bundled colors.
        runCatching { ThemeColors.resolve(ThemeColors.parse(context.assets.open("themes/$picked.toml").bufferedReader().use { it.readText() })) }
            .recoverCatching { checkNotNull(dirs.load(picked)).colors }
            .onSuccess { Palette.apply(it) }
        LauncherStyle.monochromeIcons = prefs.getBoolean(MONO_ICONS, false)
        LauncherStyle.bareAppIcons = prefs.getBoolean(BARE_APP_ICONS, false)
        LauncherStyle.recentApps = prefs.getBoolean(RECENT_APPS, true)
        LauncherStyle.framedSystemBars = prefs.getBoolean(FRAMED_SYSTEM_BARS, true)
        LauncherStyle.corners = Corners.of(prefs.getString(CORNERS, null))
    }

    fun setMonochromeIcons(on: Boolean) {
        LauncherStyle.monochromeIcons = on
        prefs.edit().putBoolean(MONO_ICONS, on).apply()
    }

    fun setBareAppIcons(on: Boolean) {
        LauncherStyle.bareAppIcons = on
        prefs.edit().putBoolean(BARE_APP_ICONS, on).apply()
    }

    fun setRecentApps(on: Boolean) {
        LauncherStyle.recentApps = on
        prefs.edit().putBoolean(RECENT_APPS, on).apply()
    }

    fun setFramedSystemBars(on: Boolean) {
        LauncherStyle.framedSystemBars = on
        prefs.edit().putBoolean(FRAMED_SYSTEM_BARS, on).apply()
    }

    fun setCorners(corners: Corners) {
        LauncherStyle.corners = corners
        prefs.edit().putString(CORNERS, corners.id).apply()
    }

    /** Whether our live wallpaper is the Android wallpaper; the launcher then shows it instead of drawing its own. */
    var liveWallpaperActive by mutableStateOf(false)
        private set

    suspend fun refreshLiveWallpaper() {
        liveWallpaperActive = WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
        if (!liveWallpaperActive && wallpaper == null) wallpaper = decodeWallpaper(background)
    }

    private suspend fun decodeWallpaper(file: File?) = file?.let { withContext(Dispatchers.IO) { decodeForScreen(it, context)?.asImageBitmap() } }

    /** Opens Android's live wallpaper preview with our wallpaper preselected; apps can't set one silently. */
    fun liveWallpaperIntent(): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, ThemeWallpaperService::class.java))

    /** Whether [load] has finished; until then it may still replace the wallpaper. */
    var loaded by mutableStateOf(false)
        private set

    /** Restores the saved theme; guest configs are already on disk from when it was applied. */
    suspend fun load() {
        try {
            restoreSaved()
        } finally {
            loaded = true
        }
    }

    private suspend fun restoreSaved() {
        themes = withContext(Dispatchers.IO) { scan() }
        val theme = themes.firstOrNull { it.name == prefs.getString(THEME, null) } ?: return
        current = theme
        Palette.apply(theme.colors)
        // Templates and how we render them may have changed with an app update.
        if (isLinux() && prefs.getString(RENDERED_BY, null) != appStamp(context)) {
            withContext(Dispatchers.IO) { guest.render(theme) }
            prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
        }
        val bg = theme.backgrounds().let { list -> list.firstOrNull { it.name == prefs.getString(BACKGROUND, null) } ?: list.firstOrNull() }
        // On reload a different background may replace one already showing; it stays up until the new one is decoded.
        val stale = wallpaper != null && bg != background
        background = bg
        // Decodes the wallpaper on first load, unless ours is the live one.
        refreshLiveWallpaper()
        if (stale && !liveWallpaperActive) wallpaper = decodeWallpaper(bg)
    }

    /** Picks up themes the user added or removed in Linux; the theme menus call it when they open. */
    suspend fun rescan() {
        themes = withContext(Dispatchers.IO) { scan() }
    }

    private fun scan(): List<LauncherTheme> {
        writeBundled()
        return dirs.scan { name, e -> Log.w(TAG, "bad theme $name", e) }
    }

    /** Puts each bundled theme's colors on disk as a built-in theme, so it can be applied like any other. */
    private fun writeBundled() {
        for (file in context.assets.list("themes").orEmpty()) {
            val name = file.removeSuffix(".toml")
            if (!ThemeDirs.isValidName(name)) continue
            runCatching {
                val text = context.assets.open("themes/$file").bufferedReader().use { it.readText() }
                val target = File(dirs.builtIn, "$name/colors.toml")
                if (!target.isFile || target.readText() != text) {
                    target.parentFile?.mkdirs()
                    target.writeText(text)
                }
            }.onFailure { Log.w(TAG, "bundled theme $name not written", it) }
        }
    }

    /**
     * Like larrylauncher-theme-remove: deletes the user's directory of [theme].
     * If it was applied, its built-in version takes over, or the default theme.
     */
    suspend fun remove(theme: LauncherTheme) {
        val own = theme.own ?: return
        withContext(Dispatchers.IO) { LauncherData.deleteTree(own) }
        rescan()
        if (current?.name != theme.name) return
        val next = themes.firstOrNull { it.name == theme.name } ?: themes.firstOrNull { it.name == DEFAULT_THEME } ?: themes.firstOrNull()
        if (next != null) {
            apply(next)
        } else {
            current = null
            bundled.firstOrNull { it.name == DEFAULT_THEME }?.let(::pick)
        }
    }

    suspend fun apply(
        theme: LauncherTheme,
        bg: File? = theme.backgrounds().firstOrNull(),
    ) {
        current = theme
        picked = theme.name
        Palette.apply(theme.colors)
        prefs.edit().putString(THEME, theme.name).apply()
        setBackground(bg)
        if (!isLinux()) return
        withContext(Dispatchers.IO) {
            // Called from screen scopes, where a failed write (storage full) would crash the launcher.
            runCatching {
                guest.render(theme)
                prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
                onConfigChanged()
                guest.recolorTerminals(theme)
            }.onFailure { Log.e(TAG, "theme ${theme.name} not written to the guest", it) }
        }
    }

    /**
     * Writes the current theme into a freshly installed guest: what [apply]
     * skipped while Linux wasn't there. The caller syncs [managed] afterwards.
     */
    suspend fun renderGuest() {
        if (themes.isEmpty()) themes = withContext(Dispatchers.IO) { scan() }
        val theme = guestTheme() ?: return
        withContext(Dispatchers.IO) {
            guest.render(theme)
            background?.let(guest::linkBackground)
        }
        prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
    }

    /** The theme the guest should have: the applied one, else the picked one, else the default. */
    private fun guestTheme(): LauncherTheme? =
        current ?: themes.firstOrNull { it.name == picked } ?: themes.firstOrNull { it.name == DEFAULT_THEME } ?: themes.firstOrNull()

    /**
     * Before a Linux app starts: foot.ini includes current/theme/foot.ini and
     * refuses to start without it, so render the theme if it's missing. Blocking.
     */
    fun ensureGuest() {
        if (!isLinux() || guest.hasFootTheme()) return
        runCatching {
            if (themes.isEmpty()) themes = scan()
            guestTheme()?.let(guest::render)
        }.onFailure { Log.e(TAG, "guest theme not written", it) }
    }

    suspend fun setBackground(file: File?) {
        background = file
        prefs.edit().putString(BACKGROUND, file?.name).apply()
        // With the live wallpaper active, the launcher is transparent and needs no copy of its own.
        wallpaper = if (liveWallpaperActive) null else decodeWallpaper(file)
        if (file != null && isLinux()) withContext(Dispatchers.IO) { guest.linkBackground(file) }
        changeListeners.forEach { it() }
    }

    /**
     * Like larrylauncher-theme-bg-install, then picking it: copies an image into the
     * current theme's ~/.config/larrylauncher/backgrounds/<theme> and shows it.
     * Formats Linux can't read (HEIC, GIF) are re-encoded as JPEG.
     */
    suspend fun addBackground(uri: Uri) {
        val theme = current ?: return
        val file = withContext(Dispatchers.IO) {
            val dir = File(dirs.backgrounds, theme.name)
            val type = context.contentResolver.getType(uri)
            val ext = when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val out = File(dir, "image-${System.currentTimeMillis()}.$ext")
            runCatching {
                dir.mkdirs()
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                if (type in listOf("image/png", "image/webp", "image/jpeg")) {
                    input.use { i -> out.outputStream().use { i.copyTo(it) } }
                } else {
                    val bmp = input.use(BitmapFactory::decodeStream) ?: error("can't decode $type")
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }
                out
            }.onFailure {
                out.delete()
                Log.e(TAG, "background not added", it)
            }.getOrNull()
        } ?: return
        setBackground(file)
    }

    /**
     * Guest configs for the apps, in the current theme's colors. Before
     * [load] ran, the saved theme's: leaving them out would count as removing them.
     */
    fun managed() = guest.managed(current ?: savedTheme())

    private fun savedTheme(): LauncherTheme? {
        val name = prefs.getString(THEME, null) ?: return null
        return runCatching { dirs.load(name) }.getOrNull()
    }

    companion object {
        private const val THEME = "theme"
        private const val BACKGROUND = "background"
        private const val MONO_ICONS = "mono_icons"
        private const val BARE_APP_ICONS = "bare_app_icons"
        private const val RECENT_APPS = "recent_apps"
        private const val FRAMED_SYSTEM_BARS = "framed_system_bars"
        private const val CORNERS = "corners"
        private const val RENDERED_BY = "rendered_by"
        private const val DEFAULT_THEME = "tokyo-night"

        /** Called on the main thread after the theme or background changes (the live wallpaper listens). */
        val changeListeners = CopyOnWriteArraySet<() -> Unit>()

        private fun prefs(context: Context) = context.getSharedPreferences("themes", Context.MODE_PRIVATE)

        private fun themeDirs(context: Context) =
            ThemeDirs(
                File(LauncherData.themesDir(context), "themes"),
                LauncherData.userThemesDir(context),
                LauncherData.userBackgroundsDir(context),
            )

        /**
         * The saved theme colors and background, read straight from disk. The wallpaper
         * service can run without the launcher (e.g. after a reboot), so this needs no store.
         */
        fun savedSelection(context: Context): Pair<Map<String, String>, File?>? {
            val prefs = prefs(context)
            val name = prefs.getString(THEME, null) ?: return null
            val theme = runCatching { themeDirs(context).load(name) }.getOrNull() ?: return null
            val bg = prefs.getString(BACKGROUND, null)?.let { saved -> theme.backgrounds().firstOrNull { it.name == saved } }
            return theme.colors to bg
        }
    }
}
