# Design.md — Visual Design System for Nearby

**Colors, theming, typography, and the reasoning behind them.**

| | |
|---|---|
| Status | Draft v0.1 |
| Companion docs | `PRD.md`, `Architecture.md` |
| Applies to | Jetpack Compose / Material3 theming across `ui/` |
| Last updated | 2026-09-18 |

---

## 1. Design Principles

These come directly from how and where this app gets used (`PRD.md` §4, §6):

1. **Calm, not clinical.** The palette should feel reassuring — "help is
   here" — not sterile or alarming. Sky blue and white, not hospital
   white-on-white or tactical black-on-red.
2. **Legible under stress, in sunlight, one-handed, in low light.** This is
   a non-negotiable functional requirement (`PRD.md` §6), not a nice-to-have.
   High contrast wins over aesthetic subtlety every time they conflict.
3. **Dark mode is a first-class default, not a toggle nobody finds.**
   Outdoor/field use and battery conservation on OLED screens both point
   the same direction. Light theme is offered, not assumed.
4. **The SOS/alert state deliberately breaks the palette.** Everywhere else,
   visual consistency is a virtue. For emergency alerts, standing out is
   more important than fitting in — a stressed user glancing at the screen
   must not be able to mistake an SOS for routine chat.
5. **No screen requires color alone to convey meaning.** Peer connection
   state, delivery status, and alerts must also use icon/shape/text, for
   colorblind users and for direct-sunlight legibility where color
   saturation itself degrades.

## 2. Color System

### 2.1 Light theme ("Sky")

| Token | Hex | Usage |
|---|---|---|
| `primary` | `#2E8FD6` | Primary actions, active states, links |
| `primaryContainer` | `#D6ECFB` | Selected/active surface backgrounds, chips |
| `onPrimary` | `#FFFFFF` | Text/icons on `primary` |
| `onPrimaryContainer` | `#0B3B5C` | Text/icons on `primaryContainer` |
| `secondary` | `#5B7C99` | Secondary actions, muted UI accents |
| `background` | `#FFFFFF` | App background |
| `surface` | `#F5FAFE` | Cards, sheets, chat bubbles (very light blue-white) |
| `surfaceVariant` | `#E4F1FB` | Distinguishing nested surfaces (e.g. incoming chat bubble) |
| `onBackground` / `onSurface` | `#0F1B24` | Primary body text — near-black, not gray, for contrast |
| `onSurfaceVariant` | `#41525E` | Secondary/metadata text (timestamps, hop count) |
| `outline` | `#B7CBDA` | Dividers, input borders |

### 2.2 Dark theme ("Night Sky") — default

| Token | Hex | Usage |
|---|---|---|
| `primary` | `#7FC4F5` | Primary actions, active states, links |
| `primaryContainer` | `#0B3B5C` | Selected/active surface backgrounds, chips |
| `onPrimary` | `#00263D` | Text/icons on `primary` |
| `onPrimaryContainer` | `#D6ECFB` | Text/icons on `primaryContainer` |
| `secondary` | `#9AB4C7` | Secondary actions, muted UI accents |
| `background` | `#0A1216` | App background — near-black, not pure black (reduces OLED smear/halo) |
| `surface` | `#111B21` | Cards, sheets, chat bubbles |
| `surfaceVariant` | `#1B2830` | Distinguishing nested surfaces |
| `onBackground` / `onSurface` | `#EAF2F7` | Primary body text |
| `onSurfaceVariant` | `#A9BAC6` | Secondary/metadata text |
| `outline` | `#2E3D46` | Dividers, input borders |

Both themes are built from the same sky-blue hue family — dark mode is not
"a different app," it's the same identity at inverted luminance.

### 2.3 Alert & status colors (theme-independent, deliberately off-palette)

These do **not** shift between light/dark the way the palette above does —
they're chosen to stay unmistakable in both:

| Token | Hex | Usage |
|---|---|---|
| `sos` | `#D7263D` | SOS/emergency alert surfaces, banners, the SOS button itself |
| `onSos` | `#FFFFFF` | Text/icons on `sos` surfaces |
| `sosContainer` (light) | `#FBE2E5` | Subtler SOS-adjacent surfaces in light theme |
| `sosContainer` (dark) | `#3D0F16` | Subtler SOS-adjacent surfaces in dark theme |
| `warning` | `#E6A400` | Non-critical warnings (e.g. "low battery, meshing may stop") |
| `success` | `#2E9E5B` | Confirmed delivery, completed transfer, verified peer |
| `unverifiedPeer` | `#8A8F98` | Neutral gray for not-yet-verified peer indicators — deliberately unsaturated so it never competes visually with `sos` or `success` |

