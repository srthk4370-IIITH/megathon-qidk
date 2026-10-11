package com.megathon.incidentvoice.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.foundation.border
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.CardShape
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.divider
import com.megathon.incidentvoice.ui.theme.secondaryText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.ask.AskController
import com.megathon.incidentvoice.ask.AskExchange
import com.megathon.incidentvoice.ask.AskHit
import com.megathon.incidentvoice.core.Taxonomy
import com.megathon.incidentvoice.ui.relativeTime
import com.megathon.incidentvoice.ui.theme.Grey
import com.megathon.incidentvoice.ui.theme.SevCritical

private val SUGGESTIONS = listOf(
    "How many critical incidents this week?", "What happened in Bay 4?", "Show fires in the last 7 days",
    "Was anyone injured recently?", "Anything about a chemical smell?", "What is the latest incident?",
)

/** PRD §37 O-2: ask about past incidents by voice or text; answers come from the on-device database only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(ctl: AskController, tx: Taxonomy, showDebug: Boolean, onMic: () -> Unit, onOpen: (String) -> Unit) {
    val ui by ctl.ui.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ui.exchanges.size, ui.exchanges.lastOrNull()?.answer) { if (ui.exchanges.isNotEmpty()) list.animateScrollToItem(ui.exchanges.size - 1) }
    fun send() { if (text.isNotBlank()) { ctl.ask(text); text = "" } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ScreenTitle("Ask about reports")
                Text(if (ui.vectorSearch) "Answers come from the reports on this device. Search by meaning and by keyword." else "Answers come from the reports on this device. Keyword search.",
                    style = MaterialTheme.typography.bodySmall, color = secondaryText)
            }
            if (ui.exchanges.isNotEmpty()) PlainButton("Clear", ctl::clear)
        }
        if (ui.exchanges.isEmpty()) {
            SectionHeader("Examples")
            FlatCard {
                SUGGESTIONS.forEachIndexed { n, s ->
                    if (n > 0) QDivider()
                    Text(s, Modifier.fillMaxWidth().clickable { ctl.ask(s) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.exchanges, key = { it.id }) { ex -> ExchangeView(ex, tx, showDebug, onOpen) }
                if (ui.thinking) item { BusyRow("Searching reports") }
            }
        }
        ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (ui.listening) { LevelMeter(ui.level); Text("Listening. Ask your question.", style = MaterialTheme.typography.bodySmall, color = secondaryText) }
        if (ui.transcribing) Text("Transcribing", style = MaterialTheme.typography.bodySmall, color = secondaryText)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Ask a question") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            )
            IconButton(onClick = { send() }, enabled = text.isNotBlank(), modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            FilledIconButton(onClick = onMic, modifier = Modifier.size(52.dp)) { Icon(if (ui.listening) Icons.Outlined.Stop else Icons.Outlined.Mic, if (ui.listening) "Stop listening" else "Ask by voice") }
        }
    }
}

@Composable
private fun ExchangeView(ex: AskExchange, tx: Taxonomy, showDebug: Boolean, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(ex.question, modifier = Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.secondaryContainer, CardShape).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        val a = ex.answer
        when {
            ex.error != null -> Text("Could not search: ${ex.error}", color = MaterialTheme.colorScheme.error)
            a != null -> {
                Text(a.text, Modifier.background(MaterialTheme.colorScheme.surface, CardShape).border(1.dp, divider, CardShape).padding(horizontal = 12.dp, vertical = 10.dp))
                if (a.hits.isNotEmpty()) FlatCard { a.hits.forEachIndexed { n, h -> if (n > 0) QDivider(); HitCard(h, tx, showDebug, onOpen) } }
                if (a.total > a.hits.size) Text("${a.total - a.hits.size} more. Narrow the question or use the Dashboard.", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                if (showDebug) Text("${a.tookMs} ms · ${a.mode} · types=${a.query.types} sev=${a.query.severities} loc=${a.query.locationLabel} time=${a.query.timeLabel} text=\"${a.query.freeText}\"",
                    style = Mono, color = secondaryText)
            }
        }
    }
}

@Composable
fun HitCard(h: AskHit, tx: Taxonomy, showDebug: Boolean, onOpen: (String) -> Unit) {
    IncidentRow(h.severity, h.title, listOfNotNull(h.location, relativeTime(h.whenMs)).joinToString(" · "), h.refNo, { onOpen(h.id) }, extra = {
        Text(listOfNotNull(tx.category(h.type ?: "")?.label, h.semantic?.let { "${(it * 100).toInt()}% match" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    })
}
