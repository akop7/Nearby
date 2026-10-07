# Product Requirements Document: Nearby

**Offline Decentralized Emergency & Disaster Communication App**

| | |
|---|---|
| Status | Draft v0.1 |
| Owner | TBD |
| Platform | Android (minSdk 26 / API 26+) |
| Last updated | 2026-09-18 |

---

## 1. Problem Statement

During disasters (earthquakes, hurricanes, wildfires, floods), search-and-rescue
operations, and in remote/off-grid areas, cellular networks and Wi-Fi
infrastructure are frequently destroyed, overloaded, or simply absent. People
in these conditions need to communicate — coordinate rescue, share
location/status, send alerts — but have no access to towers, routers, or
internet-connected servers.

Existing messaging apps (SMS, WhatsApp, Signal) universally assume some path
to a central server or carrier network exists. When that assumption breaks,
those apps become unusable exactly when communication matters most.

## 2. Product Vision

**Nearby** is an Android app that lets phones talk directly to each other —
text, voice notes, files, and live voice calls — over Bluetooth and Wi-Fi
Direct, forming an ad-hoc mesh with no cellular, no Wi-Fi router, and no
central server anywhere in the path. Identity is local and cryptographic;
there is no phone number, account, or sign-up. If two Nearby users are within
radio range of each other, directly or through a chain of other Nearby users
relaying for them, they can communicate.

## 3. Goals & Non-Goals

### 3.1 Goals
- Enable text messaging and emergency alerts to propagate across multiple
  hops of Nearby users with no infrastructure.
- Enable direct exchange of voice notes and files (photos, documents) between
  two peers, or across a mesh path, without a server.
- Enable low-latency duplex voice calls between two peers on the same local
  ad-hoc link.
- Guarantee end-to-end encryption and forward secrecy for all content, with
  identity that requires no PII.
- Work reliably enough, on real hardware, to be trustworthy in a genuine
  emergency — not just a lab demo.

### 3.2 Non-Goals (out of scope for v1)
- Internet gateway / bridging the mesh to the wider internet (no relay-to-SMS,
  no relay-to-internet messaging).
- Group voice/video calls (v1 is 1:1 calling only).
- Cross-platform support (iOS is out of scope for v1; Android only).
- Location tracking / mapping features beyond what a user manually shares.
- Content moderation, account recovery, or any server-side feature — there is
  no server.
- Mesh routing at "crowd" scale (hundreds of concurrent nodes). v1 targets the
  realistic scale of a disaster response team, shelter, or search party
  (roughly 2–50 concurrent nodes in range or hop-reachable).

## 4. Target Users & Personas

| Persona | Context | Primary need |
|---|---|---|
| **Search & rescue team member** | Coordinating a search in an area with no cell coverage | Reliable short text/status updates and SOS alerts across the team, multi-hop if spread out |
| **Disaster-affected resident** | Cell towers down after an earthquake/hurricane | Reach family/neighbors nearby, send an SOS, share a status update without needing power-hungry constant scanning |
| **Remote traveler / hiker** | Backcountry, no signal, traveling with others | Stay in touch with their group over short-to-medium range without relying on satellite devices |
| **Disaster relief coordinator (NGO/first responder)** | Managing a shelter or relief site | Broadcast alerts/instructions to everyone in range; receive status pings from the field |

Common thread: **all personas need this to work with zero setup, zero
account, and zero connectivity**, and to keep working as people move in and
out of range of each other.

## 5. Core Features (Functional Requirements)

### 5.1 Local Cryptographic Identity — P0
- On first launch, the app generates an Ed25519 keypair on-device. No
  registration, login, phone number, or email is ever requested.
- The public key fingerprint is the user's address on the mesh.
- The user may set a local display name (pseudonym); it is never used as a
  trust or identity proof.
- Private key material never leaves the device.

### 5.2 Presence Discovery & Peer Radar — P0
- The app continuously (subject to OS background limits) advertises and
  scans over BLE to discover other Nearby users in range.
- A "peer radar" UI shows currently-visible peers and, where derivable,
  approximate relative signal strength.
- Users can optionally verify a peer's identity out-of-band (e.g., QR code /
  number comparison) before trusting messages from them as "verified."

### 5.3 Multi-Hop Text Messaging & Alerts — P0
- Users can send direct messages to a specific known peer or broadcast a
  message to everyone in mesh range.
- Messages relay through intermediate Nearby-running devices (multi-hop),
  bounded by a hop limit (TTL), without those intermediaries being able to
  read the content (E2EE).
- A dedicated **SOS/emergency alert** message type gets priority relay and a
  distinct UI treatment (e.g., persistent banner, higher rebroadcast
  priority).
- Delivery status (sent / relayed / delivered, where determinable) is shown
  to the sender.

### 5.4 Voice Notes & File Transfer — P1
- Users can record and send a voice note to a peer; playback in the message
  timeline.
- Users can send arbitrary files (photos, documents) up to a defined size
  cap for v1 (e.g., 25 MB) to a peer.
- Large transfers auto-negotiate a Wi-Fi Direct / Nearby Connections link
  (rather than going over BLE) and show progress; they resume after a brief
  drop where feasible.
- Transfers are E2EE end-to-end, not just link-encrypted.

### 5.5 Direct P2P Voice Calling — P1
- Two peers on the same local link can place a low-latency duplex voice call
  to each other.
