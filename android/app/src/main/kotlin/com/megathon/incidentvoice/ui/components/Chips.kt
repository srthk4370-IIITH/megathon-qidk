package com.megathon.incidentvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.megathon.incidentvoice.core.Hotspots
import com.megathon.incidentvoice.core.ReportTags
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.ui.theme.secondaryText
import com.megathon.incidentvoice.ui.theme.severityColor

@Composable
fun SeverityDot(severity: String?, modifier: Modifier = Modifier) = Box(modifier.size(10.dp).background(severityColor(severity), CircleShape))

/** Severity as a small dot and a word ("● Critical"); the colour is only in the dot, so the text always has full contrast. */
@Composable
fun SeverityLabel(severity: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SeverityDot(severity)
        Text(severity?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Not set", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun StatusText(status: String, modifier: Modifier = Modifier) =
    Text(status.lowercase().replaceFirstChar { it.uppercase() }, modifier, style = MaterialTheme.typography.bodySmall, color = secondaryText)

/** A small flat outlined label. */
@Composable
fun Pill(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) =
    Text(text, modifier.border(1.dp, color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)

/** "Repeat incident · 3rd water leak here in 7 days" in coral, "Hotspot" in the text colour. Nothing when the report has neither. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportTagChips(tags: ReportTags, typeLabel: (String) -> String, modifier: Modifier = Modifier) {
    if (tags.repeat == null && !tags.hotspot) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.repeat?.let { Pill("Repeat incident · " + Hotspots.chip(it, typeLabel(it.type)), MaterialTheme.colorScheme.primary) }
        if (tags.hotspot) Pill("Hotspot", MaterialTheme.colorScheme.onSurface)
    }
}
