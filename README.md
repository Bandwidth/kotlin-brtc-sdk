# Bandwidth RTC Android SDK

An Android SDK for building real-time audio communication apps on the [Bandwidth](https://www.bandwidth.com) platform. Wraps WebRTC and connects to the Bandwidth BRTC gateway over a JSON-RPC 2.0 WebSocket signaling channel. Distributed as an AAR library.

For product documentation, see the [Bandwidth RTC developer docs](https://dev.bandwidth.com/docs/brtc/).

---

## Requirements

- Android API 24+ (Android 7.0 Nougat)
- Kotlin 2.0+
- Gradle 9.0+
- AGP 8.7+
- JVM target 17

---

## Installation

### Gradle (AAR)

Download the latest `BandwidthRTC-{VERSION}.aar` from the [GitHub Releases](../../releases) page and add it to your project:

1. Place the `.aar` in your module's `libs/` directory.
2. In your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/BandwidthRTC-1.0.0.aar"))

    // Required transitive dependencies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("io.getstream:stream-webrtc-android:1.3.7")
}
```

---

## Permissions

The SDK declares the following permissions in its manifest — they will be merged automatically into your app's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

> **Important:** `RECORD_AUDIO` is a dangerous permission. Your app must request it at runtime before calling `publish()`. If permission is not granted, `publish()` will succeed but produce a silent stream with no error thrown.

---

## Quick Start

```kotlin
import com.bandwidth.rtc.BandwidthRTC
import com.bandwidth.rtc.types.RtcAuthParams

class CallService(context: Context) {
    private val brtc = BandwidthRTC(context)

    suspend fun startCall(token: String) {
        // Called when a remote participant starts streaming
        brtc.onStreamAvailable = { stream ->
            Log.d("App", "Remote stream available: ${stream.streamId}")
        }

        // Called when a remote participant stops streaming
        brtc.onStreamUnavailable = { streamId ->
            Log.d("App", "Remote stream removed: $streamId")
        }

        // Called once the gateway signals readiness
        brtc.onReady = { metadata ->
            Log.d("App", "Connected — endpointId: ${metadata.endpointId}")
        }

        // Called if the remote side disconnects
        brtc.onRemoteDisconnected = {
            Log.d("App", "Remote side disconnected")
        }

        // Connect and publish local microphone audio
        brtc.connect(RtcAuthParams(endpointToken = token))
        val localStream = brtc.publish(audio = true)
        Log.d("App", "Publishing local audio: ${localStream.streamId}")
    }

    suspend fun endCall() {
        brtc.disconnect()
    }
}
```

---

## API Reference

### `BandwidthRTC`

The main SDK entry point.

```kotlin
val brtc = BandwidthRTC(context: Context, logLevel: LogLevel = LogLevel.WARN)
```

#### Callbacks

| Property | Signature | When it fires |
|---|---|---|
| `onReady` | `((ReadyMetadata) -> Unit)?` | Gateway signals the endpoint is ready |
| `onStreamAvailable` | `((RtcStream) -> Unit)?` | A remote participant begins streaming |
| `onStreamUnavailable` | `((String) -> Unit)?` | A remote participant stops streaming (stream ID passed) |
| `onRemoteDisconnected` | `(() -> Unit)?` | Subscribe ICE disconnected or failed |
| `onLocalAudioLevel` | `((FloatArray) -> Unit)?` | Per-chunk Float32 mic samples (for visualization) |
| `onRemoteAudioLevel` | `((FloatArray) -> Unit)?` | Per-chunk Float32 remote playout samples (for visualization) |

#### Methods

| Method | Description |
|---|---|
| `suspend connect(authParams, options?)` | Opens WebSocket, negotiates dual peer connections |
| `suspend disconnect()` | Tears down all connections and releases resources |
| `suspend publish(audio, alias?)` | Publishes mic audio; returns the local `RtcStream` |
| `suspend unpublish(stream)` | Removes tracks and renegotiates |
| `fun setMicEnabled(enabled)` | Mute or unmute the microphone |
| `fun setSpeakerphoneOn(enabled)` | Route audio to speakerphone or earpiece |
| `fun sendDtmf(tone)` | Send DTMF tones over the publish peer connection |
| `fun getCallStats(prev, completion)` | Async `CallStatsSnapshot` callback |
| `suspend requestOutboundConnection(id, type)` | Dial a phone number, endpoint, or call ID |
| `suspend hangupConnection(endpoint, type)` | Hang up an outbound connection |
| `fun setLogLevel(level)` | Change runtime log verbosity |

#### State

| Property | Type | Description |
|---|---|---|
| `isConnected` | `Boolean` | Whether the SDK has an active session |

---

### `RtcAuthParams`

```kotlin
RtcAuthParams(endpointToken: String)
```

A JWT endpoint token issued by the Bandwidth platform. Pass it to `connect()`.

---

### `RtcOptions`

Optional overrides for connection behavior. All fields are optional.

| Field | Type | Default | Description |
|---|---|---|---|
| `websocketUrl` | `String?` | `null` | Override the default BRTC gateway WebSocket URL |
| `iceServers` | `List<PeerConnection.IceServer>?` | `null` | Custom STUN/TURN servers. Uses WebRTC defaults when `null` |
| `iceTransportPolicy` | `PeerConnection.IceTransportsType?` | `null` (all) | Restrict ICE candidate types (e.g. `RELAY` to force TURN) |
| `audioProcessing` | `AudioProcessingOptions` | See below | Audio source, format, and processing configuration |

### `AudioProcessingOptions`

Nested inside `RtcOptions.audioProcessing`. All fields are optional.

| Field | Type | Default | Description |
|---|---|---|---|
| `enableHardwareAec` | `Boolean` | `false` | Enable hardware acoustic echo cancellation |
| `enableHardwareNoiseSuppressor` | `Boolean` | `false` | Enable hardware noise suppressor |
| `enableSoftwareEchoCancellation` | `Boolean` | `false` | Enable WebRTC software echo cancellation |
| `enableSoftwareNoiseSuppression` | `Boolean` | `false` | Enable WebRTC software noise suppression |
| `enableAutoGainControl` | `Boolean` | `false` | Enable WebRTC automatic gain control |
| `enableHighpassFilter` | `Boolean` | `false` | Enable WebRTC highpass filter |
| `audioSource` | `Int` | `VOICE_COMMUNICATION` | Android `MediaRecorder.AudioSource`. `VOICE_COMMUNICATION` enables hardware AEC and AGC on most devices |
| `audioFormat` | `Int` | `ENCODING_PCM_16BIT` | PCM encoding format (`AudioFormat.ENCODING_*`) |
| `inputSampleRate` | `Int?` | `null` | Recording sample rate in Hz. `null` uses the device default |
| `outputSampleRate` | `Int?` | `null` | Playout sample rate in Hz. `null` uses the device default |
| `useStereoInput` | `Boolean` | `false` | Capture in stereo instead of mono |
| `useStereoOutput` | `Boolean` | `false` | Play back in stereo instead of mono |
| `useLowLatency` | `Boolean` | `false` | Request a low-latency audio path (API 26+). Reduces latency at the cost of higher CPU usage |
| `audioAttributes` | `AudioAttributes?` | `null` | Custom `AudioAttributes` for playback routing (e.g. to target a specific audio usage or content type) |

---

### `RtcStream`

Returned by `publish()` and delivered to `onStreamAvailable`.

| Property | Type | Description |
|---|---|---|
| `streamId` | `String` | Unique stream identifier |
| `mediaTypes` | `List<MediaType>` | `AUDIO`, `VIDEO` |
| `alias` | `String?` | Optional display name set at publish time |
| `mediaStream` | `MediaStream` | Underlying WebRTC `MediaStream` |

---

### `ReadyMetadata`

Delivered to `onReady`.

| Property | Type | Description |
|---|---|---|
| `endpointId` | `String?` | Endpoint identifier assigned by the gateway |
| `deviceId` | `String?` | Device identifier |
| `territory` | `String?` | Geographic territory |
| `region` | `String?` | Region |

---

### `CallStatsSnapshot`

Returned asynchronously by `getCallStats()`. Delta bitrates require passing the previous snapshot.

| Property | Description |
|---|---|
| `packetsReceived` | Inbound RTP packets received |
| `packetsLost` | Inbound RTP packets lost |
| `bytesReceived` | Inbound bytes received |
| `jitter` | Inbound jitter (seconds) |
| `audioLevel` | Inbound audio level (0.0–1.0 linear amplitude) |
| `packetsSent` | Outbound RTP packets sent |
| `bytesSent` | Outbound bytes sent |
| `roundTripTime` | RTT estimate (seconds) |
| `codec` | Active audio codec name |
| `inboundBitrate` | Derived inbound bitrate (bps) |
| `outboundBitrate` | Derived outbound bitrate (bps) |
| `timestamp` | Snapshot timestamp |

---

### `BandwidthRTCError`

All SDK errors extend `BandwidthRTCError` (a `sealed class` / `Exception`):

| Subclass | Thrown when |
|---|---|
| `InvalidToken` | Endpoint token is invalid or expired |
| `ConnectionFailed(detail)` | WebSocket or ICE connection could not be established |
| `SignalingError(detail)` | An unexpected signaling protocol error occurred |
| `WebSocketDisconnected` | WebSocket dropped unexpectedly |
| `SdpNegotiationFailed(detail)` | SDP offer/answer exchange failed |
| `MediaAccessDenied` | Microphone or camera permission denied |
| `AlreadyConnected` | `connect()` called while already connected |
| `NotConnected` | `publish()`, `unpublish()`, etc. called before `connect()` |
| `PublishFailed(detail)` | Track negotiation failed during `publish()` |
| `RpcError(code, message)` | The gateway returned a JSON-RPC error response |
| `NotSupported(detail)` | Operation not supported on this platform/version |
| `NoActiveCall` | `hangupConnection()` called with no active call |

---

## Audio Routing

By default, audio is routed to the earpiece (`AudioManager.MODE_IN_COMMUNICATION`). To route to the speakerphone:

```kotlin
brtc.setSpeakerphoneOn(true)
```

Hardware AEC is assumed — software echo cancellation, noise suppression, AGC, and highpass filter are all **disabled** at the WebRTC layer to avoid double-processing.

---

## Audio Level Visualization

The SDK exposes raw PCM samples for waveform rendering:

```kotlin
brtc.onLocalAudioLevel = { samples: FloatArray ->
    // Float32 samples from the mic, normalized 0.0–1.0
    renderWaveform(samples)
}

brtc.onRemoteAudioLevel = { samples: FloatArray ->
    // Float32 samples derived from inbound RTP audio level stat
    renderWaveform(samples)
}
```

---

## Call Control

To place an outbound call to a phone number:

```kotlin
val phoneNumber = "+15551234567"

val result = brtc.requestOutboundConnection(
    id = phoneNumber,
    type = EndpointType.PHONE_NUMBER
)

// Later, to hang up:
brtc.hangupConnection(endpoint = phoneNumber, type = EndpointType.PHONE_NUMBER)
```

`EndpointType` values: `ENDPOINT`, `CALL_ID`, `PHONE_NUMBER`.

---

## Logging

```kotlin
import com.bandwidth.rtc.util.LogLevel

// At construction time
val brtc = BandwidthRTC(context, logLevel = LogLevel.DEBUG)

// Or at runtime
brtc.setLogLevel(LogLevel.TRACE)
```

Log levels: `OFF`, `ERROR`, `WARN` (default), `INFO`, `DEBUG`, `TRACE`.

All log output appears in `logcat` under the tag **`BRTC`**.

---

## Common Pitfalls

- **`RECORD_AUDIO` not granted** — `publish()` succeeds but produces a silent stream with no exception.
- **`connect()` called twice** — throws `BandwidthRTCError.AlreadyConnected`.
- **`publish()` before `connect()`** — throws `BandwidthRTCError.NotConnected`.
- **`publish()` hangs** — `waitForPublishIceConnected()` polls indefinitely. Ensure ICE servers are reachable and `RECORD_AUDIO` is granted.
- **Background coroutine scope** — `connect()` and `publish()` are `suspend` functions; call them from a coroutine with an appropriate lifecycle scope (e.g. `viewModelScope`).

---

## Samples

Sample apps can be found in [Bandwidth-Samples](https://github.com/Bandwidth-Samples).

---

## Compatibility

This SDK follows [SemVer 2.0.0](https://semver.org/#semantic-versioning-200).

---

## Contributing

> **Every PR must bump the `VERSION` file.** CI will fail if you don't.

1. Make your changes on a feature branch.
2. Open `VERSION` at the repo root and increment the version (e.g. `1.0.1` → `1.0.2`). Use patch for bug fixes, minor for new features, major for breaking changes.
3. Open your PR — `build.yml` enforces that the new version is strictly greater than `main`.

See the [versioning section in CLAUDE.md](CLAUDE.md#versioning) for the full release workflow.

### Building

```bash
# Compile the library
./gradlew :bandwidthrtc:assembleDebug
./gradlew :bandwidthrtc:assembleRelease

# Run unit tests
./gradlew :bandwidthrtc:testDebugUnitTest
./gradlew :bandwidthrtc:testReleaseUnitTest
```

Output artifact: `bandwidthrtc/build/outputs/aar/bandwidthrtc-release.aar`

### Running Unit Tests in Android Studio

1. Open the project in Android Studio.
2. In the **Project** panel, navigate to `bandwidthrtc/src/test/`.
3. Right-click any test class and select **Run Tests**.

Or from the terminal:

```bash
./gradlew :bandwidthrtc:testDebugUnitTest
```
