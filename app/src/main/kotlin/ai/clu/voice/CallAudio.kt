package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Communication audio mode for full-duplex conversation: MODE_IN_COMMUNICATION lets the platform echo canceller use
 * Clu's own output as its reference, so Clu's voice is removed from the microphone and the owner can interrupt.
 * Output goes to a connected headset (wired, USB, Bluetooth) or else the loudspeaker, and follows devices being
 * connected or removed while active. Reference counted: the offline voice session and LiveKit may both hold it.
 */
class CallAudio(context: Context, private val diag: DiagnosticsLog?) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var holders = 0
    private var savedMode: Int? = null
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = route()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = route()
    }

    @Synchronized fun enter() {
        if (holders++ > 0) return
        runCatching {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.registerAudioDeviceCallback(devices, null)
            route()
        }.onFailure { diag?.w("audio", "communication mode: ${it.message}") }
    }

    @Synchronized fun leave() {
        if (holders == 0 || --holders > 0) return
        runCatching { audio.unregisterAudioDeviceCallback(devices); audio.clearCommunicationDevice(); savedMode?.let { audio.mode = it } }
        savedMode = null
    }

    @Synchronized private fun route() {
        if (holders == 0) return
        runCatching {
            val available = audio.availableCommunicationDevices
            val target = available.firstOrNull { it.type in HEADSET_TYPES } ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (target != null && audio.communicationDevice?.id != target.id) {
                audio.setCommunicationDevice(target)
                diag?.i("audio", "speech output -> ${target.productName} (type ${target.type})")
            }
        }.onFailure { diag?.w("audio", "routing: ${it.message}") }
    }

    companion object {
        val HEADSET_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
