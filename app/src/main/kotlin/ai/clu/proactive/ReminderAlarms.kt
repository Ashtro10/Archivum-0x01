package ai.clu.proactive

import ai.clu.CluApp
import ai.clu.core.butler.ReminderScheduler
import ai.clu.ui.MainActivity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delivers CLU's reminders (core Butler items with a due time) through AlarmManager, so they ring with CLU closed and
 * after a reboot. Exact timing when Android allows exact alarms ("Alarms & reminders" permission), otherwise Android's
 * battery-friendly window, which can be several minutes late in deep sleep: [exact] tells the owner which applies.
 */
class ReminderAlarms(private val app: CluApp) : ReminderScheduler {
    private val am get() = app.getSystemService(AlarmManager::class.java)

    val exact: Boolean get() = am.canScheduleExactAlarms()

    override fun reschedule() {
        val pi = PendingIntent.getBroadcast(app, REQ, Intent(app, ReminderReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val next = app.core.butler.nextDue()
        if (next == null) { am.cancel(pi); return }
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /** Posts every reminder that is due, marks it delivered (repeating ones move on) and schedules the next. Returns what was delivered. */
    fun deliverDue(): List<String> {
        val b = app.core.butler; val now = System.currentTimeMillis()
        val due = b.due(now)
        val nm = app.getSystemService(NotificationManager::class.java)
        due.forEach { item ->
            val open = PendingIntent.getActivity(app, item.id.hashCode(), Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(app, CluApp.CH_REMINDERS).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Reminder").setContentText(item.title).setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(NOTIFY_BASE + (item.id.hashCode() and 0xffff), n) }
            b.delivered(item, now)
            app.core.diag.i("butler", "reminder delivered${if (item.recur.isNotEmpty()) " (${item.recur})" else ""}") // no title: private
        }
        reschedule()
        return due.map { it.title }
    }

    companion object {
        const val ACTION = "ai.clu.REMINDER"
        private const val REQ = 4712
        private const val NOTIFY_BASE = 2000
    }
}

/** Alarm fired, or the device restarted / the clock changed: deliver what is due and set the next alarm. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CluApp ?: return
        if (!app.isReady) return
        app.reminders.deliverDue()
    }
}
