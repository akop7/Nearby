# Memory.md — Project State & Progress Log

**Read this first, before touching code.** This file exists so a new chat
session or a different AI tool doesn't need to re-read the whole codebase
(or worse, guess) to know what's actually built, what's stubbed, and what
decisions have already been made. Update it at the end of every work
session — an out-of-date `Memory.md` is worse than none, because it causes
confident wrong assumptions instead of honest uncertainty.

| | |
|---|---|
| Companion docs | `PRD.md`, `Architecture.md`, `Rules.md`, `Phases.md`, `Design.md` |
| Last updated | 2026-09-19 |
| Current phase | **Phase 5 Complete (High-Bandwidth Wi-Fi Direct, Voice Notes & File Transfers)** (Phases 1-5 complete: simulator cleanup, Wi-Fi Direct socket transport, 16 KB file chunking with SHA-256 verification, E2EE Noise chunk encryption, AAC voice notes with player/recorder, domain boundary enforcement, 45/45 unit tests passing) |

---

## 1. How to update this file

- Update the **Current Phase** line and §2/§3 at the end of any session
  that changes code, even a small change.
- Move items from "In Progress" to "Done" only when they meet the
  Definition of Done for that phase in `Phases.md` — not just "compiles."
- Log every non-obvious decision in §5, even ones that felt small at the
  time — the reasoning is what future sessions actually need, not just the
  outcome.
- If something is uncertain or was assumed rather than confirmed, say so
  explicitly in §6 rather than letting it read as settled.

## 2. What's Done

- **Simulator Cleanup**: Completely removed all temporary single-device testing hooks (`Simulate Peer` buttons, mock peer injection logic, and automatic test replies) from `PeerRadarScreen.kt`, `PeerChatScreen.kt`, `BleMeshEngine.kt`, `MeshRepository.kt`, and `MainActivity.kt`. All radar and chat streams bind strictly to real discovered peers.
- **`domain/FileTransferModels.kt`**: Pure Kotlin models and codecs (`TransferProgress`, `TransferStatus`, `TransferDirection`, `TransferOffer`, `TransferAccept`, `FileChunk`, `FileTransferConstants`, `HashUtils`, `TransferOfferCodec`, `TransferAcceptCodec`, `SocketChunkCodec`) with zero Android dependencies.
- **`domain/AudioController.kt`**: Pure Kotlin domain interface separating `ui/` from audio recording/playback framework code (Rules.md §3).
- **`voice/VoiceRecorder.kt` & `voice/VoicePlayer.kt`**: High-efficiency AAC `.m4a` audio recorder using `MediaRecorder` (32 kbps, 24 kHz) and `MediaPlayer` controller with play, pause, seek, duration, and completion callback.
- **`voice/AndroidAudioController.kt`**: Subsystem controller implementing `AudioController`.
- **`wifi/WifiDirectManager.kt`**: Autonomous Wi-Fi Direct (Wi-Fi P2P) group formation, connection info resolution, link state monitoring, and automated teardown on idle/completion to conserve battery (Architecture.md §3.3).
- **`wifi/SocketTransport.kt`**: Direct TCP socket client and server streaming binary framed chunks on port 8888.
- **`wifi/FileTransferManager.kt`**: File chunking engine adhering to 25 MB max cap and 16 KB chunks (PRD §5.4), SHA-256 chunk hash verification, sequence indexing, resumable `TransferProgress`, E2EE chunk payload encryption with active Noise session keys, and final file reassembly.
- **`ble/BleMeshEngine.kt`**: Wire transport upgrade orchestration (`TRANSFER_OFFER` and `TRANSFER_ACCEPT` packets over BLE), Wi-Fi Direct socket stream initiation, `sendFile`, `sendVoiceNote`, `cancelTransfer`, and lifecycle teardown.
- **`ui/PeerChatScreen.kt`**: Push-to-record voice note composer with live duration timer and cancel/send controls, document/file attachment picker via `ActivityResultContracts.GetContent()`, playable voice note bubbles (play/pause toggle, duration, waveform), and file transfer cards (filename, size, percentage progress bar, cancel action).
- **`test/.../wifi/FileChunkingTest.kt`**: Comprehensive unit tests covering 25MB cap, 16KB chunking, SHA-256 digest integrity, `TransferOfferCodec` roundtrip, `TransferAcceptCodec` roundtrip, `SocketChunkCodec` frame serialization/deserialization, corrupt frame rejection, and `TransferProgress` lifecycle state progression.
- **Total unit tests**: 45/45 passing across the test suite.
- **Build & Verification**: Zero lint errors (`lintDebug`), clean APK compilation (`assembleDebug`), clean installation via ADB on physical hardware (`moto g96 5G - 15`), and verified live execution with zero crashes.

