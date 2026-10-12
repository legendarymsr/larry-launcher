package dev.legendarymsr.larrylauncher

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The launcher's own data that Linux also sees. It lives outside the rootfs,
 * so it exists without Linux and survives installing or removing it; chroot
 * binds it to the paths Larry Launcher and agents expect (see [dev.legendarymsr.larrylauncher.linux.Chroot]).
 */
object LauncherData {
    /** The release: themes, wallpapers, templates. The guest sees ~/.local/share/larrylauncher. */
    fun themesDir(context: Context) = File(context.filesDir, "larrylauncher")

    /** The user's own themes. The guest sees ~/.config/larrylauncher/themes, where larrylauncher-theme-install puts them. */
    fun userThemesDir(context: Context) = File(context.filesDir, "themes")

    /** The user's extra backgrounds, per theme. The guest sees ~/.config/larrylauncher/backgrounds. */
    fun userBackgroundsDir(context: Context) = File(context.filesDir, "backgrounds")

    /** Widget files. The guest sees ~/.config/larrylauncher/widgets. */
    fun widgetsDir(context: Context) = File(context.filesDir, "widgets")

    /** Manifest of the launcher-side [dev.legendarymsr.larrylauncher.upgrade.Provisioner]; the guest's is managed.json. */
    const val LAUNCHER_MANIFEST = "launcher-managed.json"
    const val GUEST_MANIFEST = "managed.json"

    /**
     * Deletes [dir] and everything in it without following symlinks: the
     * rootfs holds absolute guest links that resolve elsewhere on the host.
     * Directories get write permission first, since the rootfs has read-only
     * ones (the tarball's modes). Throws if anything is left.
     */
    fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    runCatching { Os.chmod(d.toString(), Os.lstat(d.toString()).st_mode and 0x1ff or 0x1c0) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                /** The walker lists a directory before [preVisitDirectory]: an unreadable one (mode 000) fails here first. */
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    val path = file.toString()
                    val mode = runCatching { Os.lstat(path).st_mode }.getOrNull() ?: throw exc
                    if (!OsConstants.S_ISDIR(mode) || mode and 0x1c0 == 0x1c0) throw exc
                    Os.chmod(path, mode and 0x1ff or 0x1c0)
                    deleteTree(file.toFile())
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    d: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    Files.delete(d)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private const val TAG = "launcher-data"
}
