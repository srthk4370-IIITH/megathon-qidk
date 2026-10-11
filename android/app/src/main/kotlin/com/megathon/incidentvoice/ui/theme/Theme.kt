package com.megathon.incidentvoice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.R

// ---- Qalam design system (HANDBOOK "Design system"). One accent, neutral everything else.
val Coral = Color(0xFFE5484D)
val CoralPressed = Color(0xFFC93A3F)
val CoralDark = Color(0xFFFF6B70)            // accent on the dark theme
val EmergencyRed = Color(0xFFC62828)         // emergency banner / response clock, flat
val SevLow = Color(0xFF16A34A)
val SevMedium = Color(0xFFD97706)
val SevHigh = Color(0xFFEA580C)
val SevCritical = Color(0xFFB91C1C)
val Amber = Color(0xFFB45309)                // "Check" / assumed values; dark enough to read as text on white
val FilledGreen = Color(0xFF15803D)
val Grey = Color(0xFF6B7280)

private val Light = lightColorScheme(
    primary = Coral, onPrimary = Color.White, primaryContainer = Color(0xFFFDECEC), onPrimaryContainer = Color(0xFF7F1D1D),
    secondary = Color(0xFF111827), onSecondary = Color.White, secondaryContainer = Color(0xFFEEF0F3), onSecondaryContainer = Color(0xFF111827),
    tertiary = Color(0xFF111827), onTertiary = Color.White, tertiaryContainer = Color(0xFFEEF0F3), onTertiaryContainer = Color(0xFF111827),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827), surfaceVariant = Color(0xFFF1F2F4), onSurfaceVariant = Color(0xFF6B7280),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White, surfaceContainerHighest = Color.White,
    outline = Color(0xFFD1D5DB), outlineVariant = Color(0xFFE5E7EB),
    error = SevCritical, onError = Color.White, errorContainer = Color(0xFFFEF2F2), onErrorContainer = Color(0xFF7F1D1D),
)
private val Dark = darkColorScheme(
    primary = CoralDark, onPrimary = Color(0xFF0F1115), primaryContainer = Color(0xFF3A1D1F), onPrimaryContainer = Color(0xFFFFD9DA),
    secondary = Color(0xFFF3F4F6), onSecondary = Color(0xFF0F1115), secondaryContainer = Color(0xFF262A33), onSecondaryContainer = Color(0xFFF3F4F6),
    tertiary = Color(0xFFF3F4F6), onTertiary = Color(0xFF0F1115), tertiaryContainer = Color(0xFF262A33), onTertiaryContainer = Color(0xFFF3F4F6),
    background = Color(0xFF0F1115), onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF181B21), onSurface = Color(0xFFF3F4F6), surfaceVariant = Color(0xFF20242C), onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF181B21), surfaceContainerLow = Color(0xFF181B21), surfaceContainer = Color(0xFF181B21),
    surfaceContainerHigh = Color(0xFF181B21), surfaceContainerHighest = Color(0xFF181B21),
    outline = Color(0xFF3A404C), outlineVariant = Color(0xFF262A33),
    error = Color(0xFFF87171), onError = Color(0xFF0F1115), errorContainer = Color(0xFF3A1D1F), onErrorContainer = Color(0xFFFECACA),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) })

/** Bundled in res/font (SIL OFL, licences in assets/licenses); nothing is downloaded at run time. */
val Inter = variable(R.font.inter, 400, 500, 600, 700)
val JetBrainsMono = variable(R.font.jetbrains_mono, 400, 500)

/** Reference numbers, timings and the proof line only. */
val Mono = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

private fun inter(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.4f).toInt()) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

// Hierarchy through size and weight: titles 22 semibold, section headers 13 medium, body 15, captions 13.
private val Type = Typography(
    displayLarge = inter(34, FontWeight.SemiBold), displayMedium = inter(30, FontWeight.SemiBold), displaySmall = inter(28, FontWeight.SemiBold),
    headlineLarge = inter(28, FontWeight.SemiBold), headlineMedium = inter(22, FontWeight.SemiBold), headlineSmall = inter(22, FontWeight.SemiBold),
    titleLarge = inter(22, FontWeight.SemiBold), titleMedium = inter(17, FontWeight.SemiBold), titleSmall = inter(13, FontWeight.Medium),
    bodyLarge = inter(15), bodyMedium = inter(15), bodySmall = inter(13),
    labelLarge = inter(15, FontWeight.Medium), labelMedium = inter(13, FontWeight.Medium), labelSmall = inter(12, FontWeight.Medium),
)

// 12 dp on cards, 10 dp on buttons, fields and chips.
private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
)
val ButtonShape = RoundedCornerShape(10.dp)
val CardShape = RoundedCornerShape(12.dp)

@Composable
fun IncidentVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, shapes = Shapes, content = content)
}

fun severityColor(s: String?): Color = when (s) {
    "LOW" -> SevLow; "MEDIUM" -> SevMedium; "HIGH" -> SevHigh; "CRITICAL" -> SevCritical; else -> Grey
}

/** True when the user switched animations off in the system settings. */
@Composable
fun reduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

val divider: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
val secondaryText: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
