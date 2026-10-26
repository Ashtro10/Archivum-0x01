package androidx.core.content

import android.content.Context

/**
 * Minimal stand-in for androidx.core's ContextCompat: the LiveKit Android SDK calls only checkSelfPermission (when it
 * creates the microphone track). AndroidX is not available to this build (Google Maven is unreachable); on API 33+
 * the platform method does exactly what ContextCompat does.
 */
object ContextCompat {
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = context.checkSelfPermission(permission)
}