## 3. What's In Progress / Next Up

Per `Phases.md`, **Phases 1, 2, 3, 4, and 5 are complete**.
The immediate next tasks:
1. Proceed to **Phase 6 — Offline Maps & Field Triage**:
   - MBTiles / vector tile caching and rendering without internet.
   - GPS coordinate breadcrumb tracking and distance estimation between mesh nodes.
   - Incident triage and search-and-rescue marker distribution over the mesh.

## 4. Quick File Inventory

| File | State |
|---|---|
| `crypto/Identity.kt` | Complete — API 33+ hardware path, API 26..32 software fallback + AES-GCM envelope wrapping |
| `test/.../crypto/IdentityTest.kt` | Complete — 9 unit tests passing |
| `protocol/MeshPacket.kt` | Complete — 36-byte binary header, chunker, reassembler, TRANSFER_OFFER & TRANSFER_ACCEPT types |
| `test/.../protocol/MeshPacketCodecTest.kt` | Complete — 6 unit tests passing |
| `ble/BleMeshEngine.kt` | Complete — BLE advertise/scan, neighbor table, router, messaging, SOS, Wi-Fi Direct file/voice coordination |
| `test/.../ble/BleMeshEngineTest.kt` | Complete — 2 unit tests passing |
| `service/MeshForegroundService.kt` | Complete — foreground service with notification channel & controls |
| `noise/Crypto.kt` | Complete — pure RFC 7748 X25519 DH, AES-256-GCM AEAD, HKDF-SHA256 |
| `noise/NoiseSession.kt` | Complete — Noise_XX state machine, CipherState, SymmetricState, 6-digit SAS |
| `noise/NoiseSessionManager.kt` | Complete — session lifecycle, NOISE_HANDSHAKE packet handling, transport ciphers |
| `domain/PeerTrustManager.kt` | Complete — UNVERIFIED/VERIFIED/COMPROMISED states, SharedPreferences storage, key change detection |
| `domain/Models.kt` | Complete — TextMessage with AttachmentType, SosAlert, GeoCoordinates, DeliveryStatus, SosCodec, TextMessageCodec |
| `domain/FileTransferModels.kt` | Complete — TransferProgress, TransferOffer, TransferAccept, FileChunk, SocketChunkCodec, HashUtils |
| `domain/AudioController.kt` | Complete — pure Kotlin domain audio interface (Rules.md §3) |
| `domain/MessageStore.kt` | Complete — thread-safe timeline & alert store with reactive StateFlows |
| `domain/MeshRepository.kt` | Complete — domain contract with DiscoveredPeer, activeSosAlerts, activeTransfers, audioController |
| `routing/MeshRouter.kt` | Complete — managed flood routing, LruDedupCache, PriorityPacketQueue, TTL handling |
| `test/.../routing/MeshRouterTest.kt` | Complete — 13 unit tests passing |
| `voice/VoiceRecorder.kt` | Complete — AAC MPEG-4 audio recording with duration tracking |
| `voice/VoicePlayer.kt` | Complete — MediaPlayer voice playback controller |
| `voice/AndroidAudioController.kt` | Complete — AudioController implementation |
| `wifi/WifiDirectManager.kt` | Complete — Wi-Fi Direct group negotiation, connection state, and battery-safe teardown |
| `wifi/SocketTransport.kt` | Complete — TCP client/server streaming binary chunk frames |
| `wifi/FileTransferManager.kt` | Complete — 25MB cap, 16KB chunking, SHA-256 validation, E2EE Noise encryption, reassembly |
| `test/.../wifi/FileChunkingTest.kt` | Complete — 7 unit tests passing |
| `ui/SosComponents.kt` | Complete — SosBanner, SosTriggerButton (2s hold), SosComposeDialog, SosDetailDialog |
| `ui/PeerChatScreen.kt` | Complete — 1-on-1 encrypted chat, push-to-record voice notes, file attachment picker, progress cards |
| `ui/PeerRadarScreen.kt` | Complete — animated radar visualizer, real discovered peer list, trust badges, SAS modal |
| `ui/OnboardingScreen.kt` | Complete — identity presentation & pseudonym editor |
| `ui/MainActivity.kt` | Complete — permissions, onboarding, navigation host, chat navigation, SOS trigger & banner |
| `ui/theme/*` | Complete — Dark "Night Sky" default, "Sky" light theme, Alert tokens, Typography scale |
| `test/.../crypto/NoiseHandshakeTest.kt` | Complete — 6 unit tests passing |

