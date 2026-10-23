package ai.clu.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot recognition through Android's SpeechRecognizer. "On-device" uses createOnDeviceSpeechRecognizer
 * (fails honestly if the device has no on-device model); the default recognizer may use its provider's network.
 */
class AndroidAsr(private val context: Context) {
    sealed class Result { data class Text(val text: String) : Result(); data class Error(val message: String) : Result() }

    fun onDeviceAvailable() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun recognizeOnce(onDevice: Boolean, timeoutMs: Long = 15_000): Result {
        val done = CompletableDeferred<Result>()
        val main = Handler(Looper.getMainLooper())
        var rec: SpeechRecognizer? = null
        main.post {
            try {
                if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    done.complete(Result.Error("This device has no on-device speech recognizer installed.")); return@post
                }
                if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
                    done.complete(Result.Error("No speech recognition service is installed.")); return@post
                }
                rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
                rec!!.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        done.complete(Result.Text(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""))
                    }
                    override fun onError(error: Int) {
                        done.complete(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Result.Text("")
                        else Result.Error("Speech recognizer error $error"))
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                rec!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice))
            } catch (e: Exception) { done.complete(Result.Error(e.message ?: "recognizer failed")) }
        }
        val r = withTimeoutOrNull(timeoutMs) { done.await() } ?: Result.Error("Speech recognizer timed out")
        main.post { runCatching { rec?.destroy() } }
        return r
    }
}
