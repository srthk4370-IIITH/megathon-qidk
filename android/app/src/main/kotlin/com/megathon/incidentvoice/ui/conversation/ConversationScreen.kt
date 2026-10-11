package com.megathon.incidentvoice.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.core.FieldStatus
import com.megathon.incidentvoice.core.Taxonomy
import com.megathon.incidentvoice.orchestrator.*
import com.megathon.incidentvoice.ui.FormDisplay
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.CardShape
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.divider
import com.megathon.incidentvoice.ui.theme.secondaryText

@Composable
fun ConversationScreen(ui: ConversationUiState, tx: Taxonomy, o: ConversationOrchestrator, showDebug: Boolean, footer: @Composable () -> Unit = {}) {
    var typed by remember(ui.state) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.timeline.size, ui.emergency != null) { if (ui.timeline.isNotEmpty()) listState.animateScrollToItem(ui.timeline.size - 1 + if (ui.emergency != null && ui.response == null) 1 else 0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // how complete the form is; neutral, the accent is kept for the report button
        LinearProgressIndicator(progress = { ui.validation?.progress ?: 0f }, Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.onSurface, trackColor = divider, drawStopIndicator = {})
        ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        // Extra 1: pinned response clock (replaces the banner, which carries the same steps)
        ui.response?.let { ResponseClockCard(it, o::onResponseStep, Modifier.padding(top = 8.dp)) }

        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the banner scrolls with the timeline so it never squeezes the conversation on a landscape monitor
            ui.emergency?.takeIf { ui.response == null }?.let { e -> item(key = "emergency") { EmergencyBannerView(e, o::onEmergencyCall, o::onEmergencyDismiss) } }
            items(ui.timeline, key = { it.id }) { b -> BubbleView(b) }
            item { LiveForm(ui, tx) }
        }

        Controls(ui, o, typed, { typed = it }, showDebug)
        Box(Modifier.padding(bottom = 8.dp)) { footer() }
    }
}

@Composable
private fun BubbleView(b: Bubble) {
    when (b.role) {
        // what the reporter said: right, neutral fill. What Qalam asked: left, white with a border.
        BubbleRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(b.text, modifier = Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.secondaryContainer, CardShape).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        BubbleRole.ASSISTANT -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(b.text, Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.surface, CardShape).border(1.dp, divider, CardShape).padding(horizontal = 12.dp, vertical = 10.dp))
            if (b.speaking) Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Speaking", Modifier.padding(start = 8.dp).size(20.dp), tint = secondaryText)
        }
        BubbleRole.SYSTEM -> Text(b.text, style = MaterialTheme.typography.bodySmall, color = secondaryText)
    }
}

/** The form as it fills in: label above value, one row per field, "Required" for what is still missing. */
@Composable
private fun LiveForm(ui: ConversationUiState, tx: Taxonomy) {
    val rows = FormDisplay.rows(ui.form, tx)
    val missing = ui.validation?.missing.orEmpty()
    if (rows.isEmpty() && ui.streamingFields.isEmpty() && missing.isEmpty()) return
    Column {
        SectionHeader("Form so far")
        FlatCard {
            var first = true
            rows.forEach { r ->
                if (!first) QDivider(); first = false
                val pending = r.key in ui.streamingFields
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row {
                        Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = secondaryText)
                        if (r.status == FieldStatus.INFERRED || r.status == FieldStatus.DEFAULTED) Text("Check", style = MaterialTheme.typography.labelMedium, color = Amber)
                    }
                    Text(r.value, color = if (pending) secondaryText else MaterialTheme.colorScheme.onSurface)
                }
            }
            missing.forEach { k ->
                if (!first) QDivider(); first = false
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tx.label(k), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = secondaryText)
                    Text("Required", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Controls(ui: ConversationUiState, o: ConversationOrchestrator, typed: String, onTyped: (String) -> Unit, showDebug: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        when (ui.state) {
            ConvState.LISTENING -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MicButton(listening = true, level = ui.level, enabled = true, size = 72.dp, onClick = o::onStopTapped)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ui.listeningFor == ListenKind.NARRATION) "Listening" else "Listening for your answer", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ui.listeningFor == ListenKind.NARRATION) "${ui.elapsedMs / 1000}s / 30s" else "${ui.elapsedMs / 1000}s", style = Mono, color = secondaryText)
                }
            }
            ConvState.TRANSCRIBING -> BusyRow("Transcribing")
            ConvState.TRANSCRIPT_REVIEW -> {
                OutlinedTextField(ui.transcriptDraft, o::onTranscriptEdited, Modifier.fillMaxWidth().onFocusChanged { o.onTranscriptFocus(it.isFocused) },
                    label = { Text("Heard this. Tap to correct, or it continues by itself") })
                LinearProgressIndicator(progress = { ui.countdownMs / 2000f }, Modifier.fillMaxWidth().padding(vertical = 8.dp).height(2.dp),
                    color = MaterialTheme.colorScheme.onSurface, trackColor = divider, drawStopIndicator = {})
                PrimaryButton("Continue", o::onContinue)
                PlainButton("Record again", o::onMicTapped)
            }
            ConvState.EXTRACTING, ConvState.VALIDATING, ConvState.PATCHING -> BusyRow(if (ui.state == ConvState.EXTRACTING) "Filling the form" else "Updating the form")
            ConvState.ASKING -> Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Asking") }
            ConvState.TYPING -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, onTyped, Modifier.weight(1f), label = { Text(if (ui.question != null) "Type your answer" else "Describe the incident") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (typed.isNotBlank()) o.onTypeInstead(typed) }))
                Spacer(Modifier.width(8.dp)); SecondaryButton("Send", { o.onTypeInstead(typed) }, enabled = typed.isNotBlank())
            }
            ConvState.ERROR_ENGINE, ConvState.ERROR_PARSE, ConvState.ERROR_AUDIO -> FlatCard {
                Column(Modifier.padding(16.dp)) {
                    Text(ui.error ?: "Something went wrong.", color = MaterialTheme.colorScheme.error)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (ui.retryable) SecondaryButton("Retry", o::onRetry)
                        if (ui.state == ConvState.ERROR_AUDIO) {
                            SecondaryButton("Try mic again", o::onMicTapped)
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            SecondaryButton("Open settings", {
                                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            })
                        }
                        SecondaryButton("Fill manually", o::onManualFallback)
                    }
                }
            }
            else -> {}
        }
        if (showDebug) {
            Text("asr ${ui.debug.asrMs} ms · llm ${ui.debug.llmMs} ms · ${ui.debug.tokensOut} tok · ${"%.1f".format(ui.debug.decodeTps)} tok/s · ${ui.debug.lastTask} · round ${ui.round}", style = Mono, color = secondaryText)
            if (ui.debug.lastToolJson.isNotBlank()) Text(ui.debug.lastToolJson.take(400), style = Mono, color = secondaryText, maxLines = 6)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.state != ConvState.TYPING) PlainButton("Type instead", o::onTypeInsteadRequested)
            PlainButton("Review now", o::onReviewNow)
            PlainButton("Cancel", o::onCancel)
        }
    }
}
