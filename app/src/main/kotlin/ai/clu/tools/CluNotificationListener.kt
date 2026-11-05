package ai.clu.tools

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * CLU's view of the notification shade, active only after the owner grants Notification access. Nothing is stored:
 * [current] reads the live list when CLU is asked. Ongoing notifications (music, downloads, CLU's own listening),
 * group summaries and CLU's own notifications are left out.
 */
class CluNotificationListener : NotificationListenerService() {
    data class Item(val app: String, val title: String, val text: String, val postedAt: Long)

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun snapshot(): List<Item> = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        .filter { keep(it) }.sortedByDescending { it.postTime }.take(MAX).map { toItem(it) }

    private fun keep(s: StatusBarNotification): Boolean {
        val n = s.notification
        return s.packageName != packageName && !s.isOngoing && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            (n.extras.getCharSequence(Notification.EXTRA_TITLE) != null || n.extras.getCharSequence(Notification.EXTRA_TEXT) != null)
    }

    private fun toItem(s: StatusBarNotification): Item {
        val e = s.notification.extras
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return Item(label, e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(120), text.take(300), s.postTime)
    }

    companion object {
        private const val MAX = 40
        @Volatile var instance: CluNotificationListener? = null; private set

        fun granted(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, CluNotificationListener::class.java))

        fun current(): List<Item>? = instance?.snapshot()

        /** "3 notifications. WhatsApp (2): Rahul: on my way; Mom: call me. Gmail: Bank statement." Newest first, per app. */
        fun summarise(items: List<Item>): String {
            val byApp = items.groupBy { it.app }
            return "${items.size} notification${if (items.size == 1) "" else "s"}. " + byApp.entries.joinToString(" ") { (app, xs) ->
                "$app${if (xs.size > 1) " (${xs.size})" else ""}: " + xs.take(3).joinToString("; ") { i ->
                    listOf(i.title, i.text).filter { it.isNotBlank() }.joinToString(": ").take(140) } + (if (xs.size > 3) "; and ${xs.size - 3} more" else "") + "."
            }
        }
    }
}
