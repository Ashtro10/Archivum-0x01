package ai.clu.voice

import ai.clu.CluApp
import ai.clu.ui.MainActivity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service (type microphone) that keeps the wake-word listener alive while Clu is in the background.
 * Android requires the visible notification; listening ends on force-stop, permission revocation, or if the OS
 * kills the service.
 */
class ListeningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as CluApp
        if (intent?.action == ACTION_STOP) {
            app.core.settings.set(ai.clu.core.config.Settings.BACKGROUND_LISTENING, false)
            stopSelf(); return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CluApp.CH_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Clu is listening for its name")
            .setContentText("Microphone audio is processed on this device only.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build())
            .setOngoing(true).build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+ forbids starting a microphone service from the background; the app must be visible.
            app.core.diag.w("voice", "could not start microphone service: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        app.voice.start(wakeMode = true)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        (application as CluApp).voice.stop()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "ai.clu.STOP_LISTENING"
    }
}
