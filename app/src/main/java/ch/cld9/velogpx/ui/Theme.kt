package ch.cld9.velogpx.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA9F3C9),
    onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF4F6355),
    tertiary = Color(0xFF3D6473),
    surface = Color(0xFFF8FAF6),
    surfaceContainer = Color(0xFFECEFEA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD7AE),
    onPrimary = Color(0xFF003921),
    primaryContainer = Color(0xFF005232),
    secondary = Color(0xFFB6CCBC),
    tertiary = Color(0xFFA5CDDE),
)

@Composable
fun VeloGpxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

