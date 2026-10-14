package dev.legendarymsr.larrylauncher.degoogle

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.legendarymsr.larrylauncher.linux.Chroot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * De-Google for One UI: turns Google apps off for user 0 with `pm disable-user`, which keeps the APK
 * and its data and is undone by `pm enable`. Nothing is uninstalled and no partition is touched, so
 * it works the same under Magisk and KernelSU(-Next). An optional module re-applies the list after
 * boot, re-enables everything if two boots in a row don't finish, and undoes it all when removed.
 */
class DeGoogle(
    private val context: Context,
    private val chroot: Chroot,
) {
    enum class Tier(val label: String, val note: String) {
        Safe("safe", "Google's own apps. Nothing else on One UI needs them."),
        Careful("careful", "System helpers. Some Samsung or app features stop working; skipped if it's a default app."),
        Core("core", "Play Services, Services Framework and Play Store. Most apps from the Play Store, push notifications, Google sign-in, Wear and contactless pay stop. Stock One UI can't run microG in their place."),
    }

    class Pkg(val name: String, val label: String, val tier: Tier, val why: String)

    /** Never touched: disabling these bootloops or breaks setup, permissions or mainline updates. */
    val never =
        listOf(
            "com.google.android.ext.services" to "notification ranking, autofill core (mainline)",
            "com.google.android.permissioncontroller" to "permission dialogs (mainline)",
            "com.google.android.modulemetadata" to "mainline module list",
            "com.google.android.adservices.api" to "mainline AdServices module",
            "com.google.mainline.telemetry" to "mainline",
            "com.google.android.webview" to "WebView, unless another provider is set",
            "com.google.android.trichromelibrary" to "shared library for WebView/Chrome",
            "com.google.android.setupwizard" to "setup wizard, needed after a factory reset",
            "com.google.android.partnersetup" to "setup wizard dependency",
            "com.google.android.onetimeinitializer" to "setup wizard dependency",
            "com.google.android.apps.restore" to "setup wizard restore step",
            "com.google.android.configupdater" to "system data updates",
            "com.google.android.apps.turbo" to "Device Health Services: battery settings",
            "com.google.android.marvin.talkback" to "accessibility suite",
            "com.sec.android.app.launcher" to "One UI Home: the fallback launcher",
        ).toMap()

    val packages =
        listOf(
            Pkg("com.google.android.youtube", "YouTube", Tier.Safe, "user app"),
            Pkg("com.google.android.apps.youtube.music", "YouTube Music", Tier.Safe, "user app"),
            Pkg("com.google.android.apps.maps", "Maps", Tier.Safe, "user app; apps that open maps fall back to the browser"),
            Pkg("com.google.android.apps.photos", "Photos", Tier.Safe, "Samsung Gallery stays"),
            Pkg("com.google.android.gm", "Gmail", Tier.Safe, "user app"),
            Pkg("com.google.android.apps.docs", "Drive", Tier.Safe, "user app"),
            Pkg("com.google.android.videos", "Google TV", Tier.Safe, "user app"),
            Pkg("com.google.android.apps.tachyon", "Meet (Duo)", Tier.Safe, "the dialer's video call button goes"),
            Pkg("com.google.android.apps.bard", "Gemini", Tier.Safe, "user app"),
            Pkg("com.google.android.apps.googleassistant", "Assistant", Tier.Safe, "the long-press assistant"),
            Pkg("com.google.android.keep", "Keep", Tier.Safe, "user app"),
            Pkg("com.google.android.calendar", "Google Calendar", Tier.Safe, "Samsung Calendar stays"),
            Pkg("com.google.android.apps.searchlite", "Google Go", Tier.Safe, "user app"),
            Pkg("com.google.android.apps.adm", "Find My Device", Tier.Safe, "Samsung Find My Mobile stays"),
            Pkg("com.google.android.feedback", "Feedback", Tier.Safe, "crash report sender"),
            Pkg("com.google.android.gms.location.history", "Location History", Tier.Safe, "stub"),
            Pkg("com.google.ar.core", "ARCore", Tier.Safe, "AR features in some apps"),
            Pkg("com.google.android.gms.supervision", "Family Link", Tier.Safe, "parental controls"),
            Pkg("com.google.android.googlequicksearchbox", "Google app", Tier.Careful, "search widget, Circle to Search, voice typing"),
            Pkg("com.google.android.as", "Android System Intelligence", Tier.Careful, "smart reply, Live Caption, some Galaxy AI bits"),
            Pkg("com.google.android.as.oss", "Private Compute Services", Tier.Careful, "goes with System Intelligence"),
            Pkg("com.google.android.apps.wellbeing", "Google Digital Wellbeing", Tier.Careful, "Samsung's own wellbeing stays"),
            Pkg("com.google.android.projection.gearhead", "Android Auto", Tier.Careful, "no car connection"),
            Pkg("com.google.android.apps.safetyhub", "Personal Safety", Tier.Careful, "crash detection, emergency sharing"),
            Pkg("com.android.chrome", "Chrome", Tier.Careful, "skipped if it's the WebView provider or default browser"),
            Pkg("com.google.android.apps.messaging", "Google Messages", Tier.Careful, "skipped if it's the SMS app"),
            Pkg("com.google.android.dialer", "Google Phone", Tier.Careful, "skipped if it's the dialer"),
            Pkg("com.google.android.inputmethod.latin", "Gboard", Tier.Careful, "skipped if it's the keyboard"),
            Pkg("com.google.android.tts", "Google speech", Tier.Careful, "Samsung TTS stays; voice typing in Google apps goes"),
            Pkg("com.google.android.syncadapters.contacts", "Google contacts sync", Tier.Careful, "contacts stop syncing to Google"),
            Pkg("com.google.android.syncadapters.calendar", "Google calendar sync", Tier.Careful, "calendars stop syncing to Google"),
            Pkg("com.google.android.printservice.recommendation", "Print recommendation", Tier.Careful, "print plugin suggestions"),
            Pkg("com.android.vending", "Play Store", Tier.Core, "no app updates or purchases; use F-Droid / Aurora"),
            Pkg("com.google.android.gms", "Play Services", Tier.Core, "breaks most Play apps, push, sign-in, Wear OS, Google Pay"),
            Pkg("com.google.android.gsf", "Services Framework", Tier.Core, "goes with Play Services"),
        )

    data class State(val name: String, val installed: Boolean, val disabled: Boolean, val guard: String?)

    val log = MutableStateFlow<List<String>>(emptyList())
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private fun say(line: String) {
        log.value = (log.value + line).takeLast(400)
    }

    fun larryIsHome(): Boolean =
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /** Installed and disabled state for every listed package, and why one would be skipped. */
    fun states(): Map<String, State> {
        val pm = context.packageManager
        val guards = guards()
        return packages.associate { p ->
            val info = runCatching { pm.getApplicationInfo(p.name, PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
            val enabled = runCatching { pm.getApplicationEnabledSetting(p.name) }.getOrNull()
            p.name to
                State(
                    p.name,
                    installed = info != null,
                    disabled = info != null && (enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || !info.enabled),
                    guard = guards[p.name],
                )
        }
    }

    /** Packages that are a default role holder right now: disabling them would leave no SMS app, keyboard, launcher… */
    private fun guards(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val pm = context.packageManager
        fun mark(pkg: String?, why: String) {
            if (!pkg.isNullOrBlank()) out.putIfAbsent(pkg, why)
        }
        val cr = context.contentResolver
        mark(Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/'), "it's the keyboard")
        mark(android.provider.Telephony.Sms.getDefaultSmsPackage(context), "it's the SMS app")
        mark(context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage, "it's the phone app")
        fun resolves(intent: Intent) = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        mark(resolves(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.org"))), "it's the browser")
        mark(resolves(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)), "it's the home app")
        val webview = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()
        mark(webview, "it's the WebView provider")
        return out
    }

    private fun job(name: String, body: () -> Boolean): Boolean {
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
            say("no root: allow Larry Launcher in Magisk or KernelSU and try again.")
            return -1
        }
        return chroot.rootShell(script) { say(it) }
    }

    /** Disables [names] for user 0, after the checks; records them in the list the module re-applies. */
    fun disable(names: Collection<String>): Boolean =
        job("disabling") {
            if (!larryIsHome()) {
                say("set Larry Launcher as the home app first, so the phone always has a launcher.")
                return@job false
            }
            val states = states()
            val ok = names.filter { n ->
                val s = states[n]
                when {
                    n in never -> { say("skip $n: never touched"); false }
                    n !in packages.map { it.name } -> { say("skip $n: not on the list"); false }
                    s == null || !s.installed -> { say("skip $n: not installed"); false }
                    s.guard != null -> { say("skip $n: ${s.guard}"); false }
                    else -> true
                }
            }
            if (ok.isEmpty()) return@job true
            val script = buildString {
                appendLine("mkdir -p $STATE_DIR && touch $LIST")
                for (n in ok) {
                    appendLine("if pm disable-user --user 0 $n >/dev/null 2>&1; then echo \"off  $n\"; grep -qx $n $LIST || echo $n >> $LIST; else echo \"failed $n\"; fi")
                }
            }
            root(script) == 0
        }

    /** Enables [names] again and drops them from the list. */
    fun enable(names: Collection<String>): Boolean =
        job("enabling") {
            val script = buildString {
                appendLine("mkdir -p $STATE_DIR && touch $LIST")
                for (n in names) {
                    if (n !in packages.map { it.name }) continue
                    appendLine("cmd package install-existing --user 0 $n >/dev/null 2>&1; pm enable --user 0 $n >/dev/null 2>&1 && echo \"on   $n\" || echo \"failed $n\"; sed -i '/^${n.replace(".", "\\.")}\$/d' $LIST")
                }
            }
            root(script) == 0
        }

    /** Every listed package back on, whatever turned it off, and an empty list. */
    fun restoreAll(): Boolean = enable(packages.map { it.name })

    fun moduleInstalled(): Boolean {
        var found = false
        chroot.rootShell("[ -d /data/adb/modules/$MODULE_ID ] || [ -d /data/adb/modules_update/$MODULE_ID ] && echo yes") { if (it.trim() == "yes") found = true }
        return found
    }

    /** Builds the module zip from assets and installs it with whichever root manager is there. */
    fun installModule(): Boolean =
        job("installing the module") {
            val zip = File(context.cacheDir, "larry-degoogle.zip")
            writeModule(zip)
            val staged = "/data/local/tmp/larry-degoogle.zip"
            root(
                """
                cp ${zip.path} $staged && chmod 644 $staged
                if command -v ksud >/dev/null 2>&1 || [ -x /data/adb/ksud ]; then
                  ${'$'}(command -v ksud || echo /data/adb/ksud) module install $staged
                elif command -v magisk >/dev/null 2>&1; then
                  magisk --install-module $staged
                else
                  echo "no magisk or ksud found"; exit 1
                fi
                r=${'$'}?; rm -f $staged; [ ${'$'}r = 0 ] && echo "installed; it starts on the next reboot"; exit ${'$'}r
                """.trimIndent(),
            ) == 0
        }

    private fun writeModule(out: File) {
        ZipOutputStream(out.outputStream()).use { z ->
            for (name in context.assets.list(ASSET_DIR).orEmpty()) {
                val path = if (name == "update-binary" || name == "updater-script") "META-INF/com/google/android/$name" else name
                z.putNextEntry(ZipEntry(path))
                context.assets.open("$ASSET_DIR/$name").use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    companion object {
        const val MODULE_ID = "larry-degoogle"
        const val STATE_DIR = "/data/adb/larry-degoogle"
        const val LIST = "$STATE_DIR/disabled.txt"
        private const val ASSET_DIR = "degoogle-module"
    }
}
