# CLAUDE.md — Bandwidth RTC Android SDK

Context file for AI assistants working in this repository.

---

## What this repo is

An Android library SDK (`bandwidthrtc-release.aar`) that lets Android apps join Bandwidth RTC sessions. It handles WebSocket signaling, WebRTC peer connection management, and audio I/O. The public API surface is intentionally small — apps call `connect`, `publish`, `unpublish`, and respond to callbacks.

---

## Project layout

```
kotlin-brtc-sdk/
├── VERSION                             # Semver string — the sole version source of truth
├── build.gradle.kts                    # Root: plugin version declarations only
├── settings.gradle.kts                 # Single module :bandwidthrtc
├── bandwidthrtc/
│   ├── build.gradle.kts                # Module build; reads version from VERSION file
│   └── src/main/kotlin/com/bandwidth/rtc/
│       ├── BandwidthRTC.kt             # Public API entry point
│       ├── media/
│       │   └── MixingAudioDevice.kt    # JavaAudioDeviceModule wrapper; audio routing + PCM tap
│       ├── signaling/
│       │   ├── SignalingClient.kt      # JSON-RPC 2.0 over WebSocket
│       │   ├── SignalingClientInterface.kt
│       │   ├── OkHttpWebSocket.kt      # OkHttp3 WebSocket implementation
│       │   ├── WebSocketInterface.kt   # Internal interface + event listener
│       │   └── rpc/                    # Request/response/notification message types
│       ├── types/                      # Data classes, enums, sealed error class
│       ├── util/
│       │   └── Logger.kt               # android.util.Log wrapper; tag = "BRTC"
│       └── webrtc/
│           ├── PeerConnectionManager.kt
│           └── PeerConnectionManagerInterface.kt
└── .github/workflows/
    ├── build.yml                       # Every push: VERSION bump check + build + test
    ├── draft_release.yml               # Merge to main: create draft GitHub release
    └── release_publish.yml             # Publish release: build + attach .aar
```

---

## Architecture

```
BandwidthRTC (public API)
  ├── SignalingClient  ──>  OkHttpWebSocket  ──>  Bandwidth Gateway (WSS)
  │     JSON-RPC 2.0 — coroutine-suspended calls + server push notifications
  ├── PeerConnectionManager
  │     ├── publishingPC   (send-only, Unified Plan, MAXBUNDLE)
  │     └── subscribingPC  (receive-only, server-driven renegotiation)
  └── MixingAudioDevice
        JavaAudioDeviceModule — mic PCM tap → onLocalAudioLevel callback
```

### Signaling flow

1. `connect()` opens WebSocket to the gateway, calls `setMediaPreferences` RPC which returns a pair of SDP offers (publish + subscribe).
2. SDK answers both offers through `PeerConnectionManager`.
3. Server pushes `sdpOffer` notifications for renegotiation (new streams joining); SDK answers each via `handleSubscribeSdpOffer`.
4. `publish()` waits for publish ICE to connect, adds audio tracks, then client-initiates offer/answer via `createPublishOffer` → `offerSdp` RPC → `applyPublishAnswer`.

### Peer connections

- **Two PCs**: `publishingPC` (send-only) and `subscribingPC` (receive-only).
- Both use Unified Plan SDP semantics, `MAXBUNDLE` bundle policy, RTCP-MUX required.
- No ICE trickle — all candidates are bundled in the SDP.
- `publishIceConnected` flag is set on `CONNECTED` or `COMPLETED`; `waitForPublishIceConnected()` polls at 50ms.
- Subscribe PC tracks `subscribeSdpRevision`; stale offers (revision ≤ current, after the first) are rejected.

### Data channels

`__heartbeat__` and `__diagnostics__` are created server-side (in SDP) and received via `onDataChannel`. Heartbeat responds to `PING` messages with `PONG` automatically.

### Audio

