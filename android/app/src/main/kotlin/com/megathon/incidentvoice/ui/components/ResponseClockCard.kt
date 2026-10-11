package com.megathon.incidentvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.core.ResponseClock
import com.megathon.incidentvoice.orchestrator.ResponseClockUi
import com.megathon.incidentvoice.ui.theme.ButtonShape
import com.megathon.incidentvoice.ui.theme.CardShape
import com.megathon.incidentvoice.ui.theme.EmergencyRed
import com.megathon.incidentvoice.ui.theme.FilledGreen
import com.megathon.incidentvoice.ui.theme.Mono
import kotlinx.coroutines.delay

/**
 * Extra 1 — response clock: elapsed time since the emergency was detected and the category's immediate SOP steps as
 * large tap targets. Pinned above the conversation / review; reporting continues underneath. Flat red, white text,
 * plain white buttons, the timer in mono; nothing blinks.
 */
@Composable
fun ResponseClockCard(rc: ResponseClockUi, onStep: (String) -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rc.startedAtMs) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    var expanded by remember { mutableStateOf(true) }
    val ink = Color(0xFF111827)

    Column(modifier.fillMaxWidth().clip(CardShape).background(EmergencyRed).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ResponseClock.mmss(now - rc.startedAtMs), color = Color.White, style = Mono.copy(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Emergency", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("${rc.steps.count { it.done }} of ${rc.steps.size} steps done", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            IconButton({ expanded = !expanded }, Modifier.size(48.dp)) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse steps" else "Show steps", tint = Color.White) }
        }
        // the reminder is a plain white line, not a flashing card
        if (rc.reminder) Text("Emergency team not called yet", Modifier.padding(end = 8.dp, top = 4.dp).fillMaxWidth().background(Color.White, ButtonShape).padding(horizontal = 12.dp, vertical = 8.dp),
            color = EmergencyRed, fontWeight = FontWeight.SemiBold)
        if (expanded) {
            rc.steps.forEach { s ->
                val done = s.done
                Row(
                    Modifier.padding(end = 8.dp, top = 8.dp).fillMaxWidth().heightIn(min = 52.dp).clip(ButtonShape).background(Color.White)
                        .clickable(role = Role.Button) { onStep(s.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (done) Icons.Outlined.CheckCircle else if (s.id == ResponseClock.ERT) Icons.Outlined.Call else Icons.Outlined.RadioButtonUnchecked,
                        null, tint = if (done) FilledGreen else if (s.id == ResponseClock.ERT) EmergencyRed else Color(0xFF6B7280))
                    Spacer(Modifier.width(12.dp))
                    Text(s.label, Modifier.weight(1f), color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = if (s.id == ResponseClock.ERT) FontWeight.SemiBold else FontWeight.Normal)
                    if (done) Text(ResponseClock.mmss(s.doneAtMs!! - rc.startedAtMs), style = Mono, color = FilledGreen, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(ResponseClock.DISCLAIMER, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
        }
    }
}
