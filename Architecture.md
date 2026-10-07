# Architecture: Nearby

**Offline Decentralized Emergency & Disaster Communication App**

| | |
|---|---|
| Status | Draft v0.1 |
| Companion doc | `PRD.md` |
| Platform | Android (Kotlin, minSdk 26 / API 26+) |
| Last updated | 2026-09-18 |

---

## 1. Architectural Principles

These constraints shape every decision below:

1. **No server, ever.** Nothing in this architecture assumes reachability to
   any backend. There is no API base URL to configure — there is no API.
2. **Layers degrade independently.** If Wi-Fi Direct negotiation fails, text
   messaging over BLE must be unaffected. If the Noise handshake with a
   specific peer hasn't completed yet, presence discovery and relay for
   *other* peers must be unaffected.
3. **Encrypt at the message layer, not just the link layer.** A relay node
   forwards ciphertext it cannot read. Transport security (if any) is a
   bonus, never the only protection.
4. **Identity is local state, not a server record.** The keypair on disk
   *is* the account. There is no remote source of truth to reconcile against.
5. **Everything is designed to be tested on a bench of real phones.**
   Anything whose correctness depends on real RF behavior (BLE range, Wi-Fi
   Direct group formation, background execution limits) is isolated behind
   an interface so it can be swapped for a fake in unit tests, and is
   explicitly called out for hardware validation.

## 2. High-Level System Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                              UI Layer (Compose)                       │
│   Peer Radar · Chat Timeline · Voice Recorder · Call Screen · Setup   │
└───────────────────────────────┬────────────────────────────────────┘
                                 │  StateFlow / events
┌───────────────────────────────▼────────────────────────────────────┐
│                          Domain / App Layer                          │
│   MeshRepository · MessageStore · PeerRepository · TransferManager   │
│   (pure Kotlin, no Android framework deps — unit-testable)           │
└───────┬───────────────┬───────────────┬───────────────┬─────────────┘
        │               │               │               │
┌───────▼──────┐ ┌──────▼───────┐ ┌─────▼──────┐ ┌───────▼────────┐
│   Security    │ │   Control &  │ │ High-Tput  │ │ Voice Streaming │
│   & Identity  │ │  Discovery   │ │ Data Layer │ │     Layer       │
│  (crypto/,    │ │   (ble/)     │ │  (wifi/)   │ │   (voice/)      │
│   noise/)     │ │              │ │            │ │                 │
│  Ed25519,     │ │  BLE scan/   │ │ Wi-Fi      │ │  WebRTC P2P     │
│  Noise_XX     │ │  advertise,  │ │ Direct /   │ │  socket mode,   │
│  handshakes   │ │  neighbor    │ │ Nearby     │ │  Opus codec     │
│               │ │  table, TTL  │ │ Connections│ │                 │
│               │ │  relay       │ │            │ │                 │
└───────────────┘ └──────────────┘ └────────────┘ └─────────────────┘
        │               │               │               │