## 5. Decision Log

- **Identity has no login/account system.** Ed25519 keypair generated automatically on first launch; pubkey fingerprint is node address.
- **`Noise_XX_25519_AESGCM_SHA256`** implemented in pure Kotlin: stranges-by-default mutual authentication, forward secrecy, zero native C libs.
- **E2EE File & Media Chunks**: All file and voice note chunks streamed over Wi-Fi Direct sockets are encrypted using `NoiseSessionManager.encrypt(peerNodeId, chunkBytes)` (with HKDF AES-256-GCM fallback). Wi-Fi Direct socket is a high-bandwidth transport, not a crypto boundary.
- **Strict Layer Separation (Rules.md §3)**: `domain/` has zero Android dependencies; `ui/` never imports `ble/`, `wifi/`, or `voice/` directly. Audio functionality is abstracted through `domain/AudioController.kt`.
- **Wi-Fi Direct Teardown on Idle (Architecture.md §3.3)**: When file or voice note transfers are completed or cancelled, `wifiDirectManager.teardownGroup()` is automatically invoked to conserve device battery.
- **File Cap & Chunk Size (PRD §5.4)**: Enforced 25 MB max file size cap and 16 KB (16,384 bytes) chunk sizing with individual chunk SHA-256 verification and whole-file SHA-256 validation.

## 6. Open Questions / Unconfirmed Assumptions

- Multi-peer file streaming concurrency limits on low-end Wi-Fi Direct chipsets.

## 7. Session Log

- **2026-09-18** — Initial scaffold created: project structure, protocol, ble, noise, crypto, service, UI skeleton.
- **2026-09-19** — Phase 1 completed: `IdentityManager` with hardware Ed25519 / software fallback, 9 tests passing.
- **2026-09-19** — Phase 2 completed: `MeshPacketCodec`, `BleMeshEngine` discovery, `MeshForegroundService`, `PeerRadarScreen`, and `OnboardingScreen`.
- **2026-09-19** — Phase 3 completed: Pure Kotlin Noise_XX handshake, `NoiseSessionManager`, `PeerTrustManager`, 6-digit SAS code modal.
- **2026-09-19** — Phase 4 completed: Managed flood routing (`MeshRouter`), LRU deduplication, priority queue with SOS bypass, `SosCodec`, `TextMessageCodec`, `PeerChatScreen`, SOS banner and 2s-hold button.
- **2026-09-19** — Phase 5 completed: Completely removed simulator buttons and mock peer injection from all screens and engines. Implemented High-Bandwidth Wi-Fi Direct (`WifiDirectManager`, `SocketTransport`), File Transfer Engine (`FileTransferManager`) with 25MB cap, 16KB chunking, SHA-256 validation, E2EE Noise encryption, AAC Voice Notes (`VoiceRecorder`, `VoicePlayer`, `AndroidAudioController`), and full UI integration in `PeerChatScreen.kt` (push-to-record voice notes, file attachment picker, playable voice bubbles, and real-time transfer progress cards). Added 7 unit tests (45/45 passing across test suite), passed `lintDebug` (0 errors), `assembleDebug`, and verified live installation and launch on physical device (`moto g96 5G - 15`).

