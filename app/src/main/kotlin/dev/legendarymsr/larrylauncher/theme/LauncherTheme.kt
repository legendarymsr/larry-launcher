package dev.legendarymsr.larrylauncher.theme

import java.io.File
import java.nio.file.Files

/** In the order larrylauncher-theme-switcher looks for a preview. */
private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")

/** What larrylauncher-theme-set lets a theme name be; it names directories and goes into prefs. */
private val THEME_NAME = Regex("^[a-z0-9_][a-z0-9._+-]*$")

/**
 * What a theme installed from a repo may not hand to Linux, as in
 * larrylauncher-theme-set: these run code. A terminal config names the program the
 * terminal starts (our foot.ini includes the theme's), and Lua is loaded by
 * Neovim; any .lua is left out too.
 */
private val DENIED_FROM_REPO = setOf("alacritty.toml", "foot.ini", "ghostty.conf", "kitty.conf", "vscode.json")

internal fun titleOf(name: String) = name.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** Symlinks in a user's theme are skipped: one made in Linux points at a guest path, which means something else on the Android side. */
private fun File.isLink() = Files.isSymbolicLink(toPath())

class LauncherTheme(
    val name: String,
    /** Its directories, lowest first: Larry Launcher's, then the user's, which overrides it file by file like larrylauncher-theme-set. */
    val dirs: List<File>,
    val colors: Map<String, String>,
    /** The user's directory of it, in ~/.config/larrylauncher/themes, if there is one. */
    val own: File? = null,
    /** Whether [own] came from someone else's repo; see [ThemeDirs.isFromRepo]. */
    val fromRepo: Boolean = false,
    /** The user's extra backgrounds for this theme, ~/.config/larrylauncher/backgrounds/<name>. */
    private val extraBackgrounds: File? = null,
) {
    val title get() = titleOf(name)
    val isLight get() = colors["mode"] == "light"

    /**
     * Like larrylauncher-theme-switcher: a preview image of any case and type, the
     * user's over Larry Launcher's, else the first background. Many themes made by
     * others ship no preview.
     */
    val preview: File by lazy {
        dirs.asReversed().firstNotNullOfOrNull { dir ->
            dir
                .listFiles { f -> f.nameWithoutExtension.equals("preview", ignoreCase = true) && f.extension.lowercase() in IMAGE_EXTENSIONS && f.isFile && !f.isLink() }
                ?.minByOrNull { IMAGE_EXTENSIONS.indexOf(it.extension.lowercase()) }
        } ?: backgrounds().firstOrNull() ?: File(dirs.last(), "preview.png")
    }

    /** The extra backgrounds first, then the theme's: larrylauncher-theme-set sorts by path, and theirs sort first. */
    fun backgrounds(): List<File> {
        val merged = LinkedHashMap<String, File>()
        for (dir in dirs) images(File(dir, "backgrounds")).forEach { merged[it.name] = it }
        return images(extraBackgrounds) + merged.values.sortedBy { it.name }
    }

    /**
     * The files Linux gets in ~/.local/state/larrylauncher/current/theme, by name:
     * each directory's top-level files over the last one's, without the
     * images and dotfiles (like [ThemeDirs.SOURCE_MARKER]), and without the
     * [DENIED_FROM_REPO] ones from a repo.
     */
    fun guestFiles(): Map<String, File> {
        val files = LinkedHashMap<String, File>()
        for (dir in dirs) {
            val strict = fromRepo && dir == own
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (!f.isFile || f.isLink() || f.extension == "png" || f.name.startsWith('.')) continue
                if (strict && (f.name in DENIED_FROM_REPO || f.extension == "lua")) continue
                files[f.name] = f
            }
        }
        return files
    }

    private fun images(dir: File?): List<File> =
        dir
            ?.listFiles { f -> f.extension.lowercase() in IMAGE_EXTENSIONS && f.isFile && !f.isLink() }
            .orEmpty()
            .sortedBy { it.name }
}

/**
 * Where themes are, as on the desktop: the release's in [builtIn], the user's own
 * in [user] (~/.config/larrylauncher/themes), which add themes or override parts
 * of one, and extra backgrounds per theme in [backgrounds].
 */
class ThemeDirs(
    val builtIn: File,
    val user: File,
    val backgrounds: File,
) {
    /** Every theme with a colors.toml somewhere. A broken one is passed to [onError] and left out. */
    fun scan(onError: (String, Throwable) -> Unit = { _, _ -> }): List<LauncherTheme> =
        (names(builtIn) + names(user))
            .distinct()
            .mapNotNull { name -> runCatching { load(name) }.onFailure { onError(name, it) }.getOrNull() }
            .sortedBy { it.name }

    /** [name]'s theme, or null if no directory of it has a colors.toml. Throws on a colors.toml that can't be read. */
    fun load(name: String): LauncherTheme? {
        if (!isValidName(name)) return null
        val dirs = listOf(File(builtIn, name), File(user, name)).filter { it.isDirectory && !it.isLink() }
        val colors = colors(dirs) ?: return null
        val own = File(user, name).takeIf { it in dirs }
        return LauncherTheme(
            name,
            dirs,
            colors,
            own = own,
            fromRepo = own != null && isFromRepo(own),
            extraBackgrounds = File(backgrounds, name),
        )
    }

    /**
     * The last directory's palette: its colors.toml or, in a theme older
     * than those, read from its alacritty.toml like larrylauncher-theme-set does.
     */
    private fun colors(dirs: List<File>): Map<String, String>? = dirs.asReversed().firstNotNullOfOrNull { colorsOf(it) }

    /** Whether [name]'s colors are on disk, so there's nothing to download for it. */
    fun hasColors(name: String) = listOf(builtIn, user).any { File(it, "$name/colors.toml").isFile || File(it, "$name/alacritty.toml").isFile }

    private fun names(dir: File) = dir.listFiles { f -> f.isDirectory && !f.isLink() }.orEmpty().map { it.name }

    companion object {
        fun isValidName(name: String) = THEME_NAME.matches(name)

        /** [dir]'s own palette, from its colors.toml or alacritty.toml; null if it has neither. */
        fun colorsOf(dir: File): Map<String, String>? {
            val toml = File(dir, "colors.toml")
            if (toml.isFile && !toml.isLink()) return ThemeColors.load(toml)
            val alacritty = File(dir, "alacritty.toml")
            if (alacritty.isFile && !alacritty.isLink()) return ThemeColors.fromAlacritty(alacritty.readText())?.let(ThemeColors::resolve)
            return null
        }

        /** Marks a user theme that came from elsewhere (older launcher installs wrote it). */
        const val SOURCE_MARKER = ".larrylauncher-source"

        /**
         * Whether a user's theme came from someone else's repo: cloned with
         * git in Linux, or marked with [SOURCE_MARKER] by an older
         * launcher. Its files are then held to [DENIED_FROM_REPO].
         */
        fun isFromRepo(dir: File) = File(dir, ".git").exists() || File(dir, SOURCE_MARKER).exists()
    }
}
