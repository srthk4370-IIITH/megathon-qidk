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
    val llm by c.llmBackend.collectAsStateWithLifecycle()
    val recent by remember { c.repository.observeList(DashboardFilter(), 3) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val clockBad = remember { java.time.Year.now().value < 2026 }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (clockBad) Text("Device clock is wrong — relative times will be off.", color = Color.White,
            modifier = Modifier.fillMaxWidth().background(SevCritical).padding(8.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        EnginePill(status, llm)
        (status as? EngineStatus.Error)?.let { e ->
            Text(e.error.message.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ c.selectEngine(c.settingsSnapshot.value.engineMode) }) { Text("Retry engine") }
                TextButton({ c.selectEngine("MOCK") }) { Text("Use mock engine") }
            }
            Text("You can still type a report and fill the form by hand.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1f))
        MicButton(listening = false, level = 0f, enabled = status is EngineStatus.Ready, onClick = onMic)
        Text("Tap and describe the incident", style = MaterialTheme.typography.titleMedium)
        Text("English or Hinglish · hold the device about 20 cm from your mouth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
fun EnginePill(s: EngineStatus, llm: String = "") {
    val (text, color) = when (s) {
        is EngineStatus.Ready -> when {
            s.info.backend == "MOCK" -> "Mock engine ready" to FilledGreen
            llm == "FALLBACK" -> "On-device speech ready · LLM: mock" to Color(0xFFEF6C00)
            else -> "NPU ready" to FilledGreen
        }
        is EngineStatus.Loading -> "Loading models… ${(s.progress * 100).toInt()} %" to Color(0xFFF9A825)
        is EngineStatus.Error -> "Engine error — ${s.error.code}" to SevCritical
        EngineStatus.Idle -> "Engine starting…" to Color.Gray
    }
    Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp))
}
