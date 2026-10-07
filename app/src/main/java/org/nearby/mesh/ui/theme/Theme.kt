package org.nearby.mesh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    primaryContainer = NightPrimaryContainer,
    onPrimary = NightOnPrimary,
    onPrimaryContainer = NightOnPrimaryContainer,
    secondary = NightSecondary,
    background = NightBackground,
    surface = NightSurface,
    surfaceVariant = NightSurfaceVariant,
    onBackground = NightOnBackground,
    onSurface = NightOnSurface,
    onSurfaceVariant = NightOnSurfaceVariant,
    outline = NightOutline
)

private val LightColorScheme = lightColorScheme(
    primary = SkyPrimary,
    primaryContainer = SkyPrimaryContainer,
    onPrimary = SkyOnPrimary,
    onPrimaryContainer = SkyOnPrimaryContainer,
    secondary = SkySecondary,
    background = SkyBackground,
    surface = SkySurface,
    surfaceVariant = SkySurfaceVariant,
    onBackground = SkyOnBackground,
    onSurface = SkyOnSurface,
    onSurfaceVariant = SkyOnSurfaceVariant,
    outline = SkyOutline
)

object NearbyThemeTokens {
    val alertColors: AlertColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAlertColors.current
}

@Composable
fun NearbyTheme(
    // Default to dark theme ("Night Sky") per Design.md §1 Principle 3 and §4
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val alertColors = AlertColorTokens()

    CompositionLocalProvider(LocalAlertColors provides alertColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}