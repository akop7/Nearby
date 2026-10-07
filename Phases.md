# Phases.md — Build Phases for Nearby

**Breaks the project into increments small enough for an AI assistant (or a
human) to build, review, and validate one at a time.**

| | |
|---|---|
| Status | Draft v0.1 |
| Companion docs | `PRD.md`, `Architecture.md`, `Rules.md` |
| Last updated | 2026-09-18 |

---

## 1. How to use this doc

Each phase below is scoped to be **buildable and reviewable as a single
unit of work** — small enough that an AI assistant can hold the whole
change in context and a human can review it in one sitting, but large
enough to leave the app in a working, demo-able state at the end of it.

This project has no login/account system (per `PRD.md`, identity is local
and automatic), so unlike a typical app's "Phase 1 = login" template, our
Phase 1 is **local identity generation**, which plays the same structural
role: it's the one piece of state everything else depends on.

Each phase specifies:
- **Goal** — what becomes true when this phase is done.
- **Builds on** — which prior phase(s) it depends on.
- **Deliverables** — concrete files/features, tied to `Architecture.md` §6.
- **Definition of done** — how to know it's actually finished.
- **Explicitly out of scope** — to stop scope creep into later phases.
- **AI-buildable vs. needs hardware** — per `Rules.md` §7, some work is
  fully unit-testable and AI-buildable end to end; some cannot be verified
  without physical devices and should be flagged as such, not skipped.

Phases are meant to be done **in order**; later phases assume earlier ones
are merged and working, not just started.

---

## Phase 0 — Project Scaffold & Tooling

**Goal:** A Gradle project that builds, with the layer structure in place
as empty/stub packages, so every later phase has somewhere correct to go.

**Builds on:** Nothing (starting point).

**Deliverables:**
- `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts`
  with the dependency set from `Architecture.md` §5.
- `AndroidManifest.xml` with the full permission matrix (declared, not yet
  requested at runtime).
- Empty package structure matching `Architecture.md` §6
  (`crypto/`, `noise/`, `protocol/`, `ble/`, `wifi/`, `voice/`, `domain/`,
  `service/`, `ui/`).
- `NearbyApp.kt` application class (no real wiring yet).

**Definition of done:** `./gradlew assembleDebug` succeeds; app installs
and shows a placeholder screen; no runtime functionality yet.

**Explicitly out of scope:** Any actual BLE/crypto/UI logic.

**AI-buildable:** Fully. No hardware needed to verify a clean build.

*(Status: substantially complete — see the scaffold already produced for
this repo.)*

---

## Phase 1 — Local Identity

**Goal:** On first launch, the app generates and persists a device
identity with no user input required. This is the "Phase 1 = login"
equivalent for a serverless, accountless app.

**Builds on:** Phase 0.

**Deliverables:**
- `crypto/Identity.kt`: `IdentityManager.getOrCreateIdentity()`,
  Ed25519 keypair generation, `AndroidKeyStore` storage.
- The API 26–32 software-fallback path flagged as an open item in
  `Architecture.md` §5 — resolved here, not deferred further.
- Minimal onboarding screen: shows the generated node ID / fingerprint and
  lets the user set a local display name (`PRD.md` §5.1).
- Unit tests for keypair generation, fingerprint derivation, and the
  fallback path (per `Rules.md` §7).

**Definition of done:** Fresh install produces a stable identity that
survives app restart; uninstall/reinstall produces a new one (no recovery
mechanism is expected — there's no server to recover from). No PII is
requested anywhere in this flow.

**Explicitly out of scope:** Peer discovery, trust/verification of *other*
peers' identities (that's Phase 3).

**AI-buildable:** Fully unit-testable without hardware. `AndroidKeyStore`
behavior should still get a quick real-device smoke test before merging,
since Keystore behavior varies more by OEM/API level than most Android
APIs.

---

## Phase 2 — BLE Presence Discovery

**Goal:** Two devices running the app can see each other in range, with no
messaging yet — just "who's here."

**Builds on:** Phase 1 (needs a local node ID to advertise).

**Deliverables:**
- `ble/BleMeshEngine.kt`: `startAdvertising()`, `startScanning()`, neighbor
  table as `StateFlow`.
- Runtime permission flow in `MainActivity.kt` for
  `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` (or legacy location permission on
  API ≤30), including the late-2026 Nearby/BLE radio-enablement prompt
  flagged in `Architecture.md` §5.
