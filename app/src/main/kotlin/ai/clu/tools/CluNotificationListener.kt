package ai.clu.tools

import ai.clu.CluApp
import ai.clu.core.notify.Incoming
import ai.clu.core.notify.NotificationPolicy
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * CLU's view of the notification shade, active only after the owner grants Notification access. Nothing is stored:
 * [current] reads the live list when CLU is asked. Ongoing notifications (music, downloads, CLU's own listening),
 * group summaries and CLU's own notifications are left out, and so are apps the owner muted for CLU.
 *
 * When the owner turns announcements on, a new notification that matches their rules is spoken once
 * ([NotificationPolicy]); the set of announced keys lives in memory only. Replies use the notification's own reply
 * action (RemoteInput), exactly as the shade's reply box does; apps without one can't be replied to.
 */
class CluNotificationListener : NotificationListenerService() {
    data class Item(val app: String, val title: String, val text: String, val postedAt: Long, val key: String = "", val category: String = "", val canReply: Boolean = false) {
        fun incoming() = Incoming(key, app, title, text, category, postedAt)
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as? CluApp ?: return
        if (!app.isReady || !keep(sbn)) return
        val item = toItem(sbn)
        val text = NotificationPolicy.announcement(item.incoming(), app.core.settings, System.currentTimeMillis(), announced.keys) ?: return
        synchronized(announced) { announced[NotificationPolicy.onceKey(item.incoming())] = true }
        app.core.diag.i("notifications", "announced a notification from ${item.app}") // app name only, never content
        app.voice.say(text)
    }

    fun snapshot(): List<Item> = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        .filter { keep(it) }.sortedByDescending { it.postTime }.take(MAX).map { toItem(it) }

    private fun keep(s: StatusBarNotification): Boolean {
        val n = s.notification
        return s.packageName != packageName && !s.isOngoing && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            (n.extras.getCharSequence(Notification.EXTRA_TITLE) != null || n.extras.getCharSequence(Notification.EXTRA_TEXT) != null)
    }

    private fun label(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun toItem(s: StatusBarNotification): Item {
        val e = s.notification.extras
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return Item(label(s.packageName), e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(120), text.take(300), s.postTime, s.key,
            s.notification.category.orEmpty(), replyAction(s.notification) != null)
    }

    /** Sends [message] through the notification's reply action. False when it has none or the app refused. */
    fun reply(key: String, message: String): Boolean {
        val sbn = runCatching { activeNotifications?.firstOrNull { it.key == key } }.getOrNull() ?: return false
        val action = replyAction(sbn.notification) ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent(); val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, message) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent); true }.getOrDefault(false)
    }

    /** Dismisses notifications (all clearable ones, or those of apps whose name contains [app]). Returns how many. */
    fun dismiss(app: String?): Int {
        val targets = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it.isClearable && it.packageName != packageName && (app == null || label(it.packageName).contains(app, ignoreCase = true)) }
        targets.forEach { runCatching { cancelNotification(it.key) } }
        return targets.size
    }

    companion object {
        private const val MAX = 40
        @Volatile var instance: CluNotificationListener? = null; private set
        /** Keys already announced (this process only; bounded). */
        private val announced = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 300 }

        fun granted(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, CluNotificationListener::class.java))

        fun current(): List<Item>? = instance?.snapshot()

        fun replyAction(n: Notification): Notification.Action? = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

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
