package com.megathon.incidentvoice.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.core.EvidenceSpan
import com.megathon.incidentvoice.core.IncidentForm
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.secondaryText

/** "Show your evidence": which field is selected on this screen, and how a field row selects itself. */
class EvidenceUi(val form: IncidentForm, val selected: String?, val toggle: (String) -> Unit)
val LocalEvidence = compositionLocalOf<EvidenceUi?> { null }

/**
 * What the reporter said, pinned above the form. When a field is selected the words its value came from are shown
 * on a coral tint in bold, and the text scrolls to the first of them.
 */
@Composable
fun TranscriptCard(turns: List<String>, spans: List<EvidenceSpan>, selectedLabel: String?, modifier: Modifier = Modifier) {
    val coral = MaterialTheme.colorScheme.primary
    val offsets = remember(turns) { turns.runningFold(0) { acc, t -> acc + t.length + 1 } }
    val text = remember(turns, spans, coral) {
        buildAnnotatedString {
            append(turns.joinToString("\n"))
            for (s in spans) {
                val t = turns.getOrNull(s.turn) ?: continue
                val from = offsets[s.turn] + s.start.coerceIn(0, t.length); val to = offsets[s.turn] + s.end.coerceIn(0, t.length)
                if (to > from) addStyle(SpanStyle(background = coral.copy(alpha = 0.2f), fontWeight = FontWeight.Bold), from, to)
            }
        }
    }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val first = spans.minWithOrNull(compareBy({ it.turn }, { it.start }))?.let { s -> turns.getOrNull(s.turn)?.let { offsets[s.turn] + s.start.coerceIn(0, it.length) } }
    LaunchedEffect(first, layout) {
        val l = layout ?: return@LaunchedEffect
        if (first != null && first <= l.layoutInput.text.length) scroll.animateScrollTo(l.getLineTop(l.getLineForOffset(first)).toInt())
    }
    FlatCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transcript", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = secondaryText)
                Text(
                    when { selectedLabel == null -> "Tap a field to see its evidence"; spans.isEmpty() -> "$selectedLabel: not in the transcript"; else -> "Evidence for $selectedLabel" },
                    style = MaterialTheme.typography.labelMedium, color = if (selectedLabel != null && spans.isEmpty()) Amber else secondaryText,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.heightIn(max = 96.dp).verticalScroll(scroll)) { Text(text, onTextLayout = { layout = it }) }
        }
    }
}

/** A value nothing in the transcript supports. */
@Composable
fun NeedsReviewChip(modifier: Modifier = Modifier) =
    Text("Needs review", modifier.border(1.dp, Amber, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Amber)
