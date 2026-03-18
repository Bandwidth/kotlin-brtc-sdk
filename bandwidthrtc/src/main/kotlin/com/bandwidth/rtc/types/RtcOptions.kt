package com.bandwidth.rtc.types

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaRecorder
import org.webrtc.PeerConnection

data class AudioProcessingOptions(
    // Hardware processing
    val enableHardwareAec: Boolean = false,
    val enableHardwareNoiseSuppressor: Boolean = false,
    // Software processing (WebRTC)
    val enableSoftwareEchoCancellation: Boolean = false,
    val enableSoftwareNoiseSuppression: Boolean = false,
    val enableAutoGainControl: Boolean = false,
    val enableHighpassFilter: Boolean = false,
    // Audio source / format
    val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    // Sample rate overrides (null = use device default)
    val inputSampleRate: Int? = null,
    val outputSampleRate: Int? = null,
    // Stereo (mono by default)
    val useStereoInput: Boolean = false,
    val useStereoOutput: Boolean = false,
    // Low latency mode (API 26+)
    val useLowLatency: Boolean = false,
    // Custom audio attributes for playback routing
    val audioAttributes: AudioAttributes? = null,
)

data class RtcOptions(
    val websocketUrl: String? = null,
    val iceServers: List<PeerConnection.IceServer>? = null,
    val iceTransportPolicy: PeerConnection.IceTransportsType? = null,
    val audioProcessing: AudioProcessingOptions = AudioProcessingOptions()
)
