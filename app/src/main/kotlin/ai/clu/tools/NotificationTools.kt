package ai.clu.tools

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.notify.NotificationPolicy
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.*
import android.content.Context
import kotlinx.serialization.json.JsonObject

private fun noAccess() = ToolResult.fail("I can't see your notifications yet: Android needs you to give CLU notification access. " +
    "Say \"open notification access settings\" and turn CLU on there.")

/** "What did I miss": important notifications since the owner last talked to CLU (or the last 3 hours), by their rules. */
class NotificationsMissedTool(private val context: Context, private val settings: SettingsStore, private val since: () -> Long,
                              private val source: () -> List<CluNotificationListener.Item>?) : Tool {
    override val name = "notifications.missed"
    override val description = "Important notifications missed since the last conversation."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!CluNotificationListener.granted(context)) return noAccess()
        val all = source() ?: return ToolResult.fail("Notification access is on, but Android hasn't connected CLU's listener yet. Try again in a moment.")
        val from = since().takeIf { it > 0 } ?: (ctx.now - 3 * 3_600_000L)
        val keys = NotificationPolicy.missed(all.map { it.incoming() }, from, settings).map { it.key }.toSet()
        val items = all.filter { it.key in keys }
        return if (items.isEmpty()) ToolResult.ok("Nothing important since we last spoke.") else ToolResult.ok(CluNotificationListener.summarise(items))
    }
}

/** Replies through a notification's own reply box. Sends a message on the owner's behalf: asks first by default. */
class NotificationsReplyTool(private val context: Context, private val settings: SettingsStore, private val source: () -> List<CluNotificationListener.Item>?,
                             private val send: (String, String) -> Boolean) : Tool {
    override val name = "notifications.reply"
    override val description = "Reply to a message notification."
    override val argsExample = """{"from":"Rahul","text":"On my way"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override val confirmByDefault = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!CluNotificationListener.granted(context)) return noAccess()
        val from = args.str("from") ?: return ToolResult.fail("Reply to whom?")
        val text = args.str("text") ?: return ToolResult.fail("What should the reply say?")
        val all = source().orEmpty().filter { NotificationPolicy.visible(it.incoming(), settings) }
        val hits = all.filter { it.title.contains(from, ignoreCase = true) || it.app.contains(from, ignoreCase = true) }
        val target = hits.firstOrNull { it.canReply } ?: return ToolResult.fail(
            if (hits.isEmpty()) "I don't see a notification from $from right now." else "${hits.first().app} doesn't allow replies from its notification; open the app to reply.")
        val ok = send(target.key, text)
        return if (ok) ToolResult(true, false, "Reply sent to ${target.title} through ${target.app}'s notification: \"$text\".", spoken = "Replied to ${target.title}.")
            else ToolResult.fail("${target.app} didn't accept the reply.")
    }
}

class NotificationsDismissTool(private val context: Context, private val dismiss: (String?) -> Int?) : Tool {
    override val name = "notifications.dismiss"
    override val description = "Clear notifications (all, or one app's)."
    override val argsExample = """{"app":"WhatsApp"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!CluNotificationListener.granted(context)) return noAccess()
        val app = args.str("app")?.takeUnless { it.equals("all", true) || it.equals("my", true) }
        val n = dismiss(app) ?: return ToolResult.fail("Android hasn't connected CLU's listener yet. Try again in a moment.")
        return ToolResult.ok(if (n == 0) "There were no notifications${app?.let { " from $it" } ?: ""} to clear." else "Cleared $n notification${if (n == 1) "" else "s"}.")
    }
}

/** The owner's switch for spoken notification announcements; explains what is missing (access) instead of pretending. */
class NotificationsAnnounceTool(private val context: Context, private val settings: SettingsStore) : Tool {
    override val name = "notifications.announce"
    override val description = "Turn spoken notification announcements on or off."
    override val argsExample = """{"state":"on"}"""
    override val sensitivity = Sensitivity.CONFIG
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val on = args.str("state") == "on"
        settings.set(Settings.NOTIF_ANNOUNCE, on)
        if (!on) return ToolResult.ok("I won't announce notifications.")
        val apps = settings.list(Settings.NOTIF_APPS).filter { it.isNotBlank() }
        val scope = if (apps.isEmpty()) "messages, calls, alarms and reminders" else apps.joinToString(", ")
        return if (CluNotificationListener.granted(context)) ToolResult.ok("I'll announce $scope, once each, outside quiet hours.")
            else ToolResult(true, false, "Announcements are on, but I need notification access first: say \"open notification access settings\".", spoken = "Turned on, but I need notification access first.")
    }
}

/** The owner's switch for call announcements; asks Android for the call-screening role and contacts when needed. */
class CallsAnnounceTool(private val settings: SettingsStore, private val roleHeld: () -> Boolean, private val ask: (String) -> Unit) : Tool {
    override val name = "calls.announce"
    override val description = "Turn incoming-call announcements on or off."
    override val argsExample = """{"state":"on"}"""
    override val sensitivity = Sensitivity.CONFIG
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val on = args.str("state") == "on"
        settings.set(Settings.CALL_ANNOUNCE, on)
        if (!on) return ToolResult.ok("I won't announce calls.")
        if (roleHeld()) { ask(android.Manifest.permission.READ_CONTACTS); return ToolResult.ok("I'll say who's calling, using your contacts. I never answer or block calls.") }
        ask(ROLE_REQUEST)
        return ToolResult(true, false, "Call announcements are on. Android will ask you to let CLU screen calls: that's how it learns the caller's number. CLU lets every call through.",
            spoken = "Turned on. Android will ask you once to let me see incoming calls.")
    }
    companion object { const val ROLE_REQUEST = "role:call_screening" }
}
