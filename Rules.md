# Rules.md — AI Assistant Working Rules for Nearby

**Boundaries and conventions for any AI coding assistant (Claude, Antigravity
automation, Copilot, etc.) contributing to this codebase.**

| | |
|---|---|
| Status | Draft v0.1 |
| Companion docs | `PRD.md`, `Architecture.md` |
| Applies to | All AI-assisted commits, PRs, and scaffolding in this repo |
| Last updated | 2026-09-18 |

---

## 1. Purpose

This repo handles cryptographic identity, E2EE messaging, and life-safety
adjacent functionality (SOS alerts, SAR coordination) with **no server to
patch mistakes after the fact**. A bad merge doesn't get fixed by a hotfix
deploy — it ships to a phone that might be relied on in an actual emergency.
These rules exist to keep AI-assisted contributions safe, reviewable, and
consistent, not to slow the team down for its own sake.

## 2. Library & Dependency Rules

### 2.1 Approved / preferred
Use these unless a specific task calls for something else, and say so if it
does:

| Concern | Use |
|---|---|
| Async | Kotlin Coroutines + Flow |
| UI | Jetpack Compose |
| Local persistence | Room |
| BLE | `android.bluetooth.le.*`, optionally Nordic BLE scanner lib |
| P2P high-throughput | Google Play Services Nearby Connections API |
| Voice calling | WebRTC (`io.github.webrtc-sdk`), Opus |
| Crypto | Noise Protocol (`noise-java`), Ed25519, `AndroidKeyStore` |
| Testing | JUnit, `kotlinx-coroutines-test`, AndroidX Test |

These are the choices recorded in `Architecture.md` §5 — treat that table as
the source of truth, not this list, if the two ever drift.

### 2.2 Never introduce without explicit human sign-off
- **Any new cryptographic library, or any hand-rolled crypto primitive.**
  Never implement a cipher, KDF, or signature scheme from scratch. If Noise
  or `AndroidKeyStore` doesn't cover a need, flag it — don't substitute a
  library the team hasn't vetted.
- **Any analytics, crash-reporting, or telemetry SDK** (Firebase Analytics,
  Crashlytics, Sentry, etc.) that phones home over the internet. This
  directly conflicts with the "genuinely offline, no PII collection"
  requirement in `PRD.md` §6. On-device-only crash logs are fine; anything
  that uploads is not.
- **Any dependency that requires a persistent internet connection to
  initialize**, even if the feature using it is optional. This includes SDK
  patterns that "phone home" for licensing/config checks on first run.
- **Any ads or monetization SDK.**
- A new **JSON/serialization library** for the wire protocol. The mesh wire
  format is a fixed, hand-specified binary layout (`protocol/MeshPacket.kt`)
  for a reason — BLE MTU and parsing-cost constraints. Don't propose
  swapping it for JSON, Protobuf, or similar without flagging the tradeoff
  explicitly; this is an architectural decision, not a convenience swap.

### 2.3 General dependency hygiene
- Prefer AndroidX / Jetpack-maintained libraries over third-party
  alternatives when both exist and are roughly equivalent.
- Don't add a dependency to solve a problem that's a few lines of plain
  Kotlin. Every added dependency is attack surface and a future breakage
  risk in a codebase that can't lean on a server-side fix.
- When adding or upgrading a dependency, state the version and one sentence
  on why, in the PR description — don't silently bump versions as a side
  effect of an unrelated change.

## 3. Layer Boundary Rules

These enforce the architecture in `Architecture.md` §1–3 at the code level:

- **The `domain/` package never imports `android.bluetooth.*`,
  `android.net.wifi.*`, or WebRTC classes directly.** It talks to `ble/`,
  `wifi/`, and `voice/` only through their exposed Kotlin interfaces. If a
  task seems to require breaking this, stop and flag it rather than doing
  it — it usually means the interface is missing a method, not that the
  rule should bend.