**Rule:** `sos` red and `success` green must never be used for anything
else in the UI. If a designer wants a red or green accent for a routine,
non-alert purpose, pick a different hue — these two colors' entire value is
that they mean exactly one thing, always.

### 2.4 Contrast requirements

- Body text on background/surface: minimum **WCAG AA (4.5:1)**, target
  **AAA (7:1)** given the direct-sunlight use case — verify `onBackground`/
  `onSurface` pairings above against actual rendered output, not just spec
  math, since device display calibration varies.
- SOS banner text (`onSos` on `sos`): must hit AAA — this is the one
  surface where "just barely passing" isn't good enough.
- Never rely on `primaryContainer` vs. `surfaceVariant` alone to
  distinguish state — pair with icon or label per Principle 5 above.

## 3. Typography

### 3.1 Typeface

**Inter** (or the system default `Roboto` as a zero-dependency fallback if
a team prefers not to bundle a custom font). Rationale: both are
open-source, highly legible at small sizes, have excellent number
legibility (relevant for hop counts, timestamps, battery %), and neither
carries a "branded" feel that would clash with the calm/reassuring goal.
Avoid anything with high stroke-contrast or condensed/display styling —
this is a utility app used under stress, not a marketing surface.

### 3.2 Type scale (Material3 roles)

| Role | Size / weight | Usage |
|---|---|---|
| `displayLarge` | 36sp / Medium | Reserved — likely unused in v1 (no marketing/splash screens) |
| `headlineLarge` | 28sp / SemiBold | Screen titles ("Peer Radar", "Chat") |
| `headlineSmall` | 22sp / SemiBold | Section headers, dialog titles |
| `titleMedium` | 17sp / SemiBold | Peer names, chat bubble sender labels |
| `bodyLarge` | 16sp / Regular | Primary chat message text — deliberately larger than typical default 14sp for at-a-glance legibility |
| `bodyMedium` | 14sp / Regular | Secondary body text, form fields |
| `labelLarge` | 14sp / Medium | Buttons |
| `labelSmall` | 12sp / Medium | Timestamps, hop-count badges, metadata |

**Minimum body text size is 16sp**, one step above Material's typical
14sp default — set deliberately given the sunlight/stress/one-handed
requirement in `PRD.md` §6. Don't shrink type to fit more on screen;
restructure the layout instead.

### 3.3 SOS/alert typography

SOS banner and alert text use `headlineSmall` or larger even in contexts
where surrounding UI is smaller (e.g., a compact peer-list row) —
emergency content should never be typographically minimized to fit a
layout.

## 4. Theming Implementation Notes (Compose / Material3)

- Implement as a single `NearbyTheme.kt` in `ui/theme/` exposing
  `LightColorScheme` and `DarkColorScheme` `ColorScheme` objects (Material3
  `lightColorScheme()` / `darkColorScheme()` builders) plus the
  theme-independent alert tokens (§2.3) as a separate small object (e.g.
  `AlertColors`) — not part of the swappable `ColorScheme`, since they must
  not vary by theme.
- Default to dark theme (`isSystemInDarkTheme()` as the initial value,
  but allow explicit override) rather than always defaulting to light and
  waiting on system settings — per Principle 3.
- Respect Android **dynamic color** (Material You) as an optional
  enhancement, not a requirement — if enabled, it must still route SOS
  through the fixed `AlertColors` token, never through a dynamically
  derived accent that could shift alert visibility unpredictably.
- Typography: define via Compose `Typography()` using the scale in §3.2;
  reference `MaterialTheme.typography.*` throughout `ui/`, never hardcode
  a `fontSize` inline — this keeps the 16sp-minimum rule enforceable
  globally by changing one definition.

## 5. Open Items

- Should verified-peer status get its own color token distinct from
  `success`, or is reusing `success` (green) for both "message delivered"
  and "peer verified" acceptable, or confusing?
- Confirm final palette hex values against real device screens outdoors
  before locking `Design.md` past draft status — spec'd contrast ratios on
  a color wheel don't always survive real display panels in sunlight.
- Decide whether Material You dynamic color is in scope for v1 at all, or
  deferred entirely to avoid the added QA surface of "the theme users see
  varies by device wallpaper."
