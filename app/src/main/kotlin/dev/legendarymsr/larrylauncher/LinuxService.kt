package dev.legendarymsr.larrylauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import dev.legendarymsr.larrylauncher.linux.ExtrasState
import dev.legendarymsr.larrylauncher.linux.LinuxState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process (and with it chroot and the Wayland server) in the
 * foreground while Linux is installed or installing; never without Linux.
 * The notification shows the install's progress.
 */
class LinuxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux session", NotificationManager.IMPORTANCE_LOW))
        val runtime = (application as App).runtime
        val state = runtime.bootstrap.state
        startForeground(1, notification(state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (watch == null) {
            watch =
                scope.launch {
                    state.combine(snapshotFlow { runtime.extras.state }) { s, e -> text(s, e) }
                        .distinctUntilChanged().collect { nm.notify(1, build(it)) }
                }
        }
        // Not restarted after the process dies: Linux only comes back with the launcher (Runtime.start),
        // and Android 12+ may refuse startForeground() in a restart from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // The guest is root's, not ours: it doesn't die with the service on its own.
        (application as App).runtime.stopGuest()
        super.onDestroy()
    }

    private fun notification(state: LinuxState) = build(text(state))

    private fun text(
        state: LinuxState,
        extras: ExtrasState = ExtrasState.Idle,
    ): String =
        when {
            state is LinuxState.Installed && extras is ExtrasState.Working ->
                "Installing desktop extras" + (extras.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") +
                    (extras.pkg?.let { " · ${extras.n} of ${extras.of} ${it.substringAfter('/')}" } ?: "")
            state is LinuxState.Installed && extras is ExtrasState.Failed -> "Desktop extras failed · open settings › linux to retry"
            else -> text(state)
        }

    private fun text(state: LinuxState): String =
        when (state) {
            is LinuxState.Installing -> "Installing Linux" + (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") + " · ${state.stage.label}"
            is LinuxState.Failed -> "Linux setup stopped · open settings › linux to retry"
            LinuxState.Removing -> "Removing Linux"
            else -> "Linux session running"
        }

    private fun build(text: String): Notification =
        Notification
            .Builder(this, CHANNEL)
            .setContentTitle(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL = "session"
    }
}
