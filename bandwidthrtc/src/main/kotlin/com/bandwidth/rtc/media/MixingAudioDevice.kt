package com.bandwidth.rtc.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.bandwidth.rtc.types.AudioProcessingOptions
import com.bandwidth.rtc.util.Logger
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Audio device wrapper that provides mic capture with visualization callbacks
 * for audio levels.
 *
 * Wraps [JavaAudioDeviceModule] to provide audio level callbacks for visualization.
 * Remote audio level monitoring is driven via WebRTC stats (inbound-rtp audioLevel).
 */
class MixingAudioDevice(context: Context, audioProcessing: AudioProcessingOptions = AudioProcessingOptions()) {

    private val log = Logger
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode

    /** Called with Float32 audio samples for visualization after each mic capture chunk. */
    var onLocalAudioLevel: ((FloatArray) -> Unit)? = null

    /** Called with Float32 audio samples for visualization after each remote audio playout chunk. */
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
            .setUseHardwareAcousticEchoCanceler(audioProcessing.enableHardwareAec)
            .setUseHardwareNoiseSuppressor(audioProcessing.enableHardwareNoiseSuppressor)
            .setAudioSource(audioProcessing.audioSource)
            .setAudioFormat(audioProcessing.audioFormat)
            .setUseStereoInput(audioProcessing.useStereoInput)
            .setUseStereoOutput(audioProcessing.useStereoOutput)
            .setUseLowLatency(audioProcessing.useLowLatency)
            .also { builder ->
                audioProcessing.inputSampleRate?.let { builder.setInputSampleRate(it) }
                audioProcessing.outputSampleRate?.let { builder.setOutputSampleRate(it) }
                audioProcessing.audioAttributes?.let { builder.setAudioAttributes(it) }
            }
            .createAudioDeviceModule()

        log.debug("MixingAudioDevice created (hardwareAec=${audioProcessing.enableHardwareAec}, hardwareNs=${audioProcessing.enableHardwareNoiseSuppressor})")
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