- Call setup, ringing, accept/decline, and in-call mute/end controls.
- No external signaling server or STUN/TURN is required; call setup happens
  entirely over the local mesh/direct link.
- Call audio is E2EE.

### 5.6 Mesh Health & Diagnostics — P2
- A simple screen showing: number of peers in range, estimated hop depth to
  known peers, battery/impact indicator for keeping meshing active,
  and a manual "stop meshing" control.

## 6. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Privacy** | No PII collected. No network calls to any Anthropic/Google/third-party analytics or crash-reporting service that would require internet connectivity to function (offline-first must be genuine, not just "usually offline"). |
| **Security** | All user content (messages, voice notes, files, calls) E2EE via Noise Protocol (Ed25519 identity keys); forward secrecy per session. |
| **Reliability** | Core text messaging must keep functioning across intermittent connectivity (peers moving in/out of range) without requiring app restart. |
| **Battery** | Background mesh participation must be power-conscious enough for multi-hour field use; the app must clearly disclose its battery impact and let users pause meshing. |
| **Performance** | Text/alert messages should propagate to an in-range peer within ~1–2 seconds; voice call audio latency should be low enough for natural conversation (target sub-300ms one-way on a direct link). |
| **Accessibility** | UI must be usable one-handed, in low-light, and readable at a glance (disaster-use context implies stressed, distracted users). |
| **Resilience** | The app must degrade gracefully: if Wi-Fi Direct negotiation fails, text/alerts must still work over BLE; if a peer drops mid-file-transfer, partial progress should not be silently lost. |

## 7. Assumptions & Constraints

- Target devices run Android 8.0 (API 26) or later.
- Effective range is inherently limited by BLE/Wi-Fi Direct radio physics
  (roughly tens of meters per hop, less indoors/through obstruction); this is
  a physical constraint of the chosen technology, not a defect.
- Background execution is subject to Android OS and OEM-specific limits
  (Doze, App Standby, aggressive battery managers on some manufacturers);
  reliability will vary by device and must be validated on physical hardware
  per manufacturer, not assumed uniform.
- v1 assumes a "small group" mesh (single-digit to a few dozen concurrent
  nodes), not city-scale mesh density.
- No developer-operated backend exists or is planned; there is nothing to
  patch server-side, which also means there is no way to push emergency
  updates to the app other than a normal app-store update.

## 8. Success Metrics

Since there is no server to instrument, success is measured primarily
through device-local, privacy-preserving indicators and qualitative field
testing rather than analytics dashboards:

- **Field-test delivery success rate**: % of test messages that
  successfully traverse N hops in real multi-device field tests.
- **Time-to-first-peer-discovered**: how quickly a freshly launched app
  finds at least one peer in range.
- **Session battery drain**: % battery consumed per hour of active meshing
  in the background, benchmarked across at least 3 device manufacturers.
- **Call setup success rate / audio quality**: measured in controlled
  device-to-device tests.
- **Crash-free session rate**, tracked locally and only surfaced to the user
  (no involuntary telemetry upload, consistent with the privacy requirement
  above).

## 9. Key Risks

| Risk | Impact | Notes |
|---|---|---|
| OEM background-service killing | High | Could silently stop relaying for others without the user realizing; needs explicit "meshing paused" UI state and battery-exemption prompts |
| Wi-Fi Direct group-formation flakiness | Medium-High | Historically the least reliable Android P2P API in the field; file/voice-note transfer UX must tolerate and clearly surface failures |
| Flood-routing scale limits | Medium | Fine at target scale (dozens of nodes); would need real routing (not flood) if scope ever grows to crowd-scale |
| Regulatory / radio compliance | Low-Medium | BLE/Wi-Fi Direct advertising is standard and permitted, but disaster/emergency-branded apps may face app-store review scrutiny around claims of "life-safety" functionality — messaging should avoid over-promising reliability |
| No push-update path for a stranded user mid-disaster | Low | Inherent to the no-server model; mitigate by making the app work correctly and safely at whatever version is currently installed |

## 10. Open Questions

- What's the maximum acceptable hop count (TTL) before a message is dropped,
  and should it be user-configurable per message type (e.g., SOS gets a
  higher TTL than routine chat)?
- Should "verified peer" (out-of-band key confirmation) be required before
  showing a peer's messages as trusted, or optional/advisory?
- What's the file-size cap for v1 transfers, and how should partial/failed
  transfers be surfaced and retried?
- Do we need a "mesh relay only" mode (device relays traffic for others but
  has no UI open) for people willing to leave a phone as infrastructure
  (e.g., plugged in at a relief shelter)?

## 11. Release Phasing (maps to engineering branches)

| Phase | Scope | Branch(es) |
|---|---|---|
| **Phase 1 — Mesh MVP** | Local identity, BLE presence discovery, multi-hop text + SOS alerts | `feature/crypto-identity`, `feature/ble-mesh` |
| **Phase 2 — Rich content** | Voice notes, file transfer over Wi-Fi Direct/Nearby Connections | `feature/file-transfer` |
| **Phase 3 — Voice calling** | Direct P2P duplex voice calls | `feature/p2p-call` |
| **Phase 4 — Hardening** | Multi-OEM field battery/reliability testing, mesh diagnostics screen, UX polish | — |

Phase 1 alone is intended to be a complete, shippable, valuable product on
its own (comparable in scope to what apps like Bridgefy launched with), so
that value is delivered even if later phases take longer than planned.
