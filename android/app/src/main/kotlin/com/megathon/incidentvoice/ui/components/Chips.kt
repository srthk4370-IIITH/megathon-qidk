package com.megathon.incidentvoice.ui.components

import androidx.compose.foundation.background
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