- `ui/PeerRadarScreen.kt` wired to real neighbor data (already scaffolded;
  connect it to a live `BleMeshEngine` instance instead of a stub).
- `service/MeshForegroundService.kt` hosting the engine so discovery
  continues while backgrounded.

**Definition of done:** Two physical devices, app installed on both, show
each other on the Peer Radar screen within a few seconds, including after
one is backgrounded.

**Explicitly out of scope:** Sending any content between peers — this
phase is discovery only. No relay/multi-hop yet (that needs the protocol
work in Phase 4).

**AI-buildable:** Partially. The permission flow and `StateFlow` wiring are
fully buildable and testable. **Actual advertise/scan behavior can only be
confirmed on two or more real Android devices** — per `Rules.md` §7, do not
claim this phase works until it's been observed on physical hardware.

---

## Phase 3 — Peer Trust & Noise Handshake

**Goal:** Two discovered peers can establish an encrypted, mutually
authenticated session with each other.

**Builds on:** Phase 2 (needs a discovered peer to handshake with).

**Deliverables:**
- `noise/NoiseSession.kt` + `NoiseSessionRegistry.kt` (already scaffolded;
  wire real handshake message exchange over the BLE link from Phase 2).
- `protocol/` additions: `NOISE_HANDSHAKE` packet type send/receive path.
- `domain/PeerRepository.kt`: tracks per-peer trust state (handshake
  complete vs. not; optionally "verified" via out-of-band comparison per
  `PRD.md` §5.2).
- Minimal UI affordance: peer entry shows "connecting" → "encrypted" state.

**Definition of done:** Two devices complete a Noise_XX handshake over a
real BLE link and can each independently confirm
`NoiseSession.isHandshakeComplete()`; a tampered/replayed handshake message
is rejected, not silently accepted (per `Rules.md` §5).

**Explicitly out of scope:** The out-of-band "verified peer" QR/number-
compare UX can be stubbed as a later polish item if it threatens this
phase's scope — the cryptographic handshake itself is the hard requirement,
not the verification UI.

**AI-buildable:** Handshake state-machine logic is unit-testable with two
in-memory `NoiseSession` instances talking to each other directly (no BLE
needed for that part). The BLE transport of handshake bytes needs
real-device validation.

---

## Phase 4 — Multi-Hop Text Messaging & SOS Alerts

**Goal:** The core value proposition of the app: a text message or SOS
alert sent on one device reaches another device, including through
intermediate relaying devices, encrypted end-to-end.

**Builds on:** Phase 2 (discovery), Phase 3 (encryption for direct
messages; broadcast alerts per the open question in `Architecture.md` §7).

**Deliverables:**
- `protocol/MeshPacket.kt` relay logic wired into `BleMeshEngine` (TTL
  decrement, dedup cache — largely scaffolded already; this phase makes it
  load-bearing).
- `domain/MessageStore.kt`: Room schema + DAO for the chat timeline.
- `domain/MeshRepository.kt`: ties discovery + trust + messaging into one
  coherent state surface for the UI.
- `ui/ChatTimelineScreen.kt`: send/receive direct messages.
- SOS alert flow: dedicated message type, priority TTL, distinct UI
  treatment (`PRD.md` §5.3).

**Definition of done:** A message sent from Device A reaches Device C via
Device B relaying, with B unable to read the plaintext; an SOS broadcast
reaches all in-range devices; messages persist locally across app restarts.

**Explicitly out of scope:** Voice notes, file transfer, calling.

**AI-buildable:** Routing/TTL/dedup/Room logic is fully unit-testable.
**3+ device multi-hop relay must be validated on physical hardware** — this
is exactly the scenario emulators can't represent, per `README.md`.

---

## Phase 5 — File & Voice Note Transfer

**Goal:** Users can send a voice note or a file to a peer, with the
BLE-to-Wi-Fi-Direct handoff working end to end.

**Builds on:** Phase 3 (encryption), Phase 4 (message timeline to attach
transfers to).

**Deliverables:**
- `protocol/` additions: `TRANSFER_OFFER` / `TRANSFER_ACCEPT` flow.
- `wifi/TransferSession.kt`, `wifi/ChunkedTransfer.kt`: Nearby Connections
  integration, including the explicit radio-enablement prompt required by
  Google's late-2026 API change (`Architecture.md` §5).
- `domain/TransferManager.kt`: orchestrates offer → accept → transfer →
  completion, with resume-on-drop handling.
