package com.megathon.incidentvoice.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.AppContainer
import com.megathon.incidentvoice.data.DashboardFilter
import com.megathon.incidentvoice.domain.engine.EngineStatus
import com.megathon.incidentvoice.ui.components.MicButton
import com.megathon.incidentvoice.ui.components.SeverityChip
import com.megathon.incidentvoice.ui.relativeTime
import com.megathon.incidentvoice.ui.theme.FilledGreen
import com.megathon.incidentvoice.ui.theme.SevCritical

@Composable
fun HomeScreen(c: AppContainer, onMic: () -> Unit, onTypeInstead: () -> Unit, notice: String?, onOpen: (String) -> Unit) {
    val status by c.engine.status.collectAsStateWithLifecycle()
    val recent by remember { c.repository.observeList(DashboardFilter(), 3) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val clockBad = remember { java.time.Year.now().value < 2026 }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (clockBad) Text("Device clock is wrong — relative times will be off.", color = Color.White,
            modifier = Modifier.fillMaxWidth().background(SevCritical).padding(8.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        EnginePill(status)
        Spacer(Modifier.weight(1f))
        MicButton(listening = false, level = 0f, enabled = status is EngineStatus.Ready, onClick = onMic)
        Text("Tap and describe the incident", style = MaterialTheme.typography.titleMedium)
        Text("English or Hinglish", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notice?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        TextButton(onClick = onTypeInstead) { Text("Type instead") }
        Spacer(Modifier.weight(1f))
        if (recent.isNotEmpty()) {
            Text("Recent incidents", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.Start))
            recent.forEach { i ->
                Card(onClick = { onOpen(i.id) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeverityChip(i.severity)
                        Text(i.title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(relativeTime(i.createdAt), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun EnginePill(s: EngineStatus) {
    val (text, color) = when (s) {
        is EngineStatus.Ready -> (if (s.info.backend == "MOCK") "Mock engine ready" else "NPU ready") to FilledGreen
        is EngineStatus.Loading -> "Loading models… ${(s.progress * 100).toInt()} %" to Color(0xFFF9A825)
        is EngineStatus.Error -> "Engine error — ${s.error.code}" to SevCritical
        EngineStatus.Idle -> "Engine starting…" to Color.Gray
    }
    Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp))
}
