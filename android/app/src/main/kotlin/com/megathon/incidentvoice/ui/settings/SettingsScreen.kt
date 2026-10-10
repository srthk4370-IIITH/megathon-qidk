package com.megathon.incidentvoice.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.AppContainer
import com.megathon.incidentvoice.config.ModelPaths
import com.megathon.incidentvoice.data.AppSettings
import com.megathon.incidentvoice.data.DemoSeeder
import com.megathon.incidentvoice.engine.EngineStatus
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer) {
    val s by c.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val status by c.engine.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }
    var seedMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Reporter profile", style = MaterialTheme.typography.titleSmall)
        var name by remember(s.reporterName) { mutableStateOf(s.reporterName) }
        var emp by remember(s.reporterEmpId) { mutableStateOf(s.reporterEmpId) }
        OutlinedTextField(name, { name = it; upd { x -> x.copy(reporterName = it) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(emp, { emp = it; upd { x -> x.copy(reporterEmpId = it) } }, label = { Text("Employee ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        var ert by remember(s.ertNumber) { mutableStateOf(s.ertNumber) }
        OutlinedTextField(ert, { ert = it; upd { x -> x.copy(ertNumber = it.trim().ifEmpty { "112" }) } }, label = { Text("Emergency team (ERT) number") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Voice", style = MaterialTheme.typography.titleSmall)
        SwitchRow("Use microphone", "Off = simulated audio (development)", s.useMic) { v -> upd { it.copy(useMic = v) } }
        SwitchRow("Speak questions aloud", null, s.speakQuestions) { v -> upd { it.copy(speakQuestions = v) } }
        Text("End-of-speech silence: ${s.vadEndSilenceMs} ms")
        Slider(s.vadEndSilenceMs.toFloat(), { v -> upd { it.copy(vadEndSilenceMs = (v / 50).toInt() * 50) } }, valueRange = 800f..1500f)

        Text("Debug", style = MaterialTheme.typography.titleSmall)
        SwitchRow("Show debug overlay", "Per-stage timings and tok/s in the conversation screen", s.showDebug) { v -> upd { it.copy(showDebug = v) } }

        Text("Engine", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                when (val st = status) {
                    is EngineStatus.Ready -> {
                        Text("Ready — backend ${st.info.backend.ifBlank { "?" }}")
                        Text("ASR: ${st.info.asrModel} · LLM: ${st.info.llmModel} (${st.info.llmVariant})", style = MaterialTheme.typography.bodySmall)
                        if (st.info.qairt.isNotBlank()) Text("QAIRT ${st.info.qairt} · ${st.info.soc} ${st.info.htpArch}", style = MaterialTheme.typography.bodySmall)
                        if (st.info.loadMs > 0) Text("Load time ${st.info.loadMs} ms", style = MaterialTheme.typography.bodySmall)
                    }
                    is EngineStatus.Loading -> Text("Loading… ${(st.progress * 100).toInt()} % ${st.stage}")
                    is EngineStatus.Error -> Text("Error ${st.error.code}: ${st.error.message}", color = MaterialTheme.colorScheme.error)
                    EngineStatus.Idle -> Text("Not started")
                }
                Spacer(Modifier.height(6.dp))
                val dir = File(ModelPaths.BASE)
                Text("Model folder: ${ModelPaths.BASE}", style = MaterialTheme.typography.labelSmall)
                Text(if (dir.isDirectory) "Found (${dir.list()?.size ?: 0} entries)" else "Not present (Mock engine in use)", style = MaterialTheme.typography.labelSmall)
                OutlinedButton({ scope.launch { runCatching { c.engine.warmup() } } }, Modifier.padding(top = 6.dp)) { Text("Re-run warm-up") }
            }
        }

        Text("Site registry", style = MaterialTheme.typography.titleSmall)
        Text("${c.config.registry.site}: ${c.config.registry.locations.size} locations (read-only)", style = MaterialTheme.typography.bodySmall)

        Text("Demo data", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ scope.launch { val n = DemoSeeder(c.config, c.repository).seed(s.reporter); seedMsg = "Added $n demo incidents" } }) { Text("Load demo incidents") }
            seedMsg?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        Text("IncidentVoice · config v${c.config.taxonomy.version} · fully offline", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked, onChange)
    }
}
