package ai.clu.core.butler

import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.tools.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import java.time.ZoneId

/** The platform's reminder delivery is told whenever the next reminder time may have changed (Android: AlarmManager). */
fun interface ReminderScheduler { fun reschedule() }

class TaskAddTool(private val butler: Butler) : Tool {
    override val name = "task.add"
    override val description = "Add to a list (tasks, shopping, …)."
    override val argsExample = """{"title":"milk","list":"shopping"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title") ?: return ToolResult.fail("What should I add?")
        val list = Butler.normaliseList(args.str("list") ?: Butler.TASKS)
        val items = if (list == Butler.SHOPPING) title.split(Regex("\\s*(?:,|\\band\\b)\\s*")).filter { it.isNotBlank() } else listOf(title)
        val added = items.map { butler.add(it, list) }
        val names = added.joinToString(", ") { it.title }
        return ToolResult.ok("Added $names to your ${label(list)}.", verified = butler.get(added.last().id) != null, spoken = "Added $names to your ${label(list)}.")
    }
}

class TaskListTool(private val butler: Butler) : Tool {
    override val name = "task.list"
    override val description = "Read a list, or all open items."
    override val argsExample = """{"list":"shopping"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val list = args.str("list")?.let(Butler::normaliseList)
        val items = butler.open(list)
        val data = buildJsonArray { items.forEach { addJsonObject { put("title", it.title); put("list", it.list); it.dueAt?.let { d -> put("due", butler.describe(d, ctx.now)) }; if (it.recur.isNotEmpty()) put("repeat", it.recur) } } }
        if (items.isEmpty()) return ToolResult.ok(if (list != null) "Your ${label(list)} is empty." else "You have no open tasks, list items or reminders.", data = data)
        val text = if (list != null) "Your ${label(list)}: " + items.joinToString(", ") { line(it, ctx.now) } + "."
            else items.groupBy { it.list }.entries.joinToString(" ") { (l, xs) -> "${label(l).replaceFirstChar { it.uppercase() }}: ${xs.joinToString(", ") { line(it, ctx.now) }}." }
        return ToolResult.ok(text, data = data)
    }
    private fun line(i: ButlerItem, now: Long) = i.title + (i.dueAt?.let { " (" + butler.describe(it, now) + (if (i.recur.isNotEmpty()) ", ${i.recur}" else "") + ")" } ?: "")
}

class TaskDoneTool(private val butler: Butler, private val scheduler: () -> ReminderScheduler?) : Tool {
    override val name = "task.done"
    override val description = "Mark a list item done."
    override val argsExample = """{"title":"milk"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("title") ?: return ToolResult.fail("Which item?")
        val list = args.str("list")?.let(Butler::normaliseList)
        val item = butler.complete(q, list) ?: return ToolResult.fail("I couldn't find \"$q\" on your ${list?.let(::label) ?: "lists"}.")
        scheduler()?.reschedule()
        return ToolResult.ok("Done: ${item.title}.", verified = item.done)
    }
}

class ReminderSetTool(private val butler: Butler, private val zone: () -> ZoneId, private val scheduler: () -> ReminderScheduler?) : Tool {
    override val name = "reminder.set"
    override val description = "Set a reminder."
    override val argsExample = """{"what":"call Ravi","when":"tomorrow 9 am"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val what = args.str("what") ?: return ToolResult.fail("What should I remind you about?")
        val whenText = args.str("when") ?: return ToolResult.fail("When should I remind you?")
        val w = Butler.parseWhen(whenText, ctx.now, zone()) ?: return ToolResult.fail("I couldn't understand when: \"$whenText\". Try \"at 6 pm\", \"in 20 minutes\" or \"tomorrow at 9\".")
        val item = butler.add(what, Butler.TASKS, w.at, w.recur)
        val s = scheduler()
        s?.reschedule()
        val desc = butler.describe(w.at, ctx.now) + if (w.recur.isNotEmpty()) ", then ${if (w.recur == "daily") "every day" else "every week"}" else ""
        // Without a delivery mechanism (no platform scheduler) the reminder is kept but can't ring: said honestly.
        return if (s != null) ToolResult.ok("I'll remind you to ${item.title} $desc.", verified = butler.get(item.id) != null)
            else ToolResult(true, false, "Saved \"${item.title}\" for $desc, but this device can't deliver reminders.", spoken = "I've noted it for $desc, but I can't ring a reminder on this device.")
    }
}

class BriefingTool(private val brief: DayBrief) : Tool {
    override val name = "butler.briefing"
    override val description = "Today's calendar, reminders and open lists."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.ok(brief.morning(ctx.now))
}

class EveningTool(private val brief: DayBrief) : Tool {
    override val name = "butler.evening"
    override val description = "Summary of the day: done, open, tomorrow."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.ok(brief.evening(ctx.now))
}

private fun label(list: String) = when (list) { Butler.TASKS -> "tasks"; Butler.SHOPPING -> "shopping list"; else -> "$list list" }
