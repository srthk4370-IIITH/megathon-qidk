package com.megathon.incidentvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.orchestrator.EmergencyBanner
import com.megathon.incidentvoice.orchestrator.EmergencyLevel
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.SevCritical

@Composable
fun MicButton(listening: Boolean, level: Float, enabled: Boolean, size: Dp = 120.dp, onClick: () -> Unit) {
    val scale = if (listening) 1f + 0.25f * level else 1f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 40.dp)) {
        if (listening) Box(Modifier.size(size).scale(scale + 0.15f).clip(CircleShape).background(SevCritical.copy(alpha = 0.18f)))
        FilledIconButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.size(size).scale(scale),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (listening) SevCritical else MaterialTheme.colorScheme.primary),
        ) { Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, if (listening) "Stop" else "Start", Modifier.size(size / 2)) }
    }
}

@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
}

@Composable
fun EmergencyBannerView(b: EmergencyBanner, onCall: () -> Unit, onDismiss: () -> Unit) {
    val red = b.level == EmergencyLevel.RED
    Card(colors = CardDefaults.cardColors(containerColor = if (red) SevCritical else Amber), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (red) "EMERGENCY" else "Possible emergency", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            b.steps.take(4).forEach { Text("• $it", color = Color.White, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCall, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SevCritical)) { Text("Call ERT (${b.ertNumber})") }
                TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.White) }
            }
        }
    }
}
