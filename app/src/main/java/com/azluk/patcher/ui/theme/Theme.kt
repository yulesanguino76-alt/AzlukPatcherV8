package com.azluk.patcher.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AzlukBlue = Color(0xFF5B8CFF)
val AzlukBlueDim = Color(0xFF315BC7)
val AzlukCyan = Color(0xFF48D7FF)
val AzlukBg = Color(0xFF070A11)
val AzlukSurface = Color(0xFF0E1420)
val AzlukSurface2 = Color(0xFF141C2B)
val AzlukSurfaceVar = Color(0xFF202C42)
val AzlukOnBg = Color(0xFFF2F6FF)
val AzlukOnSurface = Color(0xFF9EADC5)
val AzlukError = Color(0xFFFF5577)
val AzlukSuccess = Color(0xFF35E6A1)
val AzlukWarning = Color(0xFFFFC45C)

private val DarkColorScheme = darkColorScheme(
    primary = AzlukBlue,
    onPrimary = Color.White,
    primaryContainer = AzlukBlueDim,
    secondary = AzlukCyan,
    background = AzlukBg,
    surface = AzlukSurface,
    surfaceVariant = AzlukSurfaceVar,
    onBackground = AzlukOnBg,
    onSurface = AzlukOnSurface,
    error = AzlukError,
    onError = Color.White
)

@Composable
fun AzlukTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ),
            labelLarge = MaterialTheme.typography.labelLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        ),
        content = content
    )
}
