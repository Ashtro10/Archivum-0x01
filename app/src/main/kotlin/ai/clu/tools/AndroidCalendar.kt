package ai.clu.tools

import ai.clu.core.calendar.CalEvent
import ai.clu.core.calendar.CalendarSource
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * The owner's calendars through Android's calendar provider. Reading uses CalendarContract.Instances, so repeating
 * events are expanded the way the calendar app shows them. Writing goes to the owner's primary calendar (or the first
 * one they can edit). READ_CALENDAR / WRITE_CALENDAR are asked for the first time a calendar request needs them; if the
 * owner revokes them later, every call returns empty/false and the tools say access is missing.
 */
class AndroidCalendar(private val context: Context, private val ask: () -> PermissionAsker?) : CalendarSource {
    private fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    override fun canRead() = has(Manifest.permission.READ_CALENDAR)
    override fun canWrite() = has(Manifest.permission.WRITE_CALENDAR) && canRead()
    override fun requestAccess() { ask()?.ask(Manifest.permission.READ_CALENDAR) }

    override fun events(from: Long, to: Long): List<CalEvent> {
        if (!canRead()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val out = ArrayList<CalEvent>()
        runCatching {
            context.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    // All-day instances are stored in UTC midnights: shift them to the local day.
                    val shift = if (allDay) -TimeZone.getDefault().getOffset(c.getLong(2)).toLong() else 0L
                    out += CalEvent(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2) + shift, c.getLong(3) + shift, allDay, c.getString(5).orEmpty(), c.getString(6).orEmpty())
                }
            }
        }
        return out
    }

    /** The calendar new events go to: the primary one if it can be edited, otherwise the first editable one. */
    private fun targetCalendar(): Long? = runCatching {
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) { if (c.getInt(1) == 1) return@runCatching c.getLong(0); if (first == null) first = c.getLong(0) }
            first
        }
    }.getOrNull()

    override fun insert(title: String, start: Long, end: Long, location: String): Long? {
        if (!canWrite()) return null
        val cal = targetCalendar() ?: return null
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal); put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end); put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        return runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) } }.getOrNull()
    }

    override fun update(id: Long, title: String?, start: Long?, end: Long?): Boolean {
        if (!canWrite()) return false
        val v = ContentValues().apply { title?.let { put(CalendarContract.Events.TITLE, it) }; start?.let { put(CalendarContract.Events.DTSTART, it) }; end?.let { put(CalendarContract.Events.DTEND, it) } }
        return runCatching { context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null) == 1 }.getOrDefault(false)
    }

    override fun delete(id: Long): Boolean {
        if (!canWrite()) return false
        return runCatching { context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) == 1 }.getOrDefault(false)
    }
}
