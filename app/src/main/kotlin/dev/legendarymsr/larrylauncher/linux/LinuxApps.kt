package dev.legendarymsr.larrylauncher.linux

import java.io.File

data class LinuxApp(
    val name: String,
    /** The command, without the terminal wrapper for terminal apps. */
    val exec: String,
    val terminal: Boolean,
    /** Desktop file id (foot.desktop -> "foot"); GUI apps usually use it as their Wayland app_id. */
    val id: String,
    val wmClass: String?,
    /** Icon= value: a theme icon name or an absolute guest path. */
    val icon: String? = null,
)

/** Whether a window with Wayland [appId] belongs to this app: by desktop id, StartupWMClass, or reverse-DNS tail. */
fun LinuxApp.matches(appId: String): Boolean {
    val id = appId.lowercase()
    return this.id.lowercase() == id || wmClass?.lowercase() == id || this.id.substringAfterLast('.').lowercase() == id
}

private val FIELD_CODE = Regex("%[fFuUdDnNickvm]")

/**
 * Reads launchable entries from the guest's user and system application dirs.
 * A user entry replaces the system one with the same file name (XDG), so a
 * NoDisplay=true file in ~/.local/share/applications hides a package's entry.
 */
fun loadLinuxApps(chroot: Chroot): List<LinuxApp> {
    val dirs = listOf(chroot.applications, File(chroot.rootfs, "usr/share/applications"))
    return dirs
        .flatMap { it.listFiles { f -> f.extension == "desktop" }.orEmpty().toList() }
        .distinctBy { it.name }
        // An entry may be a link to an absolute guest path (some packages link from /opt), which the host can't open.
        .mapNotNull { runCatching { parseDesktopEntry(it) }.getOrNull() }
        // Curated entries (btop, micro, ...) are seeded whether or not the package is installed.
        .filter { isInstalled(chroot.rootfs, it.exec) }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase() }
}

private val BIN_DIRS = listOf("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin")

/**
 * Whether the program [exec] runs is in the guest. The launcher's own wrappers in /usr/local/bin
 * (btop) only count when the program they wrap is there; launcher commands always do.
 */
internal fun isInstalled(
    rootfs: File,
    exec: String,
): Boolean {
    var cmd = exec.trim().split(Regex("\\s+")).firstOrNull()?.trim('"', '\'') ?: return false
    if (cmd == "env") return true
    if (cmd.startsWith("larrylauncher")) return true
    if (cmd.startsWith("/")) return File(rootfs, cmd.removePrefix("/")).exists()
    if (cmd in WRAPPED) return File(rootfs, "usr/bin/$cmd").exists()
    return BIN_DIRS.any { File(rootfs, "$it/$cmd").exists() }
}

/** Programs the launcher wraps with a script of the same name in /usr/local/bin. */
private val WRAPPED = setOf("btop")

private fun parseDesktopEntry(file: File): LinuxApp? {
    var inEntry = false
    val fields = mutableMapOf<String, String>()
    file.forEachLine { raw ->
        val line = raw.trim()
        if (line.startsWith("[")) {
            inEntry = line == "[Desktop Entry]"
        } else if (inEntry && '=' in line) {
            val key = line.substringBefore('=')
            fields.putIfAbsent(key, line.substringAfter('='))
        }
    }
    if (fields["Type"] != "Application") return null
    if (fields["NoDisplay"] == "true" || fields["Hidden"] == "true") return null
    val name = fields["Name"] ?: return null
    val exec = fields["Exec"]?.replace(FIELD_CODE, "")?.trim() ?: return null
    return LinuxApp(name, exec, fields["Terminal"] == "true", file.nameWithoutExtension, fields["StartupWMClass"], fields["Icon"])
}

private val ICON_SIZES = listOf("256x256", "192x192", "128x128", "96x96", "72x72", "64x64", "48x48")

/**
 * Finds an icon file for [icon] in the guest: an absolute path, or a hicolor/pixmaps
 * lookup by name, largest PNG first, then SVG (many apps only ship hicolor/scalable).
 */
fun resolveLinuxIcon(
    rootfs: File,
    icon: String,
): File? {
    if (icon.startsWith("/")) return File(rootfs, icon.removePrefix("/")).takeIf { it.isFile && it.extension in ICON_TYPES }
    val candidates =
        ICON_SIZES.map { "usr/share/icons/hicolor/$it/apps/$icon.png" } +
            "usr/share/pixmaps/$icon.png" +
            "root/.local/share/icons/hicolor/256x256/apps/$icon.png" +
            "usr/share/icons/hicolor/scalable/apps/$icon.svg" +
            "usr/share/pixmaps/$icon.svg" +
            "root/.local/share/icons/hicolor/scalable/apps/$icon.svg"
    return candidates.map { File(rootfs, it) }.firstOrNull { it.isFile }
}

private val ICON_TYPES = setOf("png", "svg")