┌───────▼───────────────▼───────────────▼───────────────▼─────────────┐
│         Android Framework: BluetoothAdapter, WifiP2pManager,          │
│         AndroidKeyStore, Foreground Service, WorkManager              │
└────────────────────────────────────────────────────────────────────┘
```

Everything below the "Domain / App Layer" line only talks to the layer
directly above it through a narrow Kotlin interface — the domain layer never
touches `android.bluetooth.*` or `android.net.wifi.*` directly. This is what
lets `MeshRepository` be unit-tested with a fake `BleTransport` on a laptop,
with real BLE only exercised in instrumented/hardware tests.

## 3. Layer Responsibilities

### 3.1 Security & Identity (`crypto/`, `noise/`)
- Generates and stores the device's Ed25519 identity keypair
  (`IdentityManager`).
- Runs Noise_XX handshakes per peer and maintains the resulting cipher state
  (`NoiseSession`, `NoiseSessionRegistry`).
- Exposes `encrypt(peer, plaintext) -> ciphertext` and
  `decrypt(peer, ciphertext) -> plaintext` to every other layer. No other
  layer implements its own crypto.

### 3.2 Control & Discovery Layer (`ble/`, `protocol/`)
- Owns BLE advertising, scanning, and the neighbor table
  (`BleMeshEngine`).
- Owns the wire format for small control/text packets: header, TTL/hop
  count, packet type, chunking (`MeshPacket.kt`).
- Implements flood-with-TTL relay and packet de-duplication.
- Emits a `TRANSFER_OFFER` packet when the app layer wants to start a
  bulk transfer or call, and listens for the peer's `TRANSFER_ACCEPT`
  before handing off to the Wi-Fi Direct layer.

### 3.3 High-Throughput Data Layer (`wifi/`)
- Negotiates a Wi-Fi Direct / Nearby Connections link on demand, triggered
  by an accepted `TRANSFER_OFFER`.
- Streams chunked file and voice-note payloads over that link.
- Tears the link back down once idle to conserve battery — this layer is
  *not* meant to stay active continuously the way BLE is.

### 3.4 Voice Streaming Layer (`voice/`)
- Sets up a WebRTC `PeerConnection` in local P2P socket mode over the
  already-established Wi-Fi Direct link (no STUN/TURN, host ICE candidates
  only, since both devices share a local subnet).
- Opus codec, tuned for low bitrate / low latency over a local link rather
  than internet conditions.

### 3.5 Domain / App Layer (`domain/`)
- `MeshRepository`: single source of truth for "who's reachable and how",
  merging BLE neighbor data with active Wi-Fi Direct/call sessions.
- `MessageStore`: local persistence (Room) for the chat timeline, SOS
  alerts, and transfer history. Purely on-device; never synced anywhere.
- `PeerRepository`: known-peer bookkeeping (display names, verified/
  unverified trust state, last-seen).
- `TransferManager`: orchestrates the offer → accept → Wi-Fi Direct
  handoff → chunked transfer → completion flow described in §3.3.
- This layer is plain Kotlin + coroutines/Flow, with the lower layers
  injected as interfaces, so it's testable without a device or emulator.

### 3.6 UI Layer (`ui/`)
- Jetpack Compose screens, one-way data flow from domain `StateFlow`s.
- Peer Radar, Chat Timeline, Voice Recorder, Call Screen, Onboarding/Setup,
  Mesh Diagnostics (per PRD §5.6).
- No screen talks to `ble/`, `wifi/`, or `voice/` directly — only to the
  domain layer.

### 3.7 Background Execution (`service/`)
- `MeshForegroundService`: keeps BLE advertising/scanning alive while the
  app is backgrounded; hosts the persistent "meshing active" notification.
- `WorkManager` jobs for periodic housekeeping that doesn't need to be
  instantaneous (e.g., pruning stale neighbor-table entries, re-requesting
  battery-optimization exemption prompts if revoked).

## 4. End-to-End Flow Examples

### 4.1 First launch
```
App start
  → IdentityManager.getOrCreateIdentity()      (crypto/)
      generates Ed25519 keypair if none exists
  → Runtime permission prompts (BLE, Nearby Wi-Fi, Notifications, Mic)
  → MeshForegroundService started
      → BleMeshEngine.startAdvertising() + startScanning()
  → PeerRadarScreen subscribes to MeshRepository.neighbors
```

### 4.2 Sending a broadcast SOS alert
```
User taps "SOS" in UI
  → domain: MeshRepository.sendAlert(payload)
  → protocol: wraps payload in MeshPacket(type = SOS_ALERT, ttl = high, dest = BROADCAST)
  → ble: BleMeshEngine.send(packet)  — no Noise encryption for broadcast alerts
         (broadcast, by definition, has no single recipient key to encrypt to;
          see §7 open question on broadcast confidentiality)
  → every neighbor: BleMeshEngine.onBytesReceived()
      → de-dup check → surfaces to local UI → forRelay() → re-broadcast
        until TTL exhausted
```

### 4.3 Sending a direct text message to a known, verified peer
```
User sends message to Peer B
  → domain: MessageStore.compose(peerB, text)
  → security: NoiseSession for peerB (handshake already completed at
    first contact) → encrypt(text) → ciphertext
  → protocol: MeshPacket(type = TEXT_MESSAGE, dest = peerB.nodeId, payload = ciphertext)
  → ble: flood-relay toward peerB (intermediate nodes cannot decrypt)
  → peerB: BleMeshEngine delivers → security: decrypt() → domain: MessageStore
    appends to timeline → UI updates