- **No layer implements its own encryption.** All encrypt/decrypt calls go
  through `crypto/` + `noise/`. If a new payload type needs encryption,
  wire it through `NoiseSession`, don't add an ad hoc cipher call at the
  point of use.
- **UI (`ui/`) never talks to `ble/`, `wifi/`, or `voice/` directly** —
  only to `domain/`. A Compose screen that imports `BleMeshEngine` directly
  is a boundary violation, even if it "works."
- Code that depends on real RF/radio behavior (BLE scan results, Wi-Fi
  Direct group formation, WebRTC ICE negotiation) must be written behind an
  interface that can be faked in `test/`. Don't write logic that's only
  testable on a physical device unless it's genuinely radio-level code that
  belongs in `androidTest/`.

## 4. Error Handling Rules

- **Never silently swallow an exception.** No empty `catch` blocks. At
  minimum, log locally (on-device only — see §2.2) and surface a state the
  UI layer can react to.
- **Distinguish recoverable from unrecoverable failures explicitly.** A
  Wi-Fi Direct negotiation timeout is recoverable — retry or fall back to
  "text only" for that transfer. A Noise handshake that fails signature
  verification is a potential security event, not a retry-and-move-on case
  — surface it as "peer identity could not be verified," don't retry
  silently and don't proceed as if encrypted.
- **Never let a failure in one layer take down another.** Per
  `Architecture.md` §1.2, a Wi-Fi Direct failure must not affect the BLE
  text-relay path, and vice versa. If a change couples their failure
  handling, that's a boundary violation (§3), not just a bug.
- **User-facing errors must be actionable**, not raw exception text. "Could
  not reach [peer] — they may be out of range" rather than
  `WifiP2pManager.BUSY`. Map framework errors to domain-level, human-
  readable states in the layer that owns the framework call, not in the UI.
- **Never fail silently on a decrypt/verify failure.** If `NoiseSession.decrypt`
  or a signature check throws or returns invalid data, that packet is
  dropped and, where appropriate, logged as a trust event — it is never
  passed through as plaintext or treated as "probably fine."
- Use Kotlin `Result<T>` or sealed-class result types for operations that
  can fail in an expected way (transfer negotiation, handshake); reserve
  thrown exceptions for genuinely unexpected/programmer-error conditions.

## 5. Security & Privacy Rules

- **No PII, ever**, in code, logs, defaults, or sample data — no hardcoded
  phone numbers, emails, or real names in tests or placeholder content. Use
  the existing pseudonym-generation pattern (`Survivor-XXXX`) for any
  sample data needed.
- **Private key material never gets logged**, not even at debug level, not
  even truncated. If a debug log would include key material, remove the
  key material from the log, not the log.
- **Never add a "debug backdoor"** (a hardcoded test key, a way to skip the
  Noise handshake, a permanent "trust all peers" flag) even temporarily —
  these have a way of surviving into shipped builds. If a debug aid is
  genuinely needed, gate it behind a build-time flag that's off by default
  and call it out explicitly in the PR.
- **Treat every value received from a peer as untrusted input**, including
  over already-encrypted channels. Bounds-check chunk indices, payload
  lengths, and TTL values before using them (see `MessageChunker` — an
  attacker-controlled `chunkCount` must not be usable to exhaust memory).
- **Don't weaken forward secrecy for convenience.** E.g., don't cache
  decrypted plaintext longer than the UI needs it, don't persist Noise
  session keys to disk to "avoid re-handshaking."

## 6. Permissions & Background Execution Rules

- Never call a BLE, Wi-Fi Direct, or audio-recording API without a runtime
  permission check immediately guarding it — a crash from a missing
  permission is bad, but silently degrading a safety feature is worse.
  Check, and if absent, surface a clear "this feature needs X permission"
  state rather than failing deep in a stack trace.