- Hardware AEC is assumed via `AudioManager.MODE_IN_COMMUNICATION`; WebRTC software echo cancellation, noise suppression, AGC, and highpass filter are all **disabled** in `addLocalTracks`.
- `MixingAudioDevice` wraps `JavaAudioDeviceModule`. It taps raw 16-bit PCM from the mic and normalizes to `FloatArray` for `onLocalAudioLevel`.
- Speaker routing: `setCommunicationDevice` on API ≥ 31, deprecated `isSpeakerphoneOn` setter below that.

---

## Public API — `BandwidthRTC`

| Member | Type | Description |
|--------|------|-------------|
| `onStreamAvailable` | `((RtcStream) -> Unit)?` | Remote stream added |
| `onStreamUnavailable` | `((String) -> Unit)?` | Remote stream removed (stream ID) |
| `onReady` | `((ReadyMetadata) -> Unit)?` | Platform ready; carries `endpointId`, `deviceId`, etc. |
| `onRemoteDisconnected` | `(() -> Unit)?` | Subscribe ICE disconnected or failed |
| `onLocalAudioLevel` | `((FloatArray) -> Unit)?` | PCM level samples from mic |
| `onRemoteAudioLevel` | `((FloatArray) -> Unit)?` | PCM level samples from remote audio |
| `isConnected` | `Boolean` | Current session state |
| `connect(authParams, options?)` | `suspend` | Opens WebSocket + sets up dual PCs |
| `disconnect()` | `suspend` | Tears down everything |
| `publish(audio, alias?)` | `suspend` | Publishes mic audio; returns `RtcStream` |
| `unpublish(stream)` | `suspend` | Removes tracks and renegotiates |
| `setMicEnabled(enabled)` | fun | Mute/unmute mic |
| `setSpeakerphoneOn(enabled)` | fun | Route audio to speaker or earpiece |
| `sendDtmf(tone)` | fun | DTMF via publish PC's audio sender |
| `getCallStats(prev, completion)` | fun | Async `CallStatsSnapshot` callback |
| `requestOutboundConnection(id, type)` | `suspend` | Dial a phone/endpoint |
| `hangupConnection(endpoint, type)` | `suspend` | Hang up a connection |
| `setLogLevel(level)` | fun | Runtime log verbosity |

`RtcOptions` lets callers override the WebSocket URL, ICE servers, and ICE transport policy.

---

## Key types

| Type | Location | Notes |
|------|----------|-------|
| `BandwidthRTCError` | `types/` | `sealed class` — all SDK errors extend this |
| `RtcAuthParams` | `types/` | Just `endpointToken: String` |
| `RtcOptions` | `types/` | Optional overrides for URL, ICE, transport policy |
| `RtcStream` | `types/` | Wraps `MediaStream`; exposes `streamId`, `mediaTypes`, `alias` |
| `ReadyMetadata` | `types/` | `endpointId`, `deviceId`, `territory`, `region` |
| `CallStatsSnapshot` | `types/` | Packet/byte/jitter/bitrate/RTT/codec snapshot |
| `StreamMetadata` | `types/` | Per-stream metadata from server: `endpointId`, `alias`, `mediaTypes` |
| `MediaType` | `types/` | `AUDIO`, `VIDEO` |
| `EndpointType` | `types/` | `ENDPOINT`, `CALL_ID`, `PHONE_NUMBER` |
| `PeerConnectionType` | `types/` | `PUBLISH`, `SUBSCRIBE` — internal only |

---

## Versioning

The `VERSION` file at the repo root is the **only source of truth** for the version. Gradle reads it at build time:

```kotlin
// bandwidthrtc/build.gradle.kts
version = rootProject.file("VERSION").readText().trim()
```

**Workflow:**

1. Bump `VERSION` on your feature branch (e.g. `1.0.0` → `1.0.1`)
2. Push — CI fails if `VERSION` is not strictly higher than `main`'s
3. Merge PR → CI creates a draft GitHub release tagged `v{VERSION}`
4. Review and publish the release → CI builds the AAR and attaches it as `BandwidthRTC-{VERSION}.aar`

