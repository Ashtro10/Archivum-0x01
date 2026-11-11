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

    /** Minutes before a calendar event that CLU reminds (0 = off), when it can read the calendar. */
    private fun lead(): Long = app.core.settings.int(ai.clu.core.config.Settings.CAL_REMIND_MIN).toLong() * 60_000L

    /** Start of the next timed calendar event that still needs a reminder, minus the lead. */
    private fun nextEventAlert(now: Long): Long? {
        val l = lead(); val cal = app.core.calendarSource
        if (l <= 0 || cal == null || !cal.canRead()) return null
        return cal.events(now, now + 2 * 86_400_000L).filter { !it.allDay && it.start - l > now && !alerted(it) }.minOfOrNull { it.start - l }
    }

    private fun alertKey(e: ai.clu.core.calendar.CalEvent) = "${e.id}@${e.start}"
    private fun alerted(e: ai.clu.core.calendar.CalEvent) = alertKey(e) in (app.core.store.meta("cal_alerted") ?: "").split(',')
    private fun markAlerted(e: ai.clu.core.calendar.CalEvent) {
        val keep = (app.core.store.meta("cal_alerted") ?: "").split(',').filter { it.isNotBlank() }.takeLast(50)
        app.core.store.setMeta("cal_alerted", (keep + alertKey(e)).joinToString(","))
    }

    override fun reschedule() {
        val pi = PendingIntent.getBroadcast(app, REQ, Intent(app, ReminderReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = System.currentTimeMillis()
        // One alarm for the soonest of: a reminder, an appointment alert, the evening summary.
        val next = listOfNotNull(app.core.butler.nextDue(now), runCatching { nextEventAlert(now) }.getOrNull(), app.core.dayBrief.nextEvening(now)).minOrNull()
        if (next == null) { am.cancel(pi); return }
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /** Posts every reminder that is due, marks it delivered (repeating ones move on) and schedules the next. Returns what was delivered. */
    fun deliverDue(): List<String> {
        val b = app.core.butler; val now = System.currentTimeMillis()
        val due = b.due(now)
        val nm = app.getSystemService(NotificationManager::class.java)
        due.forEach { item ->
            val open = PendingIntent.getActivity(app, item.id.hashCode(), Intent(app, ai.clu.ui.ShellActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(app, CluApp.CH_REMINDERS).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Reminder").setContentText(item.title).setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(NOTIFY_BASE + (item.id.hashCode() and 0xffff), n) }
            b.delivered(item, now)
            app.core.diag.i("butler", "reminder delivered${if (item.recur.isNotEmpty()) " (${item.recur})" else ""}") // no title: private
        }
        // Appointments starting within the lead time get one notification each ("In 15 minutes: Dentist at 4:00 PM").
        val l = lead(); val cal = app.core.calendarSource
        val alerts = if (l > 0 && cal != null && cal.canRead()) cal.events(now, now + l + 60_000L).filter { !it.allDay && it.start >= now && !alerted(it) } else emptyList()
        alerts.forEach { e ->
            val z = java.time.ZoneId.systemDefault()
            val mins = ((e.start - now + 59_999) / 60_000L)
            val n = Notification.Builder(app, CluApp.CH_REMINDERS).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("In $mins min: ${e.title.ifBlank { "event" }}").setContentText(ai.clu.core.calendar.CalendarLogic.line(e, z, false))
                .setCategory(Notification.CATEGORY_EVENT).setAutoCancel(true).build()
            runCatching { nm.notify(NOTIFY_BASE + 0x10000 + (e.id.toInt() and 0xffff), n) }
            markAlerted(e)
        }
        // The evening summary's time: let the proactive planner deliver it (it checks the switch, the day and quiet hours).
        if (app.core.dayBrief.eveningDue(now)) runCatching { ProactiveScheduler.evaluate(app, appOpenedNow = false) }
        reschedule()
        return due.map { it.title } + alerts.map { "event:" + it.title }
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
