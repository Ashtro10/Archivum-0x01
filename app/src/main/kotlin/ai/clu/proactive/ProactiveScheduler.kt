package ai.clu.proactive

import ai.clu.CluApp
import ai.clu.core.config.ServicePolicy
import ai.clu.core.conversation.MessageMeta
import ai.clu.core.conversation.Role
import ai.clu.core.proactive.ProactiveContext
import ai.clu.ui.MainActivity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

/** Periodic (≈hourly, OS-batched) evaluation of proactive messages; also run when the app opens. */
object ProactiveScheduler {
    private const val JOB_ID = 4711

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, ProactiveJob::class.java)).setPeriodic(60 * 60_000L).setPersisted(false).build())
    }

    /** Evaluates the planner and delivers a message if one is due. Returns the delivered text. */
    fun evaluate(app: CluApp, appOpenedNow: Boolean): String? {
        val bm = app.getSystemService(BatteryManager::class.java)
        val ctx = ProactiveContext(app.inForeground, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging, app.lastInteractionAt, appOpenedNow)
        val msg = app.core.proactive.plan(ctx) ?: return null
        // Proactive messages become part of the conversation history.
        app.core.conversations.addMessage(app.currentConversation(), Role.ASSISTANT, msg.text, MessageMeta(source = "template", tools = listOf("proactive:${msg.kind}")))
        when (ServicePolicy.proactiveDelivery(app.core.settings, app.inForeground)) {
            ServicePolicy.ProactiveDelivery.SPEAK -> app.voice.say(msg.text)
            ServicePolicy.ProactiveDelivery.NOTIFICATION -> notify(app, msg.text)
        }
        return msg.text
    }

    private fun notify(app: CluApp, text: String) {
        val pi = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, CluApp.CH_CLU).setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(app.core.settings.string(ai.clu.core.config.Settings.CLU_NAME))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify(100, n) }
    }
}

class ProactiveJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as CluApp
        if (!app.isReady) return false
        app.scope.launch(CoroutineExceptionHandler { _, e -> app.core.diag.e("proactive", "job failed", e); jobFinished(params, false) }) {
            ProactiveScheduler.evaluate(app, appOpenedNow = false)
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
