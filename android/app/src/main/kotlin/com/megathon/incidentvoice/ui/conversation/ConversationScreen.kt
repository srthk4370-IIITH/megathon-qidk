package com.megathon.incidentvoice.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.core.FieldStatus
import com.megathon.incidentvoice.core.Taxonomy
import com.megathon.incidentvoice.orchestrator.*
import com.megathon.incidentvoice.ui.FormDisplay
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.FilledGreen
import com.megathon.incidentvoice.ui.theme.Grey
import com.megathon.incidentvoice.ui.theme.SevCritical

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(ui: ConversationUiState, tx: Taxonomy, o: ConversationOrchestrator, showDebug: Boolean) {
    var typed by remember(ui.state) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.timeline.size, ui.emergency != null) { if (ui.timeline.isNotEmpty()) listState.animateScrollToItem(ui.timeline.size - 1 + if (ui.emergency != null && ui.response == null) 1 else 0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LinearProgressIndicator(progress = { ui.validation?.progress ?: 0f }, Modifier.fillMaxWidth().padding(top = 8.dp))
        ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        // Extra 1: pinned response clock (replaces the banner, which carries the same steps)
        ui.response?.let { ResponseClockCard(it, o::onResponseStep, Modifier.padding(top = 6.dp)) }

        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the banner scrolls with the timeline so it never squeezes the conversation on a landscape monitor
            ui.emergency?.takeIf { ui.response == null }?.let { e -> item(key = "emergency") { EmergencyBannerView(e, o::onEmergencyCall, o::onEmergencyDismiss) } }
            items(ui.timeline, key = { it.id }) { b -> BubbleView(b) }
            item { LiveForm(ui, tx) }
        }

        Controls(ui, o, typed, { typed = it }, showDebug)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BubbleView(b: Bubble) {
    when (b.role) {
        BubbleRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(b.text, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp)).padding(10.dp).widthIn(max = 300.dp))
        }
        BubbleRole.ASSISTANT -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(b.text, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp, 14.dp, 14.dp, 2.dp)).padding(10.dp).widthIn(max = 300.dp))
            if (b.speaking) Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speaking", Modifier.padding(start = 6.dp))
        }
        BubbleRole.SYSTEM -> Text(b.text, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = Grey)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveForm(ui: ConversationUiState, tx: Taxonomy) {
    val rows = FormDisplay.rows(ui.form, tx)
    val missing = ui.validation?.missing.orEmpty()
    if (rows.isEmpty() && ui.streamingFields.isEmpty() && missing.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("Form", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { r ->
                    val color = when (r.status) {
                        FieldStatus.FILLED, FieldStatus.USER_EDITED -> FilledGreen
                        FieldStatus.INFERRED, FieldStatus.DEFAULTED -> Amber
                        else -> Grey
                    }
                    val pending = r.key in ui.streamingFields
                    Text("${r.label}: ${r.value}", style = MaterialTheme.typography.labelMedium,
                        color = if (pending) Grey else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.border(1.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                missing.forEach { k ->
                    Text("${tx.label(k)}: needed", style = MaterialTheme.typography.labelMedium, color = SevCritical,
                        modifier = Modifier.border(1.dp, SevCritical, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Controls(ui: ConversationUiState, o: ConversationOrchestrator, typed: String, onTyped: (String) -> Unit, showDebug: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (ui.state) {
            ConvState.LISTENING -> {
                LevelMeter(ui.level)
                Text(if (ui.listeningFor == ListenKind.NARRATION) "Listening… ${ui.elapsedMs / 1000}s (max 30s)" else "Listening for your answer… ${ui.elapsedMs / 1000}s",
                    style = MaterialTheme.typography.bodySmall)
                MicButton(listening = true, level = ui.level, enabled = true, size = 80.dp, onClick = o::onStopTapped)
            }
            ConvState.TRANSCRIBING -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Transcribing…") }
            ConvState.TRANSCRIPT_REVIEW -> {
                OutlinedTextField(ui.transcriptDraft, o::onTranscriptEdited, Modifier.fillMaxWidth().onFocusChanged { o.onTranscriptFocus(it.isFocused) },
                    label = { Text("Heard this — tap to correct, or it continues by itself") })
                LinearProgressIndicator(progress = { ui.countdownMs / 2000f }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(o::onContinue) { Text("Continue") }
                    OutlinedButton(o::onMicTapped) { Text("Re-record") }
                }
            }
            ConvState.EXTRACTING, ConvState.VALIDATING, ConvState.PATCHING ->
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (ui.state == ConvState.EXTRACTING) "Filling the form…" else "Updating the form…") }
            ConvState.ASKING -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("Asking…") }
            ConvState.TYPING -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, onTyped, Modifier.weight(1f), label = { Text(if (ui.question != null) "Type your answer" else "Describe the incident") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (typed.isNotBlank()) o.onTypeInstead(typed) }))
                Spacer(Modifier.width(8.dp)); Button({ o.onTypeInstead(typed) }, enabled = typed.isNotBlank()) { Text("Send") }
            }
            ConvState.ERROR_ENGINE, ConvState.ERROR_PARSE, ConvState.ERROR_AUDIO -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(ui.error ?: "Something went wrong.", color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (ui.retryable) Button(o::onRetry) { Text("Retry") }
                        if (ui.state == ConvState.ERROR_AUDIO) {
                            Button(o::onMicTapped) { Text("Try mic again") }
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            OutlinedButton({
                                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Open settings") }
                        }
                        OutlinedButton(o::onManualFallback) { Text("Fill manually") }
                    }
                }
            }
            else -> {}
        }
        if (showDebug) {
            Text("asr ${ui.debug.asrMs} ms · llm ${ui.debug.llmMs} ms · ${ui.debug.tokensOut} tok · ${"%.1f".format(ui.debug.decodeTps)} tok/s · ${ui.debug.lastTask} · round ${ui.round}", style = MaterialTheme.typography.labelSmall, color = Grey)
            if (ui.debug.lastToolJson.isNotBlank()) Text(ui.debug.lastToolJson.take(400), style = MaterialTheme.typography.labelSmall, color = Grey, maxLines = 6)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.state != ConvState.TYPING) TextButton(o::onTypeInsteadRequested) { Text("Type instead") }
            TextButton(o::onReviewNow) { Text("Review now") }
            TextButton(o::onCancel) { Text("Cancel") }
        }
    }
}
