package com.megathon.incidentvoice.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.AppContainer

@Composable
fun SettingsScreen(c: AppContainer) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
    }
}
