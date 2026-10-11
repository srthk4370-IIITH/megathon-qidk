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
import kotlinx.coroutines.launch
import com.megathon.incidentvoice.orchestrator.ConvState
import com.megathon.incidentvoice.orchestrator.UiEvent
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.secondaryText
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.ui.conversation.ConversationScreen
import com.megathon.incidentvoice.ui.home.HomeScreen
import com.megathon.incidentvoice.ui.review.LocalOrchestrator
import com.megathon.incidentvoice.ui.review.LocalReporter
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
            if (ev is UiEvent.Dial) {
                // The QIDK has no SIM and may have no dialer app: never crash on the emergency button.
                runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ev.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { android.widget.Toast.makeText(ctx, "No dialer on this device — call ${ev.number} from a phone", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    // RECORD_AUDIO is requested on the first mic tap (PRD §16.2); denial → ERROR_AUDIO card via AudioCapture
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { o.onMicTapped() }
    val onMic: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (settings.useMic && !granted) permLauncher.launch(Manifest.permission.RECORD_AUDIO) else o.onMicTapped()
    }

    // Extra 2: tile / shortcut / widget / wake word → start listening straight away (asks for the mic permission if needed)
    val quick by c.quickReport.collectAsStateWithLifecycle()
    var quickHandled by remember { mutableIntStateOf(c.quickReport.value.let { if (ui.state == ConvState.IDLE) 0 else it }) }
    LaunchedEffect(quick) {
        if (quick > quickHandled) {
            quickHandled = quick
            if (ui.state == ConvState.IDLE || ui.state == ConvState.SUBMITTED) { if (ui.state == ConvState.SUBMITTED) o.startNew(); onMic() }
        }
    }

    // First launch: ask for the reporter profile once (PRD §20.8, D-25)
    val stored by c.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var profileDismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (stored != null && stored!!.reporterName.isBlank() && !stored!!.profileAsked && !profileDismissed && ui.state == ConvState.IDLE && quick == 0) {
        var name by remember { mutableStateOf("") }
        var emp by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { profileDismissed = true; scope.launch { c.settings.update { it.copy(profileAsked = true) } } },
            title = { Text("Who is reporting?") },
            text = {
                Column {
                    Text("Saved on this device and attached to every report.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(emp, { emp = it }, label = { Text("Employee ID") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ scope.launch { c.settings.update { it.copy(reporterName = name.trim(), reporterEmpId = emp.trim(), profileAsked = true) } }; profileDismissed = true }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ profileDismissed = true; scope.launch { c.settings.update { it.copy(profileAsked = true) } } }) { Text("Later") } },
        )
    }

    CompositionLocalProvider(LocalOrchestrator provides o, LocalReporter provides listOf(settings.reporterName, settings.reporterEmpId).filter { it.isNotBlank() }.joinToString(" · ")) {
        when (ui.state) {
            ConvState.IDLE, ConvState.ERROR_AUDIO ->
                if (ui.state == ConvState.ERROR_AUDIO) ConversationScreen(ui, c.config.taxonomy, o, settings.showDebug) { com.megathon.incidentvoice.ui.components.ProofLine(c) }
                else HomeScreen(c, onMic = onMic, onTypeInstead = o::onTypeInsteadRequested, notice = ui.notice, onOpen = onOpenDetail)
            ConvState.MANUAL_FILL, ConvState.RECOMMENDING, ConvState.REVIEW -> ReviewScreen(ui, c.config, o)
            ConvState.SUBMITTED -> {
                SubmittedScreen(ui.submittedRef.orEmpty(), ui.form.str("severity"), ui.otherIncidents, ui.editingExisting, o::onReportAnother, onGoDashboard, ui.linkedTo)
                // Extra 3: the app proposes, the reporter decides; nothing is ever linked automatically
                ui.duplicate?.let { d ->
                    AlertDialog(
                        onDismissRequest = o::onKeepSeparate,
                        title = { Text("Same incident?") }, text = { Text(d.question) },
                        confirmButton = { SecondaryButton("Link", o::onLinkWitness) },
                        dismissButton = { PlainButton("Separate", o::onKeepSeparate) },
                    )
                }
            }
            else -> ConversationScreen(ui, c.config.taxonomy, o, settings.showDebug) { com.megathon.incidentvoice.ui.components.ProofLine(c) }
        }
    }
}

@Composable
private fun SubmittedScreen(ref: String, severity: String?, others: List<String>, updated: Boolean, onAnother: (String?) -> Unit, onDashboard: () -> Unit, linkedTo: String? = null) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle(if (updated) "Report updated" else "Report saved")
        Text("Saved on device", color = secondaryText)
        Spacer(Modifier.height(16.dp))
        FlatCard {
            Column(Modifier.padding(16.dp)) {
                Text("Reference", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                Text(ref, style = Mono.copy(fontSize = 20.sp, lineHeight = 26.sp))
                Spacer(Modifier.height(8.dp))
                SeverityLabel(severity)
                linkedTo?.let { Spacer(Modifier.height(8.dp)); Text("Added as a witness account to $it", style = MaterialTheme.typography.bodySmall, color = secondaryText) }
            }
        }
        others.firstOrNull()?.let { other ->
            SectionHeader("You also mentioned")
            FlatCard {
                Column(Modifier.padding(16.dp)) {
                    Text(other)
                    SecondaryButton("Report it now", { onAnother(other) }, Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Report another incident", { onAnother(null) })
        PlainButton("View dashboard", onDashboard)
    }
}
