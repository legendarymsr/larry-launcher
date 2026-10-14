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
 * Samsung's SM-F721B source for the running Android version. Flashing replaces only the kernel inside the boot
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
                    android !in BUILDS -> "there's a Larry kernel for Android ${BUILDS.keys.joinToString(" and ")} only, built from Samsung's source for each, and this phone runs Android $android."
                    BUILDS[android]?.firmware?.let { it != build } == true -> "the Android $android kernel is built from Samsung's source for firmware ${BUILDS[android]?.firmware} and this phone runs $build. Flash that firmware first."
                    !kernel.startsWith("5.10.") -> "the running kernel is $kernel, not the 5.10 kernel this one replaces."
                    else -> null
                }
    }

    class KernelBuild(
        val version: String,
        val url: String,
        val sha256: String,
        /** The exact firmware build its source matches, when it's tied to one. */
        val firmware: String? = null,
    )

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
    /** Downloads [url] into the cache unless it's already there, and checks its sha256. */
    private fun fetch(
        url: String,
        sha: String,
        name: String,
    ): File? {
        val f = File(context.cacheDir, name)
        if (!f.exists() || sha256(f) != sha) {
            say("downloading $url")
            download(url, f)
        }
        val sum = sha256(f)
        if (sum != sha) {
            f.delete()
            say("checksum of $name is $sum, expected $sha; stopping")
            return null
        }
        say("$name ok (sha256 $sum)")
        return f
    }

    /** Runs an AnyKernel3 zip's installer as root; 0 when it flashed. */
    private fun runZip(zip: File): Int {
        val work = "/data/local/tmp/larry-kernel"
        return root(
            """
            set -e
            rm -rf $work; mkdir -p $work; cp ${shellQuote(zip.path)} $work/k.zip; cd $work
            unzip -o k.zip META-INF/com/google/android/update-binary >/dev/null
            sh META-INF/com/google/android/update-binary 3 1 $work/k.zip
            cd /; rm -rf $work
            """.trimIndent(),
        )
    }

    /** True when the running kernel is the KernelSU-Next one. */
    fun ksuRunning(d: Device = device()): Boolean = d.kernel.endsWith(KSU_SUFFIX)

    /** Downloads the Larry kernel zip, checks it, backs up boot, then flashes the kernel. Off the main thread. */
    fun flash(): Boolean =
        job("flashing") {
            val d = device()
            d.refusal?.let { say("refused: $it"); return@job false }
            if (ksuRunning(d)) {
                say("refused: KernelSU-Next is running and its boot has no Magisk. Use \"back to Magisk\" instead, or this would leave the phone without root.")
                return@job false
            }
            val build = BUILDS.getValue(d.android)
            say("Android ${d.android}: Larry kernel ${build.version}")
            val zip = fetch(build.url, build.sha256, "larry-kernel-b4q-android${d.android}.zip") ?: return@job false
            backupInner() ?: return@job false
            val code = runZip(zip)
            if (code == 0) say("done. reboot to start the Larry kernel. If it doesn't boot, hold power + volume down and use the boot backup.") else say("flash failed (exit $code). Boot was not rewritten unless the log says so; restore the backup if unsure.")
            code == 0
        }

    /**
     * Magisk to KernelSU-Next: back up the Magisk boot (kept as the way back), switch off every
     * Magisk module (a list is kept so going back turns them on again), install the KernelSU-Next
     * manager while Magisk root still works, then flash the KSU kernel zip, which also puts the
     * stock ramdisk back from Magisk's own .backup so Magisk isn't left in boot. Off the main thread.
     */
    fun switchToKsu(): Boolean =
        job("switching to KernelSU-Next") {
            val d = device()
            d.refusal?.let { say("refused: $it"); return@job false }
            if (ksuRunning(d)) { say("KernelSU-Next is already running."); return@job false }
            val build = KSU_BUILDS[d.android]
            if (build == null) { say("refused: no KernelSU-Next kernel for Android ${d.android}."); return@job false }
            if (root("[ -d /data/adb/magisk ] || command -v magisk >/dev/null") != 0) {
                say("refused: Magisk isn't installed, so there's no Magisk boot to come back to.")
                return@job false
            }
            val zip = fetch(build.url, build.sha256, "larry-kernel-ksun-b4q-android${d.android}.zip") ?: return@job false
            val apk = fetch(MANAGER_URL, MANAGER_SHA256, "ksunext-manager.apk") ?: return@job false
            val backup = backupInner() ?: return@job false
            say("step 1: the Magisk boot is saved as the way back")
            if (root("echo ${shellQuote(backup)} > $BACKUP_DIR/magisk-boot.path") != 0) return@job false
            say("step 2: switching off Magisk modules")
            root(
                """
                : > $BACKUP_DIR/disabled-modules.txt
                for m in /data/adb/modules/*/; do
                  [ -d "${'$'}m" ] || continue
                  [ -e "${'$'}m/disable" ] && continue
                  touch "${'$'}m/disable" && echo "${'$'}m" >> $BACKUP_DIR/disabled-modules.txt && echo "off: ${'$'}m"
                done
                true
                """.trimIndent(),
            )
            say("step 3: installing the KernelSU-Next manager")
            val inst =
                root(
                    """
                    cp ${shellQuote(apk.path)} /data/local/tmp/ksunext.apk && chmod 644 /data/local/tmp/ksunext.apk
                    pm install -r /data/local/tmp/ksunext.apk; r=${'$'}?; rm -f /data/local/tmp/ksunext.apk; exit ${'$'}r
                    """.trimIndent(),
                )
            if (inst != 0) {
                say("manager install failed; boot is untouched. Turning the modules back on.")
                enableModules()
                return@job false
            }
            say("step 4: flashing the KernelSU-Next kernel (the zip removes Magisk from the ramdisk)")
            val code = runZip(zip)
            if (code != 0) {
                say("flash failed (exit $code); the zip stops before writing when the ramdisk check fails. Turning the modules back on. Restore the Magisk boot backup if the log shows boot was written.")
                enableModules()
                return@job false
            }
            say("done. Reboot, open KernelSU Next and allow Larry Launcher root (superuser list). Then uninstall the Magisk app. To go back, use \"back to Magisk\" here.")
            true
        }

    private fun enableModules() {
        root("[ -f $BACKUP_DIR/disabled-modules.txt ] && while read -r m; do rm -f \"${'$'}m/disable\" && echo \"on: ${'$'}m\"; done < $BACKUP_DIR/disabled-modules.txt; true")
    }

    /** The Magisk boot saved by [switchToKsu], if there is one. */
    fun magiskBoot(): String? {
        var p: String? = null
        chroot.rootShell("cat $BACKUP_DIR/magisk-boot.path 2>/dev/null") { if (it.endsWith(".img")) p = it.trim() }
        return p
    }

    /** KernelSU-Next back to Magisk: writes the saved Magisk boot back and turns the modules on again. Off the main thread. */
    fun backToMagisk(): Boolean {
        val path = magiskBoot()
        if (path == null) {
            say("no saved Magisk boot found in $BACKUP_DIR")
            return false
        }
        if (!restore(path)) return false
        enableModules()
        root("rm -f $BACKUP_DIR/disabled-modules.txt $BACKUP_DIR/magisk-boot.path")
        say("Magisk boot is back. Reboot; reinstall the Magisk app if you removed it. You can uninstall KernelSU Next afterwards.")
        return true
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
        const val BOOT = "/dev/block/by-name/boot"
        /** /sdcard/LarryLauncher/backup, by the path root can always reach whatever its mount namespace. */
        const val BACKUP_DIR = "/data/media/0/LarryLauncher/backup"
        /** One kernel per Android version, each from Samsung's SM-F721B source for that version; its zip checks the version again. */
        const val KSU_SUFFIX = "-ksun"
        const val MANAGER_URL = "https://github.com/KernelSU-Next/KernelSU-Next/releases/download/v3.4.2/KernelSU_Next_v3.4.2_33355-release.apk"
        const val MANAGER_SHA256 = "774a5511f7034a4930e881114e202319cbdc34c5aaaa5c7c6b6d2902364e4c58"

        /** The same kernels plus KernelSU-Next v3.4.2, with Samsung's UH/RKP/KDP/DEFEX/PROCA/FIVE off. */
        val KSU_BUILDS =
            mapOf(
                "15" to KernelBuild("5.10.226-android12-9-larry-ksun", "https://github.com/legendarymsr/larry-launcher/releases/download/v0.9.2-debug/larry-kernel-ksun-b4q-android15.zip", "9ad24792b89edf59b2791954a6f18f26ead89ca3016cbb2fc4ebccd368091d94", firmware = "F721BXXSAHYI1"),
            )

        val BUILDS =
            mapOf(
                "15" to KernelBuild("5.10.226-android12-9-larry", "https://github.com/legendarymsr/larry-launcher/releases/download/v0.8.1-debug/larry-kernel-b4q-android15.zip", "dd25c13cba31401689bd3450b7f197b1372061e2212273cacce8625da2317cae", firmware = "F721BXXSAHYI1"),
                "16" to KernelBuild("5.10.236-android12-9-larry", "https://github.com/legendarymsr/larry-launcher/releases/download/v0.8.0-debug/larry-kernel-b4q.zip", "dbd302a42bae1f2ee3db4d47011c2b9586d3799b571e032a120ce41dccf0a1a1"),
            )
    }
}