- `ui/VoiceRecorderScreen.kt`: record and send a voice note.
- File-size cap and unsupported-type handling per the open question in
  `PRD.md` §10.

**Definition of done:** A voice note and a file (e.g., a photo) each
transfer successfully between two devices end to end, encrypted, with
visible progress; an interrupted transfer fails cleanly rather than
corrupting the message timeline.

**Explicitly out of scope:** Live voice calling (Phase 6).

**AI-buildable:** Offer/accept state machine and chunk sequencing are
unit-testable. **Wi-Fi Direct group formation is the single riskiest,
least-emulator-representable piece in the whole project** — budget real
multi-device, multi-OEM testing time here specifically, per the risk called
out in `PRD.md` §9.

---

## Phase 6 — P2P Voice Calling

**Goal:** Two peers can place a live duplex voice call over the local link.

**Builds on:** Phase 5 (reuses the Wi-Fi Direct handoff and offer/accept
pattern).

**Deliverables:**
- `voice/CallSession.kt`, `voice/OpusConfig.kt`: WebRTC `PeerConnection`
  setup, host-only ICE candidates, Opus configuration tuned for a local
  link.
- `ui/CallScreen.kt`: ringing, accept/decline, mute, end-call controls.
- Call-specific `TRANSFER_OFFER` subtype reusing the Phase 5 handoff.

**Definition of done:** A call connects, both sides hear each other with
acceptable latency/quality on a real local link, and the call ends cleanly
(both explicit hangup and abrupt peer-loss-of-range cases).

**Explicitly out of scope:** Group calls (non-goal per `PRD.md` §3.2).

**AI-buildable:** WebRTC wiring and call-state UI are buildable and
partially testable in isolation. **Actual audio quality and latency can
only be judged on real devices** — this phase should not be marked done
off of a build succeeding.

---

## Phase 7 — Mesh Diagnostics & Background Hardening

**Goal:** The app is trustworthy for extended real-world use, not just a
working demo — background reliability and battery impact are visible and
manageable.

**Builds on:** All prior phases (this phase hardens what already exists
rather than adding new user-facing capability).

**Deliverables:**
- `ui/` mesh diagnostics screen (`PRD.md` §5.6): peer count, hop depth,
  battery impact indicator, manual "stop meshing" control.
- `service/MeshMaintenanceWorker.kt`: WorkManager job for neighbor-table
  pruning and re-prompting for battery-optimization exemption if revoked.
- Explicit "meshing paused" UI state for when the OS has throttled or
  killed background execution (per `Rules.md` §6).

**Definition of done:** A multi-hour, screen-off field test on at least 3
different device manufacturers shows the app either actively meshing or
clearly telling the user it isn't — never silently doing nothing while
appearing active.

**Explicitly out of scope:** New user-facing features.

**AI-buildable:** UI and WorkManager scheduling logic are buildable
directly. **The actual battery/OEM-killing behavior this phase exists to
address can only be measured on real hardware over real time** — this is
the phase where hardware validation is the deliverable, not a checkbox.

---

## 2. Summary Table

| Phase | Focus | Primary risk | Hardware validation needed? |
|---|---|---|---|
| 0 | Project scaffold | Low | No |
| 1 | Local identity | Low | Light (Keystore smoke test) |
| 2 | BLE presence discovery | Medium | Yes — 2 devices |
| 3 | Noise handshake / trust | Medium | Yes — 2 devices |
| 4 | Multi-hop text + SOS | Medium-High | Yes — 3+ devices |
| 5 | File / voice note transfer | **High** | Yes — multi-OEM |
| 6 | P2P voice calling | Medium-High | Yes — audio quality |
| 7 | Diagnostics & hardening | Medium | Yes — multi-hour, multi-OEM |

## 3. Open Items

- Should Phase 4 (multi-hop messaging) and Phase 5 (file transfer) be
  reordered if voice notes turn out to matter more to early testers than
  file transfer — i.e., should "voice note only" ship before general file
  transfer as a thinner Phase 5a?
- Does Phase 6 (calling) get cut from v1 scope entirely if Phase 5's
  Wi-Fi Direct work proves as unreliable in the field as flagged in
  `PRD.md` §9 — should there be an explicit go/no-go checkpoint after
  Phase 5 before committing to Phase 6?
- Should Phase 7's diagnostics screen actually ship earlier (e.g., right
  after Phase 2) so background-reliability problems surface during
  development instead of being deferred to the end?