```

### 4.4 Sending a voice note / file
```
User attaches a file
  → domain: TransferManager.offer(peer, fileMeta)
  → protocol/ble: TRANSFER_OFFER packet sent over BLE (small, just metadata)
  → peer accepts → TRANSFER_ACCEPT sent back over BLE
  → wifi: both sides bring up a Wi-Fi Direct / Nearby Connections link
  → wifi: chunked, Noise-encrypted payload streamed over that link
  → wifi: link torn down once transfer completes or times out
  → domain: MessageStore records the completed transfer in the timeline
```

### 4.5 Placing a voice call
```
User taps "Call" on a peer
  → protocol/ble: TRANSFER_OFFER (type: call) sent over BLE
  → peer accepts (ringing UI) → TRANSFER_ACCEPT
  → wifi: Wi-Fi Direct link established (same handoff as 4.4)
  → voice: WebRTC PeerConnection opened over that local link, host-only
    ICE candidates, Opus
  → voice: duplex audio streaming until either side ends the call
  → wifi: link torn down after call ends
```

## 5. Technical Stack

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Standard for modern Android, coroutines/Flow fit the async, event-driven mesh model well |
| UI | Jetpack Compose | Declarative UI maps cleanly onto `StateFlow`-driven peer/message state |
| Async | Kotlin Coroutines + Flow | Natural fit for continuous BLE scan results, neighbor table updates, message streams |
| Local persistence | Room (SQLite) | On-device-only chat/transfer history; no sync layer needed |
| BLE | `android.bluetooth.le.*` directly, optionally Nordic's BLE scanner library | Fine-grained control over advertising/scanning needed for the custom mesh protocol; Nordic lib smooths over some OEM BLE quirks |
| High-throughput P2P | Google Play Services **Nearby Connections API** (wraps Wi-Fi Direct/Bluetooth) | Abstracts Wi-Fi Direct's flakier raw APIs; note Google's **late-2026 behavior change** requiring apps to explicitly prompt the user to enable Wi-Fi/Bluetooth themselves (the API will no longer auto-enable radios) — must be designed for from the start |
| Voice calling | WebRTC (`io.github.webrtc-sdk` Android build), Opus codec, local socket / host-candidate-only ICE | Mature, well-tested audio engine; avoids reinventing jitter buffering, echo cancellation, codec negotiation |
| Encryption | Noise Protocol Framework (`noise-java`), `Noise_XX_25519_ChaChaPoly_SHA256` | Purpose-built for exactly this "no CA, mutual auth, forward secrecy" P2P scenario; same pattern used by Bitchat |
| Identity keys | Ed25519, `AndroidKeyStore`-backed where available (API 33+), software fallback + Keystore-wrapped envelope encryption on API 26–32 | Hardware-backed key protection where the OS supports it, without raising `minSdk` past 26 |
| Background execution | Foreground `Service` + `WorkManager` | Foreground service is the only sanctioned way to keep BLE alive while backgrounded; WorkManager handles periodic non-urgent housekeeping |
| Dependency injection | Manual constructor injection (or Hilt, if the team prefers, once scope grows) | Keeps the domain layer's lower-layer interfaces easy to fake in tests without pulling in a heavy DI framework for v1 |
| Testing | JUnit + Kotlin coroutines-test for domain layer; Android instrumented tests + physical-device test plan for `ble/`, `wifi/`, `voice/` | Domain logic (routing, TTL, de-dup, message store) is fully unit-testable; radio behavior is not — must be hardware-validated per PRD §7 |
| Build | Gradle Kotlin DSL, single `:app` module for v1 | Simple enough for v1 scope; layers are separated by package + interface discipline rather than Gradle modules, with a note in §6 on when to split into real Gradle modules |

## 6. File & Folder Structure

```
nearby/
├── build.gradle.kts                # root: plugin versions
├── settings.gradle.kts             # module list
├── README.md
├── PRD.md
├── Architecture.md                 # this file
│
└── app/
    ├── build.gradle.kts            # dependencies: Compose, Nearby Connections,
    │                                #   noise-java, WebRTC, WorkManager, Room
    │
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml # full permission matrix (see README)
        │   │
        │   ├── java/org/nearby/mesh/
        │   │   ├── NearbyApp.kt            # Application class, top-level DI wiring
        │   │   │
        │   │   ├── crypto/                 # Security & Identity layer (§3.1)
        │   │   │   └── Identity.kt         #   IdentityManager, NodeIdentity
        │   │   │
        │   │   ├── noise/                  # Security & Identity layer (§3.1)
        │   │   │   └── NoiseSession.kt     #   NoiseSession, NoiseSessionRegistry
        │   │   │
        │   │   ├── protocol/               # Wire format, shared by ble/ and wifi/
        │   │   │   └── MeshPacket.kt       #   header, TTL, PacketType, codec, chunker
        │   │   │
        │   │   ├── ble/                    # Control & Discovery layer (§3.2)
        │   │   │   ├── BleMeshEngine.kt    #   advertise/scan, neighbor table, relay
        │   │   │   └── GattServer.kt       #   [planned] characteristic read/write
        │   │   │
        │   │   ├── wifi/                   # High-Throughput Data layer (§3.3)
        │   │   │   ├── TransferSession.kt  #   [planned] Nearby Connections wrapper
        │   │   │   └── ChunkedTransfer.kt  #   [planned] resume-capable file streaming
        │   │   │
        │   │   ├── voice/                  # Voice Streaming layer (§3.4)
        │   │   │   ├── CallSession.kt      #   [planned] WebRTC PeerConnection mgmt
        │   │   │   └── OpusConfig.kt       #   [planned] codec params for local links
        │   │   │
        │   │   ├── domain/                 # Domain / App layer (§3.5)
        │   │   │   ├── MeshRepository.kt   #   [planned] merged peer/session state
        │   │   │   ├── MessageStore.kt     #   [planned] Room entities + DAO
        │   │   │   ├── PeerRepository.kt   #   [planned] known-peer bookkeeping
        │   │   │   └── TransferManager.kt  #   [planned] offer/accept orchestration
        │   │   │
        │   │   ├── service/                # Background execution
        │   │   │   ├── MeshForegroundService.kt
        │   │   │   └── MeshMaintenanceWorker.kt  # [planned] WorkManager housekeeping
        │   │   │
        │   │   └── ui/                     # Compose UI layer (§3.6)
        │   │       ├── MainActivity.kt     # [planned] permission flow, nav host
        │   │       ├── PeerRadarScreen.kt
        │   │       ├── ChatTimelineScreen.kt   # [planned]
        │   │       ├── VoiceRecorderScreen.kt  # [planned]
        │   │       ├── CallScreen.kt           # [planned]
        │   │       └── OnboardingScreen.kt     # [planned]
        │   │
        │   └── res/
        │       ├── values/strings.xml, themes.xml
        │       └── drawable/               # notification icons, etc.
        │
        ├── test/                           # JUnit — domain/protocol layers
        │   └── java/org/nearby/mesh/
        │       ├── protocol/MeshPacketCodecTest.kt      # [planned]
        │       └── domain/MessageStoreTest.kt           # [planned]
        │
        └── androidTest/                    # instrumented + device-dependent tests
            └── java/org/nearby/mesh/
                ├── ble/BleMeshEngineDeviceTest.kt        # [planned]
                └── wifi/TransferSessionDeviceTest.kt     # [planned]
