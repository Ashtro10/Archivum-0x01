package ai.clu.tools

import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.*
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject

private fun Context.open(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

/** Asks the visible screen to request a runtime permission (tools run off the UI; the activity shows the system prompt). */
fun interface PermissionAsker { fun ask(permission: String) }

// ------------------------------------------------------------------------------------------------ media

/**
 * Play, pause, next and previous for whatever is playing (music, podcasts, video apps), through the same media-key
 * events a headset button sends. Works with any app that has an active media session; no permission is needed.
 */
class MediaControlTool(private val context: Context) : Tool {
    override val name = "media.control"
    override val description = "Media: play/pause/next/previous."
    override val argsExample = """{"action":"pause"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args.str("action")?.lowercase() ?: return ToolResult.fail("Play, pause, next or previous?")
        val (code, said) = when (action) {
            "play", "resume", "continue", "unpause" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP to "Stopped."
            "toggle", "play pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Done."
            "next", "skip", "next track", "next song" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Next track."
            "previous", "back", "previous track", "previous song", "last song" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Previous track."
            else -> return ToolResult.fail("I can play, pause, stop, skip to the next track or go back to the previous one.")
        }
        val am = context.getSystemService(AudioManager::class.java)
        val wasPlaying = am.isMusicActive
        if (code == KeyEvent.KEYCODE_MEDIA_PAUSE && !wasPlaying) return ToolResult.ok("Nothing is playing.", verified = true)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        // Pause/play can be checked against the audio system; next/previous only reach the player.
        val verified = when (code) { KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> !am.isMusicActive; else -> false }
        return ToolResult(true, verified, "Sent \"$action\" to the media player.${if (!wasPlaying && code == KeyEvent.KEYCODE_MEDIA_PLAY) " If nothing starts, no app has music ready to resume." else ""}", spoken = said)
    }
}

// ------------------------------------------------------------------------------------------------ notifications

/**
 * Reads and summarises the notifications currently on the device. Needs "Notification access", which only the owner can
 * grant (Settings → Notifications → Device & app notifications); CLU never stores notification text, it is read from
 * the live list when asked. Text from notifications is someone else's words: it is reported, never followed.
 */
class NotificationsReadTool(private val context: Context, private val settings: ai.clu.core.config.SettingsStore, private val source: () -> List<CluNotificationListener.Item>?) : Tool {
    override val name = "notifications.read"
    override val description = "Read current notifications."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!CluNotificationListener.granted(context)) return ToolResult.fail("I can't see your notifications yet: Android needs you to give CLU notification access. " +
            "Say \"open notification access settings\" and turn CLU on there.")
        val all = source() ?: return ToolResult.fail("Notification access is on, but Android hasn't connected CLU's listener yet. Try again in a moment.")
        val app = args.str("app")?.lowercase()
        val items = all.filter { (app == null || it.app.lowercase().contains(app)) && !ai.clu.core.notify.NotificationPolicy.muted(it.app, settings) }
        val data = buildJsonArray { items.forEach { addJsonObject { put("app", it.app); put("title", it.title); put("text", it.text); put("postedAt", it.postedAt) } } }
        if (items.isEmpty()) return ToolResult.ok(if (app != null) "No notifications from $app." else "You have no notifications.", data = data)
        val text = CluNotificationListener.summarise(items)
        return ToolResult.ok(text, data = data)
    }
}

// ------------------------------------------------------------------------------------------------ contacts, calls, messages

/** Contact lookup by name (needs READ_CONTACTS, asked for the first time it is needed). */
class Contacts(private val context: Context) {
    data class Entry(val name: String, val number: String?, val email: String?)

    fun granted() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts whose name matches [query]: exact first, then names starting with it, then names containing it. */
    fun find(query: String, wantEmail: Boolean = false): List<Entry> {
        val q = query.trim().removePrefix("my ").trim()
        if (q.isEmpty()) return emptyList()
        val uri = if (wantEmail) ContactsContract.CommonDataKinds.Email.CONTENT_URI else ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val nameCol = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        val dataCol = if (wantEmail) ContactsContract.CommonDataKinds.Email.ADDRESS else ContactsContract.CommonDataKinds.Phone.NUMBER
        val out = ArrayList<Entry>()
        context.contentResolver.query(uri, arrayOf(nameCol, dataCol), "$nameCol LIKE ?", arrayOf("%$q%"), "$nameCol ASC")?.use { c ->
            while (c.moveToNext()) { val n = c.getString(0) ?: continue; val d = c.getString(1)
                out += if (wantEmail) Entry(n, null, d) else Entry(n, d, null) }
        }
        val lower = q.lowercase()
        return out.distinctBy { it.name.lowercase() + "|" + (it.number ?: it.email)?.filter { ch -> ch.isLetterOrDigit() || ch == '@' } }
            .sortedBy { when { it.name.lowercase() == lower -> 0; it.name.lowercase().startsWith(lower) -> 1; else -> 2 } }
    }

    /** One person for [query], or why not (none / which one?). */
    fun resolve(query: String, wantEmail: Boolean = false): Pair<Entry?, String?> {
        val hits = find(query, wantEmail)
        if (hits.isEmpty()) return null to "I couldn't find $query in your contacts."
        val exact = hits.filter { it.name.equals(query.trim(), ignoreCase = true) }
        val people = (exact.ifEmpty { hits }).distinctBy { it.name.lowercase() }
        if (people.size > 1) return null to "Which one: ${people.take(4).joinToString(", ") { it.name }}?"
        val same = (exact.ifEmpty { hits }).filter { it.name.equals(people[0].name, true) }
        return same.first() to null
    }
}

private fun needContacts(asker: PermissionAsker?): ToolResult {
    asker?.ask(Manifest.permission.READ_CONTACTS)
    return ToolResult.fail("I need permission to read your contacts for that. I've asked Android; allow it and try again.")
}

class ContactFindTool(private val contacts: Contacts, private val asker: () -> PermissionAsker?) : Tool {
    override val name = "contacts.find"
    override val description = "Contact's number."
    override val argsExample = """{"name":"Rahul"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name") ?: return ToolResult.fail("Whose number?")
        if (!contacts.granted()) return needContacts(asker())
        val hits = contacts.find(name)
        if (hits.isEmpty()) return ToolResult.fail("I couldn't find $name in your contacts.")
        return ToolResult.ok(hits.take(4).joinToString("; ") { "${it.name}: ${it.number}" } + ".")
    }
}

/** Calls by name or number. Opens the dialer with the number filled in: the owner presses call (no silent calls). */
class CallTool(private val context: Context, private val contacts: Contacts, private val asker: () -> PermissionAsker?) : Tool {
    override val name = "phone.call"
    override val description = "Call a contact."
    override val argsExample = """{"name":"Mom"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val (who, number) = target(args, contacts, asker) { return it }
        return if (context.open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))) ToolResult(true, true, "Dialer opened for $who ($number); you press call.", spoken = "Ready to call $who. Press call.")
            else ToolResult.fail("No dialer is available on this device.")
    }
}

/** Writes a text message: the messaging app opens with the recipient and text filled in, and the owner sends it. */
class SmsComposeTool(private val context: Context, private val contacts: Contacts, private val asker: () -> PermissionAsker?) : Tool {
    override val name = "sms.compose"
    override val description = "Draft an SMS."
    override val argsExample = """{"name":"Ravi","text":"late"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val body = args.str("text") ?: return ToolResult.fail("What should the message say?")
        val (who, number) = target(args, contacts, asker) { return it }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", body)
        return if (context.open(i)) ToolResult(true, false, "Message to $who written in your messaging app: \"$body\". It isn't sent until you press send.",
            spoken = "I've written it to $who. Check it and press send.") else ToolResult.fail("No messaging app is available on this device.")
    }
}

/** Writes an email in the owner's mail app (recipient from contacts by name, or an address). */
class EmailComposeTool(private val context: Context, private val contacts: Contacts, private val asker: () -> PermissionAsker?) : Tool {
    override val name = "email.compose"
    override val description = "Draft an email."
    override val argsExample = """{"to":"Ravi","subject":"x","text":"y"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val to = args.str("to")
        val address = when {
            to == null -> ""
            to.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) -> to
            else -> { if (!contacts.granted()) return needContacts(asker())
                val (e, why) = contacts.resolve(to, wantEmail = true); e?.email ?: return ToolResult.fail(why ?: "No email address for $to.") }
        }
        val uri = Uri.parse("mailto:" + Uri.encode(address) + "?subject=" + Uri.encode(args.str("subject") ?: "") + "&body=" + Uri.encode(args.str("text") ?: ""))
        return if (context.open(Intent(Intent.ACTION_SENDTO, uri))) ToolResult(true, false, "Email${if (address.isNotEmpty()) " to $address" else ""} written in your mail app; it isn't sent until you send it.",
            spoken = "Your email is ready to review and send.") else ToolResult.fail("No email app is set up on this device.")
    }
}

private inline fun target(args: JsonObject, contacts: Contacts, asker: () -> PermissionAsker?, fail: (ToolResult) -> Nothing): Pair<String, String> {
    args.str("number")?.takeIf { it.matches(Regex("[+0-9*#\\- ]{3,20}")) }?.let { return it to it.replace(" ", "") }
    val name = args.str("name") ?: fail(ToolResult.fail("Who to?"))
    if (!contacts.granted()) fail(needContacts(asker()))
    val (e, why) = contacts.resolve(name)
    val n = e?.number ?: fail(ToolResult.fail(why ?: "$name has no phone number in your contacts."))
    return e.name to n
}

// ------------------------------------------------------------------------------------------------ clipboard, brightness

/** Copies text (or CLU's last reply) to the clipboard. Reading the clipboard in the background is blocked by Android. */
class ClipboardCopyTool(private val context: Context, private val lastReply: (String?) -> String?) : Tool {
    override val name = "clipboard.copy"
    override val description = "Copy to clipboard (last reply if no text)."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = args.str("text") ?: lastReply(ctx.conversationId) ?: return ToolResult.fail("There's nothing to copy yet.")
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Clu", text))
        val ok = cm.primaryClip?.getItemAt(0)?.text?.toString() == text
        return if (ok) ToolResult.ok("Copied to the clipboard.", verified = true) else ToolResult(true, false, "Asked Android to copy it; I couldn't confirm.", spoken = "I've copied it.")
    }
}

/**
 * Screen brightness. Changing it needs "Modify system settings", which only the owner can allow; without it CLU opens
 * that permission screen (and the display settings) instead of pretending.
 */
class BrightnessTool(private val context: Context, private val canWrite: (Context) -> Boolean = { Settings.System.canWrite(it) }) : Tool {
    override val name = "device.brightness"
    override val description = "Screen brightness."
    override val argsExample = """{"level":"40"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!canWrite(context)) {
            context.open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return ToolResult.fail("Android needs you to allow CLU to modify system settings before I can change brightness. I've opened that screen.",
                spoken = "I need your permission to change brightness. I've opened the setting; allow CLU there.")
        }
        val cr = context.contentResolver
        args.str("adaptive")?.let { a -> val on = a == "on" || a == "true"
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            return ToolResult.ok("Adaptive brightness ${if (on) "on" else "off"}.") }
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (args.str("direction")?.lowercase()) {
            "up" -> cur + 40; "down" -> cur - 40
            else -> args.int("level")?.coerceIn(0, 100)?.let { (it * 255 + 50) / 100 } ?: return ToolResult.fail("To what level?")
        }.coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val pct = (now * 100 + 127) / 255
        return ToolResult(true, now == target, "Brightness set to $pct%.", spoken = "Brightness $pct percent.")
    }
}

