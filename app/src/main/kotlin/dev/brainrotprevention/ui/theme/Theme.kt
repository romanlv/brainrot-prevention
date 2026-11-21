package dev.brainrotprevention.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The one accent colour for "blocking" (§12): a warm coral that stays the same family in light and dark and
 * does not depend on the wallpaper, so "Blocking" always reads the same.
 */
@Immutable
data class BlockingColors(val accent: Color, val onAccent: Color, val container: Color, val onContainer: Color)

private val LightBlocking = BlockingColors(
    accent = Color(0xFFB4442C),
    onAccent = Color.White,
    container = Color(0xFFFFDAD2),
    onContainer = Color(0xFF3D0700),
)
private val DarkBlocking = BlockingColors(
    accent = Color(0xFFFFB4A3),
    onAccent = Color(0xFF611404),
    container = Color(0xFF8C2D18),
    onContainer = Color(0xFFFFDAD2),
)

/** Calm sage/teal fallback palette for devices without dynamic colour (API < 31). */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF39675B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEDDD),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635B),
    secondaryContainer = Color(0xFFCDE8DE),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF416277),
    tertiaryContainer = Color(0xFFC5E7FF),
    background = Color(0xFFF6FAF7),
    surface = Color(0xFFF6FAF7),
    surfaceContainer = Color(0xFFEAEFEC),
    surfaceContainerHigh = Color(0xFFE4E9E6),
    surfaceContainerLow = Color(0xFFF0F5F1),
    error = Color(0xFFBA1A1A),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA1D1C1),
    onPrimary = Color(0xFF02382D),
    primaryContainer = Color(0xFF1F4F43),
    onPrimaryContainer = Color(0xFFBCEDDD),
    secondary = Color(0xFFB2CCC2),
    secondaryContainer = Color(0xFF344C44),
    onSecondaryContainer = Color(0xFFCDE8DE),
    tertiary = Color(0xFFA9CBE3),
    tertiaryContainer = Color(0xFF294A5E),
    background = Color(0xFF0F1513),
    surface = Color(0xFF0F1513),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerLow = Color(0xFF171D1B),
    error = Color(0xFFFFB4AB),
)

val LocalBlockingColors = staticCompositionLocalOf { LightBlocking }

/** Material 3 theme: light/dark, dynamic colour on API 31+, plus [LocalBlockingColors]. */
@Composable
fun BrpTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalBlockingColors provides if (darkTheme) DarkBlocking else LightBlocking) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
