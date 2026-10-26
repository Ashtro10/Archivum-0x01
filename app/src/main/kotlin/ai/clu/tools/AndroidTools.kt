package ai.clu.tools

import ai.clu.core.config.AppPolicy
import ai.clu.core.config.SettingsStore
import ai.clu.core.persona.ClockTimeParser
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.*
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

private fun Context.startNew(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

class OpenAppTool(private val context: Context, private val settings: SettingsStore) : Tool {
    override val name = "app.open"
    override val description = "Open an installed app by name."
    override val argsExample = """{"app":"YouTube"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true

    data class App(val label: String, val pkg: String)

    fun installedApps(): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)).map {
            App(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.pkg }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("app") ?: return ToolResult.fail("Which app?")
        val apps = installedApps()
        val norm = q.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        val best = apps.map { a ->
            val l = a.label.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
            a to when { l == norm -> 1.0; l.startsWith(norm) || norm.startsWith(l) -> 0.9; l.contains(norm) -> 0.8
                else -> ai.clu.core.voice.WakePhraseMatcher.similarity(l, norm) }
        }.filter { it.second >= 0.72 }.maxByOrNull { it.second }?.first
            ?: return ToolResult.fail("I couldn't find an app called $q.")
        if (!AppPolicy.allowed(settings, best.label, best.pkg)) return ToolResult.fail("${best.label} isn't on the list of apps I'm allowed to open.")
        val launch = context.packageManager.getLaunchIntentForPackage(best.pkg) ?: return ToolResult.fail("${best.label} has no launchable screen.")
        return if (context.startNew(launch)) ToolResult.ok("Opened ${best.label}.", verified = true, spoken = "Opening ${best.label}.")
        else ToolResult.fail("Android refused to open ${best.label}.")
    }
}

class AlarmTool(private val context: Context) : Tool {
    override val name = "alarm.set"
    override val description = "Set an alarm in the clock app."
    override val argsExample = """{"time":"6:30 am","label":"Gym"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val t = args.str("time") ?: return ToolResult.fail("For what time?")
        val (h, m) = ClockTimeParser.parse(t) ?: return ToolResult.fail("I couldn't understand the time \"$t\".")
        val am = context.getSystemService(AlarmManager::class.java)
        val before = am.nextAlarmClock?.triggerTime
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!context.startNew(i)) return ToolResult.fail("No clock app accepted the alarm request.")
        val label = "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
        // Verify through the system's next-alarm record (works for clock apps that register with AlarmManager).
        val verified = withTimeoutOrNull(3000) {
            while (true) {
                val next = am.nextAlarmClock?.triggerTime
                if (next != null && next != before) {
                    val c = Calendar.getInstance().apply { timeInMillis = next }
                    if (c.get(Calendar.HOUR_OF_DAY) == h && c.get(Calendar.MINUTE) == m) return@withTimeoutOrNull true
                }
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        return if (verified) ToolResult.ok("Alarm set for $label (confirmed by the system).", verified = true, spoken = "Alarm set for $label.")
        else ToolResult(true, false, "Alarm request for $label sent to the clock app; I couldn't confirm it in the system alarm list.",
            spoken = "I've asked the clock app to set an alarm for $label, but I couldn't confirm it.")
    }
}

class TimerTool(private val context: Context) : Tool {
    override val name = "timer.set"
    override val description = "Start a countdown timer."
    override val argsExample = """{"duration":"10 minutes"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val d = args.str("duration") ?: return ToolResult.fail("For how long?")
        val sec = ClockTimeParser.parseDurationSec(d) ?: return ToolResult.fail("I couldn't understand \"$d\".")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val human = if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else if (sec >= 60) "${sec / 60} min${if (sec % 60 > 0) " ${sec % 60} s" else ""}" else "$sec seconds"
        return if (context.startNew(i)) ToolResult(true, false, "Timer for $human sent to the clock app.", spoken = "Timer set for $human.")
        else ToolResult.fail("No clock app accepted the timer.")
    }
}

class FlashlightTool(private val context: Context) : Tool {
    override val name = "device.flashlight"
    override val description = "Turn the flashlight on or off."
    override val argsExample = """{"state":"on"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val on = when (args.str("state")?.lowercase()) { "on", "true" -> true; "off", "false" -> false; else -> return ToolResult.fail("On or off?") }
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ToolResult.fail("This device has no flashlight.")
        val result = CompletableDeferred<Boolean>()
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id && enabled == on) result.complete(true) }
        }
        cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        return try {
            cm.setTorchMode(id, on)
            val ok = withTimeoutOrNull(1500) { result.await() } ?: false
            if (ok) ToolResult.ok("Flashlight ${if (on) "on" else "off"}.", spoken = "Flashlight ${if (on) "on" else "off"}.")
            else ToolResult(true, false, "Torch command sent; state change not confirmed.")
        } catch (e: Exception) { ToolResult.fail("The flashlight is unavailable (camera in use?).") } finally { cm.unregisterTorchCallback(cb) }
    }
}