- Any new background work goes through `MeshForegroundService` or
  `WorkManager` — never a raw background `Thread` or an unmanaged
  coroutine scope that outlives its intended lifecycle. Background mesh
  participation must be visible and stoppable by the user at all times
  (per `PRD.md` §5.6).
- Don't add code that keeps radios (BLE, Wi-Fi) active more aggressively
  than the current design calls for "to be safe" — battery impact is a
  first-class constraint here (`PRD.md` §6), not an afterthought. If a
  change increases background radio duty cycle, call that out explicitly.

## 7. Testing Rules

- Any change to `protocol/`, `domain/`, `crypto/`, or `noise/` needs a
  corresponding unit test in `test/` — these are exactly the layers
  designed to be testable without hardware (per `Architecture.md` §1.5).
  Don't skip this because "it's a small change."
- Any change to `ble/`, `wifi/`, or `voice/` that touches real radio
  behavior must be flagged for physical-device validation in the PR
  description — do not claim or imply it's been verified on real hardware
  unless it actually has been. Simulator/emulator BLE and Wi-Fi Direct
  behavior is not representative (per `README.md` "Known constraints").
- Don't write a test that mocks away the exact thing it's supposed to be
  proving (e.g., a "TTL relay test" that fakes `forRelay()` itself rather
  than exercising the real decrement/drop logic).

## 8. What the AI Should Do

- Flag architectural implications, even for a request that sounds like a
  small ask — e.g., "add a retry" for a Wi-Fi Direct call might implicitly
  need idempotency handling on the receiving end too.
- Point out when a request would violate a layer boundary (§3), a security
  rule (§5), or introduce a disallowed dependency (§2.2), and propose the
  compliant alternative, rather than silently complying or silently
  refusing.
- Keep `README.md`, `PRD.md`, and `Architecture.md` in sync with real
  structural changes (new packages, changed data flow, dependency swaps) —
  treat doc drift as a defect, not a follow-up task.
- Default to the smallest change that satisfies the request, especially in
  `crypto/`, `noise/`, and `protocol/` — these are the highest-consequence
  files in the repo.
- Ask before making a call on any of the open questions listed in
  `PRD.md` §10 or `Architecture.md` §7, rather than picking an answer
  unilaterally and encoding it into shipped code.

## 9. What the AI Should NOT Do

- **Do not implement or modify cryptographic logic "confidently" without
  flagging it for human review.** Getting crypto subtly wrong is worse than
  not implementing it at all.
- **Do not claim hardware-dependent code has been tested on a physical
  device** unless it actually has — this is a hardware validation
  constraint from the project spec (`README.md`), not a formality.
- **Do not add telemetry, analytics, or any network call to a
  non-Anthropic/non-peer-device endpoint** without explicit sign-off — see
  §2.2. If a library appears to need one to function, that's a reason to
  reject the library, not to add the call quietly.
- **Do not weaken, bypass, or "temporarily disable" the Noise handshake,
  signature verification, or permission checks** to make a feature easier
  to demo or test. Use a clearly-labeled debug build flag if a shortcut is
  genuinely needed during development, per §5.
- **Do not invent product requirements.** If a task is ambiguous relative
  to `PRD.md`, ask or flag the assumption explicitly — don't silently
  decide, e.g., that broadcast SOS alerts should be encrypted when that's
  still an open question (`Architecture.md` §7).
- **Do not restructure the package layout** (§3, `Architecture.md` §6)
  without discussing it first — the folder structure is load-bearing
  documentation of the architecture, not just organization.
- **Do not merge a change that couples two layers' failure handling**
  (§4) or otherwise breaks the "layers degrade independently" principle,
  even if it's the expedient way to fix a bug.

## 10. Open Items

- Should this file define a required PR template / checklist that encodes
  §7–§9 as literal checkboxes, or stay prose-only guidance?
- Do we want an automated lint rule (e.g., a Detekt custom rule) enforcing
  the `domain/` import boundary in §3, rather than relying on review?
