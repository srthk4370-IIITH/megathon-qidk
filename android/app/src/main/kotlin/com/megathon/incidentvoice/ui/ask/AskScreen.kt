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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import com.megathon.incidentvoice.ui.components.LevelMeter
import com.megathon.incidentvoice.ui.components.SeverityChip
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
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask about incidents", style = MaterialTheme.typography.titleLarge)
                Text(if (ui.vectorSearch) "Answers from this device's reports · meaning + keyword search" else "Answers from this device's reports · keyword search",
                    style = MaterialTheme.typography.bodySmall, color = Grey)
            }
            if (ui.exchanges.isNotEmpty()) TextButton(ctl::clear) { Text("Clear") }
        }
        if (ui.exchanges.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Try asking", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { ctl.ask(s) }, label = { Text(s) }) }
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.exchanges, key = { it.id }) { ex -> ExchangeView(ex, tx, showDebug, onOpen) }
                if (ui.thinking) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Searching reports…") } }
            }
        }
        ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (ui.listening) { LevelMeter(ui.level); Text("Listening… ask your question", style = MaterialTheme.typography.bodySmall) }
        if (ui.transcribing) Text("Transcribing…", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Ask a question…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            )
            IconButton(onClick = { send() }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            FilledIconButton(
                onClick = onMic,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (ui.listening) SevCritical else MaterialTheme.colorScheme.primary),
            ) { Icon(if (ui.listening) Icons.Default.Stop else Icons.Default.Mic, if (ui.listening) "Stop listening" else "Ask by voice") }
        }
    }
}

@Composable
private fun ExchangeView(ex: AskExchange, tx: Taxonomy, showDebug: Boolean, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(ex.question, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp)).padding(10.dp).widthIn(max = 300.dp))
        }
        val a = ex.answer
        when {
            ex.error != null -> Text("Could not search: ${ex.error}", color = MaterialTheme.colorScheme.error)
            a != null -> {
                Text(a.text, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp, 14.dp, 14.dp, 2.dp)).padding(10.dp))
                a.hits.forEach { HitCard(it, tx, showDebug, onOpen) }
                if (a.total > a.hits.size) Text("+ ${a.total - a.hits.size} more — narrow the question or use the Dashboard", style = MaterialTheme.typography.labelSmall, color = Grey)
                if (showDebug) Text("${a.tookMs} ms · ${a.mode} · types=${a.query.types} sev=${a.query.severities} loc=${a.query.locationLabel} time=${a.query.timeLabel} text=\"${a.query.freeText}\"",
                    style = MaterialTheme.typography.labelSmall, color = Grey, fontStyle = FontStyle.Italic)
            }
        }
    }
}

@Composable
fun HitCard(h: AskHit, tx: Taxonomy, showDebug: Boolean, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(h.id) }) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityChip(h.severity)
                Text(tx.category(h.type ?: "")?.label ?: "Incident", style = MaterialTheme.typography.labelMedium, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                h.semantic?.let { Text("${(it * 100).toInt()}% match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                Text(relativeTime(h.whenMs), style = MaterialTheme.typography.labelSmall)
            }
            Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(h.refNo, h.location).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
