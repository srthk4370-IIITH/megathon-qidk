package com.megathon.incidentvoice.ui

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.AppContainer
import com.megathon.incidentvoice.orchestrator.ConvState
import com.megathon.incidentvoice.orchestrator.UiEvent
import com.megathon.incidentvoice.ui.components.SeverityChip
import com.megathon.incidentvoice.ui.conversation.ConversationScreen
import com.megathon.incidentvoice.ui.home.HomeScreen
import com.megathon.incidentvoice.ui.review.LocalOrchestrator
import com.megathon.incidentvoice.ui.review.ReviewScreen

/** The "Report" tab: routes the orchestrator state to a screen (PRD §20.3). */
@Composable
fun ReportFlow(c: AppContainer, onOpenDetail: (String) -> Unit, onGoDashboard: () -> Unit) {
    val o = c.orchestrator
    val ui by o.ui.collectAsStateWithLifecycle()
    val settings by c.settingsSnapshot.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(o) {
        o.events.collect { ev ->
            if (ev is UiEvent.Dial) ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ev.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // RECORD_AUDIO is requested on the first mic tap (PRD §16.2); denial → ERROR_AUDIO card via AudioCapture
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { o.onMicTapped() }
    val onMic: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (settings.useMic && !granted) permLauncher.launch(Manifest.permission.RECORD_AUDIO) else o.onMicTapped()
    }

    CompositionLocalProvider(LocalOrchestrator provides o) {
        when (ui.state) {
            ConvState.IDLE, ConvState.ERROR_AUDIO ->
                if (ui.state == ConvState.ERROR_AUDIO) ConversationScreen(ui, c.config.taxonomy, o, settings.showDebug)
                else HomeScreen(c, onMic = onMic, onTypeInstead = o::onTypeInsteadRequested, notice = ui.notice, onOpen = onOpenDetail)
            ConvState.MANUAL_FILL, ConvState.RECOMMENDING, ConvState.REVIEW -> ReviewScreen(ui, c.config, o)
            ConvState.SUBMITTED -> SubmittedScreen(ui.submittedRef.orEmpty(), ui.form.str("severity"), ui.otherIncidents, o::onReportAnother, onGoDashboard)
            else -> ConversationScreen(ui, c.config.taxonomy, o, settings.showDebug)
        }
    }
}

@Composable
private fun SubmittedScreen(ref: String, severity: String?, others: List<String>, onAnother: (String?) -> Unit, onDashboard: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("Report saved", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(ref, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp)); SeverityChip(severity)
        Spacer(Modifier.height(24.dp))
        others.firstOrNull()?.let { other ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("You also mentioned: $other", textAlign = TextAlign.Start)
                    Button({ onAnother(other) }, Modifier.padding(top = 8.dp)) { Text("Report it now") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Button({ onAnother(null) }, Modifier.fillMaxWidth()) { Text("Report another incident") }
        OutlinedButton(onDashboard, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("View dashboard") }
    }
}
