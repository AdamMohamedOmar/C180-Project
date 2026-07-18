package com.kompressorlink.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.health.HealthStatus

// Phase 4.5 dark automotive theme (spec §8; the mockup
// docs/superpowers/specs/2026-07-13-phase45-ui-mockup.html is the
// directional target). One semantic color set everywhere: gauges,
// subsystem cards, maintenance rows, DTC urgency.
object KlColors {
    val Ok = Color(0xFF4CAF7D)
    val Watch = Color(0xFFE0A83C)
    val Attention = Color(0xFFE05B4B)
    val Neutral = Color(0xFF6E8291)

    fun forLevel(level: GaugeLevel): Color = when (level) {
        GaugeLevel.OK -> Ok
        GaugeLevel.AMBER -> Watch
        GaugeLevel.RED -> Attention
        GaugeLevel.NEUTRAL, GaugeLevel.UNAVAILABLE -> Neutral
    }

    fun forStatus(status: HealthStatus): Color = when (status) {
        HealthStatus.OK -> Ok
        HealthStatus.WATCH -> Watch
        HealthStatus.ATTENTION -> Attention
    }
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB6C9),
    secondary = KlColors.Neutral,
    background = Color(0xFF101416),
    surface = Color(0xFF1A1F22),
    surfaceVariant = Color(0xFF22282C),
    onBackground = Color(0xFFE8ECEE),
    onSurface = Color(0xFFE8ECEE),
    onSurfaceVariant = Color(0xFF9AA4AA),
    error = KlColors.Attention,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF33667E),
    secondary = KlColors.Neutral,
    error = KlColors.Attention,
)

// Big legible numerals for gauge values (night-driving legibility).
private val KlTypography = Typography().let {
    it.copy(headlineMedium = it.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium))
}

// Dark is the design target; follows the system setting (spec §8).
@Composable
fun KompressorLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = KlTypography,
        content = content,
    )
}