Never hardcode version strings anywhere else in the codebase.

---

## Build commands

```bash
# Compile the library
./gradlew :bandwidthrtc:assembleDebug
./gradlew :bandwidthrtc:assembleRelease

# Run unit tests
./gradlew :bandwidthrtc:testDebugUnitTest
./gradlew :bandwidthrtc:testReleaseUnitTest

# Check for dependency/configuration issues
./gradlew :bandwidthrtc:dependencies
```

**Output artifact:** `bandwidthrtc/build/outputs/aar/bandwidthrtc-release.aar`

---

## Dependencies

| Dependency | Version | Scope |
|------------|---------|-------|
| `kotlinx-coroutines-core` | 1.7.3 | `implementation` |
| `kotlinx-coroutines-android` | 1.7.3 | `implementation` |
| `kotlinx-serialization-json` | 1.6.2 | `implementation` |
| `okhttp3:okhttp` | 4.12.0 | `implementation` |
| `stream-webrtc-android` | 1.3.7 | `api` (re-exported) |
| `junit` | 4.13.2 | `testImplementation` |
| `mockk` | 1.13.8 | `testImplementation` |
| `kotlinx-coroutines-test` | 1.7.3 | `testImplementation` |

`stream-webrtc-android` is exposed as `api` because consumers reference WebRTC types (e.g. `PeerConnection.IceConnectionState`, `MediaStream`).

---

## Toolchain

| Tool | Version |
|------|---------|
| Gradle | 9.0.0 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| JVM target | 17 |
| `compileSdk` | 34 |
| `minSdk` | 24 (Android 7.0) |

---

## Testing approach

Unit tests live in `bandwidthrtc/src/test/`. The test suite uses:

- **MockK** for mocking WebRTC classes (`PeerConnectionFactory`, `PeerConnection`, `MediaStream`, etc.)
- `mockkStatic` to intercept `PeerConnectionFactory.initialize()` and `PeerConnectionFactory.builder()` so no real native library is loaded
- `testOptions { unitTests.isReturnDefaultValues = true }` so unmocked Android framework calls return safe defaults
- `runTest` from `kotlinx-coroutines-test` for `suspend` function tests

`PeerConnectionManagerInterface` is the seam used to inject a mock into `BandwidthRTC` in tests, avoiding real WebRTC initialization.

---

## Logging

```kotlin
Logger.setLevel(LogLevel.DEBUG)  // OFF, ERROR, WARN, INFO, DEBUG, TRACE
Logger.logCallerInfo = true      // Prepends [ClassName.method] to each message
```

Tag in `logcat`: **`BRTC`**

---

## Android permissions (declared in the library manifest)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

`RECORD_AUDIO` is a dangerous permission — consuming apps must request it at runtime before calling `publish()`.

---

## Gateway

Default WebSocket URL (defined in `SignalingClient.kt`):
```
wss://gateway.pv.prod.global.aws.bandwidth.com/prod/gateway-service/api/v1/endpoints
```

Override via `RtcOptions.websocketUrl`. The client appends query params:
`?client=android&sdkVersion=0.1.0&uniqueId={uuid}&endpointToken={token}`

---

## Common pitfalls

- **`RECORD_AUDIO` not granted** — `publish()` will succeed at the WebRTC layer but produce a silent stream; no exception is thrown. Check permission before publishing.
- **Calling `publish()` before `connect()`** — throws `BandwidthRTCError.NotConnected`.
- **Calling `connect()` twice** — throws `BandwidthRTCError.AlreadyConnected`.
- **Modifying `VERSION` without bumping it** — CI will block the PR build with an error showing the minimum required version.
- **Dual PC ICE** — `waitForPublishIceConnected()` polls indefinitely; if the publish PC never reaches `CONNECTED`/`COMPLETED`, `publish()` will hang. Ensure ICE servers are reachable and `RECORD_AUDIO` permission is granted.
