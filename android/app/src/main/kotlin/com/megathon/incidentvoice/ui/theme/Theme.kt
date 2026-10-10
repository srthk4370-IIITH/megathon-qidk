package com.megathon.incidentvoice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF16324F)
val SevLow = Color(0xFF2E7D32)
val SevMedium = Color(0xFFF9A825)
val SevHigh = Color(0xFFEF6C00)
val SevCritical = Color(0xFFC62828)
val Amber = Color(0xFFFFB300)
val FilledGreen = Color(0xFF2E7D32)
val Grey = Color(0xFF757575)

private val Light = lightColorScheme(primary = Navy, secondary = Color(0xFF00796B), tertiary = Color(0xFFEF6C00))
private val Dark = darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFF80CBC4), tertiary = Color(0xFFFFB74D))

@Composable
fun IncidentVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

fun severityColor(s: String?): Color = when (s) {
    "LOW" -> SevLow; "MEDIUM" -> SevMedium; "HIGH" -> SevHigh; "CRITICAL" -> SevCritical; else -> Grey
}