```

**Package = layer.** Every source file lives in the package matching the
architectural layer it belongs to (§3), so the folder structure *is* the
layer diagram. This is what makes the "domain layer never imports
`android.bluetooth.*`" rule enforceable by code review at a glance.

**When to split into Gradle modules:** v1 stays a single `:app` module
deliberately — splitting early adds build-config overhead the team doesn't
need yet. Revisit once `domain/` is stable and a second surface (e.g., a
wearable companion, or isolating `protocol/` as a publishable library for
interop testing) creates a real reason to enforce the layer boundaries at
the build-graph level instead of just via package convention.

## 7. Open Architectural Questions

- **Broadcast confidentiality**: SOS/broadcast alerts are currently
  unencrypted by design (no single recipient key to target). Is that
  acceptable, or should broadcasts be encrypted to a rotating
  "well-known" mesh-wide symmetric key at the cost of some forward secrecy?
- **Gradle module boundaries**: stay single-module through Phase 2, or split
  `protocol/` out now so it can be unit-tested and versioned independently?
- **Room schema migrations**: since there's no server to coordinate a
  rollout, how strict does on-device migration safety need to be across app
  updates in the field (a user mid-disaster should never lose message
  history to a bad migration)?
- **GATT server design**: `ble/GattServer.kt` (the marked TODO in
  `BleMeshEngine`) needs a concrete characteristic layout — single mesh
  characteristic with MTU-negotiated writes, or split characteristics per
  packet type? Affects both throughput and Android GATT stack quirks.
