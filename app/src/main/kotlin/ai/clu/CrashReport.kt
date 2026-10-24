package ai.clu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Records uncaught exceptions (startup or later) to a private file so they can be shown on the next launch.
 * Without a PC there is no logcat, so this is how a crash on a real device gets reported.
 */
object CrashReport {
    @Volatile var startupError: Throwable? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { save(app, "thread '${t.name}'", e) }
            previous?.uncaughtException(t, e)
        }
    }

    fun save(ctx: Context, where: String, e: Throwable) {
        file(ctx).writeText(header(ctx) + "Where: $where\n\n" + e.stackTraceToString())
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) { file(ctx).delete() }

    private fun header(ctx: Context): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        return "Clu $version crash report\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}\n" +
            "Time: ${java.time.Instant.now()}\n"
    }
}

/** Plain screen (no dependency on the Clu core, which may have failed to start) showing the last crash. */
class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.pending(this) ?: CrashReport.startupError?.stackTraceToString() ?: "No crash recorded."
        val started = (application as CluApp).isReady
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            text = if (started) "Clu crashed last time" else "Clu could not start"
        })
        root.addView(TextView(this).apply {
            textSize = 14f
            text = "Please tap Copy (or Share) and send this report to the developer. It contains the error and device model, " +
                "no conversations or memories."
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clu crash report", report))
                Toast.makeText(this@CrashReportActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Clu crash report").putExtra(Intent.EXTRA_TEXT, report), "Share crash report"))
            }
        })
        buttons.addView(Button(this).apply {
            text = if (started) "Dismiss" else "Close"
            setOnClickListener {
                CrashReport.clear(this@CrashReportActivity)
                if (started) { startActivity(Intent(this@CrashReportActivity, ai.clu.ui.MainActivity::class.java)); finish() }
                else { finishAffinity(); Process.killProcess(Process.myPid()) }
            }
        })
        root.addView(buttons)
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashReportActivity).apply { text = report; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
