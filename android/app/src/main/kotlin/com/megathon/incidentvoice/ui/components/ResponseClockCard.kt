package com.megathon.incidentvoice.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.core.ResponseClock
import com.megathon.incidentvoice.orchestrator.ResponseClockUi
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.FilledGreen
import com.megathon.incidentvoice.ui.theme.SevCritical
import kotlinx.coroutines.delay

/**
 * Extra 1 — response clock: elapsed time since the emergency was detected and the category's immediate SOP steps as
 * large tap targets. Pinned above the conversation / review; reporting continues underneath.
 */
@Composable
fun ResponseClockCard(rc: ResponseClockUi, onStep: (String) -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rc.startedAtMs) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    var expanded by remember { mutableStateOf(true) }
    val pulse = rememberInfiniteTransition(label = "pulse").animateColor(
        initialValue = SevCritical, targetValue = Amber, animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulseColor",
    )
    val bg = if (rc.reminder) pulse.value else SevCritical

    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ResponseClock.mmss(now - rc.startedAtMs), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Response clock", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (rc.reminder) "Emergency team not called yet" else "${rc.steps.count { it.done }} of ${rc.steps.size} steps done", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse steps" else "Show steps", tint = Color.White) }
            }
            if (expanded) {
                rc.steps.forEach { s ->
                    val done = s.done
                    OutlinedButton(
                        onClick = { onStep(s.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 3.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (done) Color.White.copy(alpha = 0.85f) else Color.White, contentColor = Color(0xFF16324F)),
                        border = BorderStroke(0.dp, Color.Transparent), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(if (done) Icons.Default.CheckCircle else if (s.id == ResponseClock.ERT) Icons.Default.Call else Icons.Default.CheckCircle,
                            null, tint = if (done) FilledGreen else if (s.id == ResponseClock.ERT) SevCritical else Color.LightGray)
                        Spacer(Modifier.width(10.dp))
                        Text(s.label, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = if (s.id == ResponseClock.ERT) FontWeight.Bold else FontWeight.Normal)
                        if (done) Text(ResponseClock.mmss(s.doneAtMs!! - rc.startedAtMs), fontFamily = FontFamily.Monospace, color = FilledGreen)
                    }
                }
                Text(ResponseClock.DISCLAIMER, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
