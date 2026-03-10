package com.bandwidth.rtc.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.bandwidth.rtc.util.Logger
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Audio device wrapper that provides mic capture with visualization callbacks
 * for audio levels.
 *
 * On Android, WebRTC's JavaAudioDeviceModule handles the low-level audio I/O.
 * This class wraps it to provide audio level callbacks for visualization,
 * analogous to the Swift SDK's MixingAudioDevice.
 *
 * Note: The Android WebRTC SDK only provides a recording (mic) samples callback
 * via [JavaAudioDeviceModule.SamplesReadyCallback]. There is no built-in playout
 * (remote audio) samples callback. Remote audio level monitoring can be done via
 * WebRTC stats (inbound-rtp audioLevel) instead.
 */
class MixingAudioDevice(context: Context) {

    private val log = Logger
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode

    val supportsHardwareAec = false
    val supportsHardwareNs = false

    /** Called with Float32 audio samples for visualization after each mic capture chunk. */
    var onLocalAudioLevel: ((FloatArray) -> Unit)? = null

    /** Called with Float32 audio samples for visualization after each remote audio playout chunk.
     *  Note: On Android, this is driven by WebRTC stats polling rather than a direct playout tap. */
    var onRemoteAudioLevel: ((FloatArray) -> Unit)? = null

    /** The underlying WebRTC audio device module. */
    val audioDeviceModule: AudioDeviceModule

    init {
        // MODE_IN_COMMUNICATION enables hardware-level voice processing (AEC, routing)
        // and is required for VoIP. Without it the audio stack stays in MODE_NORMAL
        // which is optimized for media playback, not microphone capture.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setSamplesReadyCallback { samples ->
                // Mic capture samples — AudioSamples.getData() returns byte[] of 16-bit PCM
                val bytes = samples.data
                val sampleCount = bytes.size / 2 // 16-bit PCM = 2 bytes per sample
                if (sampleCount > 0) {
                    val floatSamples = FloatArray(sampleCount)
                    val int16Max = Short.MAX_VALUE.toFloat()
                    for (i in 0 until sampleCount) {
                        val low = bytes[i * 2].toInt() and 0xFF
                        val high = bytes[i * 2 + 1].toInt()
                        val sample = ((high shl 8) or low).toShort()
                        floatSamples[i] = sample.toFloat() / int16Max
                    }
                    onLocalAudioLevel?.invoke(floatSamples)
                }
            }
            .setUseHardwareAcousticEchoCanceler(supportsHardwareAec)
            .setUseHardwareNoiseSuppressor(supportsHardwareNs)
            .createAudioDeviceModule()

        log.debug("MixingAudioDevice created (hardwareAec=$supportsHardwareAec, hardwareNs=$supportsHardwareNs)")
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphoneOn(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speakerDevice = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            audioManager.isSpeakerphoneOn = enabled
        }
        log.debug("Speakerphone ${if (enabled) "on" else "off"}")
    }

    @Suppress("DEPRECATION")
    fun release() {
        audioManager.mode = previousAudioMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = false
        }
        audioDeviceModule.release()
        log.debug("MixingAudioDevice released")
    }
}
