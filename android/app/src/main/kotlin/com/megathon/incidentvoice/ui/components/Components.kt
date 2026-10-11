package com.megathon.incidentvoice.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.orchestrator.EmergencyBanner
import com.megathon.incidentvoice.orchestrator.EmergencyLevel
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.ButtonShape
import com.megathon.incidentvoice.ui.theme.CardShape
import com.megathon.incidentvoice.ui.theme.EmergencyRed
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.divider
import com.megathon.incidentvoice.ui.theme.reduceMotion
import com.megathon.incidentvoice.ui.theme.secondaryText

/** Section header: 13 sp medium, secondary grey, sentence case. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = secondaryText)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)

@Composable
fun QDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = divider)

/** The one primary action of a screen: solid coral, full width, 52 dp. 19 sp semibold keeps white-on-coral within the large-text contrast rule. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled, shape = ButtonShape, elevation = null) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = ButtonShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
    ) { Text(text) }
}

/** Plain text action in the text colour (coral is kept for the primary button). */
@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = ButtonShape, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

/** A flat white surface with a 1 dp border: the only "card" in the app. No shadow. */
@Composable
fun FlatCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surface).border(1.dp, divider, CardShape), content = content)
}

/**
 * The main report button: a solid coral circle with a mic, and one thin ring that follows the voice level while
 * listening. Static when idle.
 */
@Composable
fun MicButton(listening: Boolean, level: Float, enabled: Boolean, size: Dp = 96.dp, onClick: () -> Unit) {
    val still = reduceMotion()
    val grow by animateFloatAsState(if (listening && !still) level.coerceIn(0f, 1f) else 0f, tween(120), label = "micRing")
    val coral = MaterialTheme.colorScheme.primary
    val ring = size + 16.dp + 28.dp * grow
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 48.dp)) {
        Box(Modifier.size(ring).border(2.dp, if (listening) coral else divider, CircleShape))
        Box(
            Modifier.size(size).clip(CircleShape).background(if (enabled) coral else MaterialTheme.colorScheme.outline)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (listening) "Stop" else "Report incident", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic, if (listening) "Stop" else "Report incident", Modifier.size(size * 0.42f), tint = MaterialTheme.colorScheme.onPrimary) }
    }
}

@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { level.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.onSurface, trackColor = divider, drawStopIndicator = {},
    )
}

/** Small spinner + text for "working" states; no decoration. */
@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp)); Text(text)
    }
}

/** Incident list row: severity dot + title, a secondary line, the reference number in mono, a chevron. */
@Composable
fun IncidentRow(severity: String?, title: String, secondary: String, refNo: String?, onClick: () -> Unit, modifier: Modifier = Modifier, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityDot(severity); Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(start = 20.dp)) {
                if (secondary.isNotBlank()) Text(secondary, style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                refNo?.let { Text(it, style = Mono, color = secondaryText) }
                extra?.invoke(this)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = secondaryText)
    }
}

/** Emergency banner: flat red, white text, plain white buttons. */
@Composable
fun EmergencyBannerView(b: EmergencyBanner, onCall: () -> Unit, onDismiss: () -> Unit) {
    val red = b.level == EmergencyLevel.RED
    Column(Modifier.fillMaxWidth().clip(CardShape).background(if (red) EmergencyRed else Amber).padding(16.dp)) {
        Text(if (red) "Emergency" else "Possible emergency", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        b.steps.take(4).forEachIndexed { i, s -> Text("${i + 1}. $s", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onCall, Modifier.height(52.dp), shape = ButtonShape, elevation = null, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = if (red) EmergencyRed else Amber)) {
                Text("Call ERT (${b.ertNumber})", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp), shape = ButtonShape) { Text("Dismiss", color = Color.White) }
        }
    }
}
