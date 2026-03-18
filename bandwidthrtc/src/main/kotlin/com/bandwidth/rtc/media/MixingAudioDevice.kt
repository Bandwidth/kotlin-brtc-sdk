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

    private var micSampleCount = 0

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
                    var maxAbs = 0f
                    for (i in 0 until sampleCount) {
                        val low = bytes[i * 2].toInt() and 0xFF
                        val high = bytes[i * 2 + 1].toInt()
                        val sample = ((high shl 8) or low).toShort()
                        val f = sample.toFloat() / int16Max
                        floatSamples[i] = f
                        val abs = if (f < 0) -f else f
                        if (abs > maxAbs) maxAbs = abs
                    }
                    // Log mic level every ~100 callbacks (~1s at 10ms/callback)
                    micSampleCount++
                    if (micSampleCount % 100 == 1) {
                        log.debug("Mic capture: maxAbs=${"%.4f".format(maxAbs)}, samples=$sampleCount (callback #$micSampleCount)")
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
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                    log.error("AudioTrack init error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    errorMessage: String
                ) {
                    log.error("AudioTrack start error ($errorCode): $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String) {
                    log.error("AudioTrack error: $errorMessage")
                }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                @Suppress("DEPRECATION")
                override fun onWebRtcAudioTrackStart() {
                    val mode = audioManager.mode
                    val modeStr = when (mode) {
                        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
                        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
                        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
                        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
                        else -> "UNKNOWN($mode)"
                    }
                    val voiceCallVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    val voiceCallMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    val musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    log.info("AudioTrack playout started — mode=$modeStr, voiceCallVol=$voiceCallVol/$voiceCallMax, musicVol=$musicVol/$musicMax, speakerOn=${audioManager.isSpeakerphoneOn}")
                }
                override fun onWebRtcAudioTrackStop() {
                    log.info("AudioTrack playout stopped")
                }
            })
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    log.error("AudioRecord init error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String
                ) {
                    log.error("AudioRecord start error ($errorCode): $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    log.error("AudioRecord error: $errorMessage")
                }
            })
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    log.info("AudioRecord capture started")
                }
                override fun onWebRtcAudioRecordStop() {
                    log.info("AudioRecord capture stopped")
                }
            })
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