/** Opens the notification-access screen (the only way to grant it). */
fun notificationAccessIntent(context: Context) = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, CluNotificationListener::class.java).flattenToString())

// ------------------------------------------------------------------------------------------------ websites

/** "Open example.org", "go to Wikipedia": the phone opens the page in its browser (or the site's app, if installed). */
class SiteOpenTool(private val context: Context) : Tool {
    override val name = "browser.open"
    override val description = "Open a website by name or address."
    override val argsExample = """{"site":"Wikipedia"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val what = args.str("site") ?: args.str("url") ?: return ToolResult.fail("Which website?")
        val url = ai.clu.core.browser.Sites.urlFor(what) ?: return ToolResult.fail("I don't know a website called \"$what\". Say its address, like example.org.")
        return if (context.open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ToolResult(true, false, "Opened $url.", spoken = "Opening ${ai.clu.core.browser.Sites.named(what)?.name ?: Uri.parse(url).host}.")
            else ToolResult.fail("No browser is available on this device.")
    }
}

/** "Search YouTube for lo-fi": the site's own search page, so the YouTube/Amazon/Spotify app opens on it when installed. */
class SiteSearchTool(private val context: Context) : Tool {
    override val name = "browser.search"
    override val description = "Search a website (YouTube, Amazon…) in its app or the browser."
    override val argsExample = """{"site":"YouTube","query":"lo-fi music"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("query") ?: return ToolResult.fail("Search for what?")
        val site = args.str("site")
        val url = ai.clu.core.browser.Sites.searchUrl(site, q)
        val where = site?.let { ai.clu.core.browser.Sites.named(it)?.name } ?: "the web"
        return if (context.open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ToolResult(true, false, "Opened $where search for \"$q\".", spoken = "Searching $where for $q.")
            else ToolResult.fail("No browser is available on this device.")
    }
}