class VolumeTool(private val context: Context) : Tool {
    override val name = "device.volume"
    override val description = "Set or change media volume (0-100)."
    override val argsExample = """{"level":"40"} or {"direction":"up"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = args.int("level")?.let { (it.coerceIn(0, 100) * max + 50) / 100 } ?: when (args.str("direction")) {
            "up" -> am.getStreamVolume(AudioManager.STREAM_MUSIC) + max / 10 + 1
            "down" -> am.getStreamVolume(AudioManager.STREAM_MUSIC) - max / 10 - 1
            else -> return ToolResult.fail("Which volume level?")
        }.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = now * 100 / max
        return if (now == target) ToolResult.ok("Media volume is now $pct%.") else ToolResult(true, false, "Volume change requested; the system reports $pct%.")
    }
}

class MapsOpenTool(private val context: Context) : Tool {
    override val name = "maps.open"
    override val description = "Open the maps app at a place, or with directions to a destination (live traffic and public transport are shown there). " +
        "mode: driving, walking, cycling or transit."
    override val argsExample = """{"destination":"Phoenix Mall Pune","mode":"transit"} or {"query":"coffee near me"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val dest = args.str("destination"); val q = args.str("query")
        val mode = ai.clu.core.tools.DistanceTool.normaliseMode(args.str("mode"))
        val origin = args.str("from")
        val uri = when {
            // Directions (not turn-by-turn) for transit or a given start point: Google Maps' public directions URL.
            dest != null && (mode != "driving" || origin != null) -> Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest) +
                (origin?.let { "&origin=" + Uri.encode(it) } ?: "") + "&travelmode=" + when (mode) { "walking" -> "walking"; "cycling" -> "bicycling"; "transit" -> "transit"; else -> "driving" })
            dest != null -> Uri.parse("google.navigation:q=" + Uri.encode(dest))
            q != null -> Uri.parse("geo:0,0?q=" + Uri.encode(q))
            else -> return ToolResult.fail("Where to?")
        }
        val ok = context.startNew(Intent(Intent.ACTION_VIEW, uri)) ||
            context.startNew(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest ?: q))))
        val what = dest ?: q
        return if (ok) ToolResult(true, false, "Opened maps for $what. Live route data is shown in the maps app; I did not retrieve it.",
            spoken = if (dest != null) "Opening navigation to $what." else "Showing $what on the map.")
        else ToolResult.fail("No maps app is installed.")
    }
}

class DialTool(private val context: Context) : Tool {
    override val name = "phone.dial"
    override val description = "Open the dialer with a number (does not place the call)."
    override val argsExample = """{"number":"+919876543210"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val n = args.str("number")?.takeIf { it.matches(Regex("[+0-9*#\\- ]{3,20}")) } ?: return ToolResult.fail("That isn't a valid phone number.")
        return if (context.startNew(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))) ToolResult(true, true, "Dialer opened with $n; you press call.",
            spoken = "Dialer ready with $n.") else ToolResult.fail("No dialer available.")
    }
}

class WebOpenTool(private val context: Context) : Tool {
    override val name = "web.open"
    override val description = "Open a web page in the browser."
    override val argsExample = """{"url":"https://www.irctc.co.in"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.str("url")?.let { if (it.startsWith("http")) it else "https://$it" } ?: return ToolResult.fail("Which page?")
        if (!url.matches(Regex("https?://[^\\s]+"))) return ToolResult.fail("That isn't a valid web address.")
        return if (context.startNew(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ToolResult(true, false, "Opened $url in the browser (content not read).",
            spoken = "Opening it in the browser.") else ToolResult.fail("No browser available.")
    }
}

class SettingsPanelTool(private val context: Context) : Tool {
    override val name = "device.settings"
    override val description = "Open a system settings panel: wifi, bluetooth, internet, volume, battery, display."
    override val argsExample = """{"panel":"wifi"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = when (args.str("panel")?.lowercase()) {
            "wifi", "wi-fi" -> Settings.Panel.ACTION_WIFI; "internet", "data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume", "sound" -> Settings.Panel.ACTION_VOLUME; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY; "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> return ToolResult.fail("Which settings panel?")
        }
        return if (context.startNew(Intent(action))) ToolResult(true, true, "Opened ${args.str("panel")} settings. Android requires you to toggle it yourself.",
            spoken = "Here are the ${args.str("panel")} settings.") else ToolResult.fail("That settings panel isn't available.")
    }
}

class CalendarAddTool(private val context: Context) : Tool {
    override val name = "calendar.add"
    override val description = "Prepare a calendar event (opens the calendar app to confirm)."
    override val argsExample = """{"title":"Dentist","date":"2026-11-02","time":"5 pm","minutes":"60"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title") ?: return ToolResult.fail("What's the event?")
        val cal = Calendar.getInstance()
        args.str("date")?.let { d -> runCatching { val p = d.split("-").map { it.toInt() }; cal.set(p[0], p[1] - 1, p[2]) }.onFailure { return ToolResult.fail("Date must be YYYY-MM-DD.") } }
        args.str("time")?.let { t -> val (h, m) = ClockTimeParser.parse(t) ?: return ToolResult.fail("Bad time"); cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m) }
        val mins = args.int("minutes") ?: 60
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + mins * 60_000L)
        return if (context.startNew(i)) ToolResult(true, false, "Calendar opened with \"$title\" prefilled; save it there to confirm.",
            spoken = "I've prepared the event. Please confirm it in your calendar.") else ToolResult.fail("No calendar app available.")
    }
}

class DeviceStatusTool(private val context: Context, private val online: () -> Boolean, private val extra: () -> String) : Tool {
    override val name = "device.status"
    override val description = "Battery, storage, connectivity and Clu's own status."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val st = StatFs(Environment.getDataDirectory().path)
        val freeGb = st.availableBytes / 1e9
        val s = "Battery $pct%${if (charging) " (charging)" else ""}, ${"%.1f".format(freeGb)} GB free storage, ${if (online()) "online" else "offline"}. ${extra()}"
        return ToolResult.ok(s.trim(), spoken = s.trim())
    }
}
