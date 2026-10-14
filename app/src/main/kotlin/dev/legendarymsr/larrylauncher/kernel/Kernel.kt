package dev.legendarymsr.larrylauncher.kernel

import android.content.Context
import android.os.Build
import dev.legendarymsr.larrylauncher.linux.Chroot
import dev.legendarymsr.larrylauncher.linux.shellQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The Larry kernel for the Galaxy Z Flip4 (b4q): a kernel-only AnyKernel3 zip built from
 * Samsung's SM-F721B Android 16 source. Flashing replaces only the kernel inside the boot
 * partition; the ramdisk, and Magisk in it, are kept. Every flash backs up boot first.
 */
class Kernel(
    private val context: Context,
    private val chroot: Chroot,
) {
    data class Device(
        val device: String,
        val model: String,
        val android: String,
        val build: String,
        val kernel: String,
    ) {
        /** Null when the Larry kernel fits this phone, otherwise why it doesn't. */
        val refusal: String?
            get() =
                when {
                    device !in DEVICES -> "this phone is \"$device\" ($model), not a Galaxy Z Flip4 (b4q). The kernel would not boot here."
                    !model.startsWith("SM-F721") -> "model $model isn't a Galaxy Z Flip4 (SM-F721*)."
                    android != ANDROID -> "the kernel is built from Samsung's Android $ANDROID source and this phone runs Android $android. Update to One UI 8 (Android $ANDROID) first."
                    !kernel.startsWith("5.10.") -> "the running kernel is $kernel, not the 5.10 kernel this one replaces."
                    else -> null
                }
    }

    val log = MutableStateFlow<List<String>>(emptyList())
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private fun say(line: String) {
        log.value = (log.value + line).takeLast(400)
    }

    fun device(): Device =
        Device(
            device = prop("ro.product.vendor.device").ifBlank { Build.DEVICE },
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            build = prop("ro.build.PDA").ifBlank { Build.DISPLAY },
            kernel = System.getProperty("os.version").orEmpty(),
        )

    private fun prop(name: String): String =
        runCatching {
            ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

    /** Boot backups, newest first, as root sees them in /sdcard/LarryLauncher/backup. */
    fun backups(): List<String> {
        val out = mutableListOf<String>()
        chroot.rootShell("ls -1t $BACKUP_DIR/boot-*.img 2>/dev/null") { if (it.endsWith(".img")) out += it.trim() }
        return out
    }

    private fun job(
        name: String,
        body: () -> Boolean,
    ): Boolean {
        if (_busy.value != null) return false
        _busy.value = name
        log.value = emptyList()
        return try {
            body()
        } catch (e: Exception) {
            say("error: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            _busy.value = null
        }
    }

    private fun root(script: String): Int {
        if (!chroot.checkRoot()) {
            say("no root: allow Larry Launcher in Magisk and try again.")
            return -1
        }
        return chroot.rootShell(script) { say(it) }
    }

    /** Copies the whole boot partition to the backup folder and checks the copy. Off the main thread. */
    fun backup(): Boolean = job("backing up boot") { backupInner() != null }

    private fun backupInner(): String? {
        val d = device()
        d.refusal?.let { say("refused: $it"); return null }
        val name = "boot-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + "-" + d.kernel.substringBefore('-') + ".img"
        val path = "$BACKUP_DIR/$name"
        say("backing up $BOOT to $path")
        val code =
            root(
                """
                set -e
                mkdir -p $BACKUP_DIR
                dd if=$BOOT of=${shellQuote(path)} bs=4M 2>/dev/null
                sync
                want=${'$'}(blockdev --getsize64 $BOOT)
                got=${'$'}(stat -c %s ${shellQuote(path)})
                [ "${'$'}want" = "${'$'}got" ] || { echo "backup size ${'$'}got doesn't match boot ${'$'}want"; rm -f ${shellQuote(path)}; exit 1; }
                a=${'$'}(sha256sum $BOOT | cut -d' ' -f1); b=${'$'}(sha256sum ${shellQuote(path)} | cut -d' ' -f1)
                [ "${'$'}a" = "${'$'}b" ] || { echo "backup checksum doesn't match boot"; rm -f ${shellQuote(path)}; exit 1; }
                echo "${'$'}b  $name" > ${shellQuote("$path.sha256")}
                [ -e $BACKUP_DIR/boot-first.img ] || cp ${shellQuote(path)} $BACKUP_DIR/boot-first.img
                echo "backup ok: $name (${'$'}got bytes, sha256 ${'$'}b)"
                """.trimIndent(),
            )
        return if (code == 0) path else null.also { say("backup failed, nothing was flashed") }
    }

    /** Downloads the Larry kernel zip, checks it, backs up boot, then flashes the kernel. Off the main thread. */
    fun flash(): Boolean =
        job("flashing") {
            val d = device()
            d.refusal?.let { say("refused: $it"); return@job false }
            val zip = File(context.cacheDir, "larry-kernel-b4q.zip")
            if (!zip.exists() || sha256(zip) != ZIP_SHA256) {
                say("downloading $ZIP_URL")
                download(ZIP_URL, zip)
            }
            val sum = sha256(zip)
            if (sum != ZIP_SHA256) {
                zip.delete()
                say("download checksum $sum doesn't match $ZIP_SHA256; not flashing")
                return@job false
            }
            say("zip ok (sha256 $sum)")
            backupInner() ?: return@job false
            val work = "/data/local/tmp/larry-kernel"
            val code =
                root(
                    """
                    set -e
                    rm -rf $work; mkdir -p $work; cp ${shellQuote(zip.path)} $work/k.zip; cd $work
                    unzip -o k.zip META-INF/com/google/android/update-binary >/dev/null
                    sh META-INF/com/google/android/update-binary 3 1 $work/k.zip
                    cd /; rm -rf $work
                    """.trimIndent(),
                )
            if (code == 0) say("done. reboot to start the Larry kernel. If it doesn't boot, hold power + volume down and use the boot backup.") else say("flash failed (exit $code). Boot was not rewritten unless the log says so; restore the backup if unsure.")
            code == 0
        }

    /** Writes a boot backup back to the boot partition. Off the main thread. */
    fun restore(path: String): Boolean =
        job("restoring") {
            device().refusal?.let { say("refused: $it"); return@job false }
            say("restoring $path to $BOOT")
            root(
                """
                set -e
                f=${shellQuote(path)}
                [ "${'$'}(stat -c %s "${'$'}f")" = "${'$'}(blockdev --getsize64 $BOOT)" ] || { echo "backup size doesn't match the boot partition"; exit 1; }
                if [ -e "${'$'}f.sha256" ]; then [ "${'$'}(sha256sum "${'$'}f" | cut -d' ' -f1)" = "${'$'}(cut -d' ' -f1 "${'$'}f.sha256")" ] || { echo "backup is corrupt (checksum)"; exit 1; }; fi
                blockdev --setrw $BOOT 2>/dev/null || true
                dd if="${'$'}f" of=$BOOT bs=4M 2>/dev/null
                sync
                echo "restored. reboot to use it."
                """.trimIndent(),
            ) == 0
        }

    fun reboot() {
        root("svc power reboot || reboot")
    }

    private fun download(
        url: String,
        to: File,
    ) {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            try {
                when (c.responseCode) {
                    200 -> {
                        c.inputStream.use { i -> to.outputStream().use { i.copyTo(it) } }
                        return
                    }
                    301, 302, 303, 307, 308 -> u = URL(u, c.getHeaderField("Location"))
                    else -> throw IOException("HTTP ${c.responseCode} for $u")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val DEVICES = setOf("b4q")
        const val ANDROID = "16"
        const val BOOT = "/dev/block/by-name/boot"
        /** /sdcard/LarryLauncher/backup, by the path root can always reach whatever its mount namespace. */
        const val BACKUP_DIR = "/data/media/0/LarryLauncher/backup"
        const val VERSION = "5.10.236-android12-9-larry"
        const val ZIP_URL = "https://github.com/legendarymsr/larry-launcher/releases/download/v0.8.0-debug/larry-kernel-b4q.zip"
        const val ZIP_SHA256 = "dbd302a42bae1f2ee3db4d47011c2b9586d3799b571e032a120ce41dccf0a1a1"
    }
}
