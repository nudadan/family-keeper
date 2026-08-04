package com.noesolution.gtracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette — matches the launcher icon accent.
val GardeniaBlue = Color(0xFF1565C0)
val GardeniaBlueDark = Color(0xFF0D47A1)
val GardeniaBlueContainer = Color(0xFFD6E6FF)
val GardeniaTeal = Color(0xFF00897B)
val GardeniaAmber = Color(0xFFF9A825)
val GardeniaRed = Color(0xFFD32F2F)

// Status colors used across screens (active/sharing vs. stopped/idle).
val StatusActiveGreen = Color(0xFF2E7D32)
val StatusActiveGreenContainer = Color(0xFFDCEFDD)
val StatusIdleGrey = Color(0xFF616161)
val StatusIdleGreyContainer = Color(0xFFE7E7E7)
val StatusWarnAmberContainer = Color(0xFFFFF1C2)
val StatusWarnAmberOn = Color(0xFF7A5B00)

private val LightColors = lightColorScheme(
    primary = GardeniaBlue,
    onPrimary = Color.White,
    primaryContainer = GardeniaBlueContainer,
    onPrimaryContainer = GardeniaBlueDark,
    secondary = GardeniaTeal,
    onSecondary = Color.White,
    tertiary = GardeniaAmber,
    onTertiary = Color(0xFF3E2E00),
    error = GardeniaRed,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00325A),
    primaryContainer = GardeniaBlueDark,
    onPrimaryContainer = GardeniaBlueContainer,
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332C),
    tertiary = Color(0xFFFFD54F),
    onTertiary = Color(0xFF3E2E00),
    error = Color(0xFFEF9A9A),
    background = Color(0xFF121316),
    surface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFF26282C),
)

@Composable
fun GardeniaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
