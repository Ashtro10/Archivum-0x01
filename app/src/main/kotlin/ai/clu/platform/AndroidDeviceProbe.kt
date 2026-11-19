package ai.clu.platform

import ai.clu.core.ProcDeviceProbe
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What Android reports while the model runs: memory (PSS), thermal status, battery level/temperature/charging. */
class AndroidDeviceProbe(private val ctx: Context) : ProcDeviceProbe() {
    private fun battery(): Intent? = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    override fun memoryMb(): Double? = runCatching { android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.totalPss / 1024.0 }.getOrNull() ?: super.memoryMb()
    override fun thermal(): String? = if (Build.VERSION.SDK_INT < 29) null else when ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "none"; PowerManager.THERMAL_STATUS_LIGHT -> "light"; PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"; PowerManager.THERMAL_STATUS_CRITICAL -> "critical"; PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"; else -> "unknown"
    }
    override fun batteryPercent(): Int? = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    override fun batteryTempC(): Double? = battery()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
    override fun charging(): Boolean? = battery()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 }
    override fun describe(): JsonObject = buildJsonObject {
        super.describe().forEach { (k, v) -> put(k, v) }
        put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("device", Build.DEVICE)
        put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"); put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
        val mi = android.app.ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        put("ram_mb", mi.totalMem / 1_048_576.0); put("available_ram_mb_at_start", mi.availMem / 1_048_576.0)
    }
}
