package dev.legendarymsr.larrylauncher

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.legendarymsr.larrylauncher.ui.Palette
import dev.legendarymsr.larrylauncher.ui.applySystemBars
import dev.legendarymsr.larrylauncher.ui.LauncherRoot
import dev.legendarymsr.larrylauncher.ui.LauncherState

/** The home screen: widgets, pinned apps and the drawer; Linux windows open as their own tasks. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private val state = LauncherState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(this, Palette.isLight)
        runtime.start()
        setContent {
            LauncherRoot(runtime, state)
            LaunchedEffect(Palette.isLight) { applySystemBars(this@MainActivity, Palette.isLight) }
        }
        handleDebugExec(intent)
        // Asked on arriving at the home screen, not over the welcome screen of a fresh install,
        // and once nothing else is in front: not over the notification permission dialog that
        // opting into Linux there brings up.
        lifecycleScope.launch {
            snapshotFlow { runtime.launcherReady }.first { it }
            withResumed { requestHomeRole() }
        }
    }

    /** Asks once to become the default home app; Android shows its own chooser. */
    private fun requestHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        if (prefs.getBoolean("home_role_asked", false)) return
        prefs.edit().putBoolean("home_role_asked", true).apply()
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
    }

    /**
     * Debug builds: `adb shell am start -n dev.legendarymsr.larrylauncher/.MainActivity --es exec foot`,
     * or `--es theme kanagawa` to apply a theme.
     */
    private fun handleDebugExec(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("exec")?.let(runtime::launch)
        intent.getStringExtra("theme")?.let { name ->
            lifecycleScope.launch {
                snapshotFlow { runtime.themes.loaded }.first { it }
                runtime.themes.themes.firstOrNull { it.name == name }?.let { runtime.themes.apply(it) }
                    ?: Log.w("debug", "no theme $name")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.wm.focus(null)
        removeStaleWindowTasks()
        // The user may have set or replaced the wallpaper while we were in the background.
        lifecycleScope.launch { runtime.themes.refreshLiveWallpaper() }
    }

    /** Drops Recents cards whose Linux window no longer exists (app exited or process restarted). */
    private fun removeStaleWindowTasks() {
        for (task in getSystemService(ActivityManager::class.java).appTasks) {
            val base = runCatching { task.taskInfo?.baseIntent }.getOrNull() ?: continue
            // By its data rather than its class: windows start through aliases too.
            if (base.data?.let { it.scheme == "larrylauncher" && it.host == "window" } != true) continue
            // A window that just closed removes its own task meanwhile; finishing it again throws.
            if (runtime.wm.windowByHandle(base.data?.lastPathSegment) == null) runCatching { task.finishAndRemoveTask() }
        }
    }

    /** Pressing Home while already home closes the menu, like other launchers. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.close()
        state.closeScreens()
        runtime.widgets.openScreen = null
        state.editing = null
        handleDebugExec(intent)
    }

    /** Launcher shortcuts (Super+key), then typing into a terminal tile that has the keyboard. Back stays the launcher's. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && shortcut(event.keyCode)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK && runtime.terminals.active != null && runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Widgets only refresh while the home screen is visible. */
    override fun onStart() {
        super.onStart()
        runtime.widgets.homeStarted()
        runtime.screenStarted()
    }

    /** Leaving the launcher for good (not just opening an app from home) ends the root guest. */
    override fun onDestroy() {
        if (isFinishing) runtime.stopGuest()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        state.closeNow()
        // An app was just opened from home: it moves to the front of the strip now, out of sight.
        runtime.usage.settle()
        runtime.widgets.homeStopped()
        runtime.screenStopped()
    }

    private fun shortcut(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_D -> state.open()
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_DPAD_LEFT -> state.nav = -1 to 0
            KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_DPAD_RIGHT -> state.nav = 1 to 0
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> state.nav = 0 to 1
            KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> state.nav = 0 to -1
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = keyCode - KeyEvent.KEYCODE_1
            else -> return false
        }
        return true
    }
}
