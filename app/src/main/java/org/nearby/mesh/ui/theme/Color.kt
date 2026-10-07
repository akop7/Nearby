package org.nearby.mesh.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light Theme ("Sky") tokens - Design.md §2.1
val SkyPrimary = Color(0xFF2E8FD6)
val SkyPrimaryContainer = Color(0xFFD6ECFB)
val SkyOnPrimary = Color(0xFFFFFFFF)
val SkyOnPrimaryContainer = Color(0xFF0B3B5C)
val SkySecondary = Color(0xFF5B7C99)
val SkyBackground = Color(0xFFFFFFFF)
val SkySurface = Color(0xFFF5FAFE)
val SkySurfaceVariant = Color(0xFFE4F1FB)
val SkyOnBackground = Color(0xFF0F1B24)
val SkyOnSurface = Color(0xFF0F1B24)
val SkyOnSurfaceVariant = Color(0xFF41525E)
val SkyOutline = Color(0xFFB7CBDA)

// Dark Theme ("Night Sky") tokens - default - Design.md §2.2
val NightPrimary = Color(0xFF7FC4F5)
val NightPrimaryContainer = Color(0xFF0B3B5C)
val NightOnPrimary = Color(0xFF00263D)
val NightOnPrimaryContainer = Color(0xFFD6ECFB)
val NightSecondary = Color(0xFF9AB4C7)
val NightBackground = Color(0xFF0A1216)
val NightSurface = Color(0xFF111B21)
val NightSurfaceVariant = Color(0xFF1B2830)
val NightOnBackground = Color(0xFFEAF2F7)
val NightOnSurface = Color(0xFFEAF2F7)
val NightOnSurfaceVariant = Color(0xFFA9BAC6)
val NightOutline = Color(0xFF2E3D46)

// Alert & status colors (theme-independent, deliberately off-palette) - Design.md §2.3
data class AlertColorTokens(
    val sos: Color = Color(0xFFD7263D),
    val onSos: Color = Color(0xFFFFFFFF),
    val sosContainerLight: Color = Color(0xFFFBE2E5),
    val sosContainerDark: Color = Color(0xFF3D0F16),
    val warning: Color = Color(0xFFE6A400),
    val success: Color = Color(0xFF2E9E5B),
    val unverifiedPeer: Color = Color(0xFF8A8F98)
)

val LocalAlertColors = staticCompositionLocalOf { AlertColorTokens() }