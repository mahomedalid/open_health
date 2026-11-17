package org.openhealth.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Dark-first palette (PRD VIS-01). Text colors keep at least 4.5:1 contrast on [Surface]. */
internal object Palette {
    val Background = Color(0xFF080B12)
    val Surface = Color(0xFF111622)
    val SurfaceRaised = Color(0xFF182031)
    val Outline = Color(0xFF263048)
    val TextPrimary = Color(0xFFE9EDF5)
    val TextSecondary = Color(0xFF9AA5BC)
    val Accent = Color(0xFF7C9CFF)
    val Danger = Color(0xFFFF7A85)
}

/** One color per metric, used identically in tiles, charts, and legends (PRD VIS-02). */
internal object MetricColors {
    val HeartRate = Color(0xFFFF5C7A)
    val Hrv = Color(0xFFA78BFF)
    val RestingHr = Color(0xFFFFB547)
    val Activity = Color(0xFF3DDC97)
    val Battery = Color(0xFF2DD4BF)
    val SleepDeep = Color(0xFF4C6FFF)
    val SleepLight = Color(0xFF8FB0FF)
    val SleepRem = Color(0xFFC08BFF)
    val SleepAwake = Color(0xFFFF9E7A)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF0A1030),
    secondary = MetricColors.Hrv,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Outline,
    error = Palette.Danger,
)

private val typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(color = Palette.TextSecondary, letterSpacing = 0.6.sp),
    )
}

/** Large numeric readout used by metric tiles. */
internal val MetricValueStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

@Composable
internal fun OpenHealthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
