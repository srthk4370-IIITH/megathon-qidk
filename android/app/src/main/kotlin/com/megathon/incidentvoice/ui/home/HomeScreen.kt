package com.megathon.incidentvoice.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.relativeTime
import com.megathon.incidentvoice.ui.theme.EmergencyRed
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.secondaryText

@Composable
fun HomeScreen(c: AppContainer, onMic: () -> Unit, onTypeInstead: () -> Unit, notice: String?, onOpen: (String) -> Unit) {
    val status by c.engine.status.collectAsStateWithLifecycle()
    val llm by c.llmBackend.collectAsStateWithLifecycle()
    val recent by remember { c.repository.observeList(DashboardFilter(), 3) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by c.settingsSnapshot.collectAsStateWithLifecycle()
    val clockBad = remember { java.time.Year.now().value < 2026 }
    val wakeOn = c.config.app.features.wakeWord && settings.wakeEnabled

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (clockBad) Text("Device clock is wrong. Relative times will be off.", color = Color.White, modifier = Modifier.fillMaxWidth().background(EmergencyRed).padding(horizontal = 16.dp, vertical = 8.dp))

        // the one centred element of the app: the report button
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            MicButton(listening = false, level = 0f, enabled = status is EngineStatus.Ready, size = 112.dp, onClick = onMic)
            Text(
                if (wakeOn) "Tap to report · or say '${c.config.app.wakeDisplay}'" else "Tap to report",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(engineLine(status, llm), style = MaterialTheme.typography.bodySmall, color = if (status is EngineStatus.Error) MaterialTheme.colorScheme.error else secondaryText, textAlign = TextAlign.Center)
            if (wakeOn) {
                val w by c.wake.ui.collectAsStateWithLifecycle()
                w.error?.let { Text("Wake word unavailable: $it", style = MaterialTheme.typography.bodySmall, color = secondaryText) }
                if (settings.showDebug) Text("heard: ${w.lastHeard.ifBlank { "–" }} · ${if (w.listening) "listening" else "paused"}", style = Mono, color = secondaryText, maxLines = 1)
            }
            notice?.let { Spacer(Modifier.height(8.dp)); Text(it, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(8.dp))
            PlainButton("Type instead", onTypeInstead)
        }

        (status as? EngineStatus.Error)?.let { e ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(e.error.message.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("You can still type a report and fill the form by hand.", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    SecondaryButton("Retry engine", { c.selectEngine(c.settingsSnapshot.value.engineMode) })
                    SecondaryButton("Use mock engine", { c.selectEngine("MOCK") })
                }
            }
        }

        SectionHeader("Recent reports", Modifier.padding(horizontal = 16.dp))
        if (recent.isEmpty()) Text("No reports yet. Tap the button above to create one.", Modifier.padding(horizontal = 16.dp), color = secondaryText)
        else FlatCard(Modifier.padding(horizontal = 16.dp)) {
            recent.forEachIndexed { n, i ->
                if (n > 0) QDivider()
                IncidentRow(i.severity, i.title, listOfNotNull(i.location, relativeTime(i.createdAt)).joinToString(" · "), i.refNo, { onOpen(i.id) })
            }
        }
        Spacer(Modifier.weight(1f))
        ProofLine(c, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** Plain words for the engine state (the top bar only shows the dot). */
private fun engineLine(s: EngineStatus, llm: String): String = when (s) {
    is EngineStatus.Ready -> when {
        s.info.backend == "MOCK" -> "Mock engine (scripted speech)"
        llm == "FALLBACK" -> "Speech recognised on this device · stand-in language model"
        else -> "Speech and language model run on this device"
    }
    is EngineStatus.Loading -> "Loading models, ${(s.progress * 100).toInt()} %"
    is EngineStatus.Error -> "Engine error ${s.error.code}"
    EngineStatus.Idle -> "Starting"
}
