package androidx.core.app

import android.app.Notification
import android.content.Context

/**
 * Minimal stand-in for androidx.core's NotificationCompat, covering what the LiveKit SDK's screen-share service uses
 * (Clu never shares its screen, so this is only here to keep that class linkable).
 */
class NotificationCompat {
    open class Builder(context: Context, channelId: String) {
        private val b = Notification.Builder(context, channelId)
        open fun setPriority(priority: Int): Builder = this // channel importance applies on API 26+
        open fun build(): Notification = b.build()
    }
}
