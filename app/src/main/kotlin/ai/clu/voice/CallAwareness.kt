package ai.clu.voice

import ai.clu.CluApp
import ai.clu.core.notify.CallAnnouncer
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Incoming-call announcements. Android gives the caller's number to apps in only two legitimate ways: the call log
 * (READ_CALL_LOG, restricted by Play policy) or the call-screening role, which the owner grants once in a system dialog.
 * CLU uses the role: it allows every call unchanged (it never blocks, silences or answers), looks the number up in the
 * owner's contacts (READ_CONTACTS) and, if announcements are on and it isn't quiet hours, says who is calling.
 * Unknown numbers are announced as "unknown number" (never read out digit by digit) or not at all.
 */
class CluCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        // Always let the call through exactly as it is.
        respondToCall(details, CallResponse.Builder().build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val app = application as? CluApp ?: return
        if (!app.isReady) return
        val number = details.handle?.schemeSpecificPart
        val name = number?.let { contactName(this, it) }
        CallAnnouncer.text(name, number, app.core.settings, System.currentTimeMillis())?.let { text ->
            app.core.diag.i("calls", "incoming call announced (${if (name != null) "contact" else "unknown"})") // no number, no name in logs
            app.voice.say(text)
        }
    }

    companion object {
        fun contactName(context: Context, number: String): String? {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            return runCatching { context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        }

        fun roleHeld(context: Context): Boolean = context.getSystemService(RoleManager::class.java)?.let {
            it.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && it.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) } ?: false
    }
}

/**
 * Call-state awareness without any phone permission: Android reports the audio mode (ringing, in a call) to every app.
 * When a phone or VoIP call starts, CLU stops speaking and stops listening so it never talks over a call or records it;
 * the owner starts voice again when they want (CLU doesn't silently reopen the microphone).
 */
class CallStateWatcher(private val app: CluApp) {
    private val audio = app.getSystemService(AudioManager::class.java)
    @Volatile var inCall = false; private set
    private val listener = AudioManager.OnModeChangedListener { mode -> onMode(mode) }

    fun start() = runCatching { audio.addOnModeChangedListener(app.mainExecutor, listener) }.onFailure { app.core.diag.w("calls", "call-state watch unavailable: ${it.message}") }

    /** Ringing: the microphone stops (a call announcement may still be spoken). Answered: CLU's speech stops too. */
    fun onMode(mode: Int) {
        val ringing = mode == AudioManager.MODE_RINGTONE
        val active = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING
        if (ringing || active) {
            if (!inCall) app.core.diag.i("calls", if (active) "phone call: CLU paused speech and listening" else "phone ringing: CLU stopped listening")
            inCall = true
            runCatching { if (app.voice.isRunning) app.voice.stop() }
            if (active) runCatching { app.speech.stop() }
        } else if (mode != AudioManager.MODE_IN_COMMUNICATION) inCall = false
    }
}
