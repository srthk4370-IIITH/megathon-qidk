package com.megathon.incidentvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.ui.theme.severityColor

@Composable
fun SeverityChip(severity: String?, modifier: Modifier = Modifier) {
    val c = severityColor(severity)
    Text(
        severity ?: "—", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.background(c, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    Text(
        status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
