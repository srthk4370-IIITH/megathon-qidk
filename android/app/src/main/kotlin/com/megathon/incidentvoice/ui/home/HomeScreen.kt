package com.megathon.incidentvoice.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.AppContainer

@Composable
fun HomeScreen(c: AppContainer) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("IncidentVoice", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Conversation flow arrives in Stage C.", style = MaterialTheme.typography.bodyMedium)
    }
}
