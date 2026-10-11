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
import com.megathon.incidentvoice.data.AppSettings
import com.megathon.incidentvoice.data.DemoSeeder
import com.megathon.incidentvoice.domain.engine.EngineStatus
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.secondaryText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer) {
    val s by c.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val status by c.engine.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }
    var seedMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        ScreenTitle("Settings")

        SectionHeader("Reporter profile")
        var name by remember(s.reporterName) { mutableStateOf(s.reporterName) }
        var emp by remember(s.reporterEmpId) { mutableStateOf(s.reporterEmpId) }
        OutlinedTextField(name, { name = it; upd { x -> x.copy(reporterName = it) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(emp, { emp = it; upd { x -> x.copy(reporterEmpId = it) } }, label = { Text("Employee ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        var ert by remember(s.ertNumber) { mutableStateOf(s.ertNumber) }
        OutlinedTextField(ert, { ert = it; upd { x -> x.copy(ertNumber = it.trim().ifEmpty { "112" }) } }, label = { Text("Emergency team (ERT) number") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        SectionHeader("Voice")
        FlatCard {
            SwitchRow("Use microphone", "Off uses simulated audio (development)", s.useMic) { v -> upd { it.copy(useMic = v) } }
            QDivider()
            SwitchRow("Speak questions aloud", null, s.speakQuestions) { v -> upd { it.copy(speakQuestions = v) } }
            QDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row { Text("End-of-speech silence", Modifier.weight(1f)); Text("${s.vadEndSilenceMs} ms", style = Mono, color = secondaryText) }
                Slider(s.vadEndSilenceMs.toFloat(), { v -> upd { it.copy(vadEndSilenceMs = (v / 50).toInt() * 50) } }, valueRange = 800f..1500f,
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.onSurface, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant))
            }
        }

        if (c.config.app.features.wakeWord) {
            val w by c.wake.ui.collectAsStateWithLifecycle()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SectionHeader("Hands-free")
            FlatCard {
                SwitchRow("Say '${c.config.app.wakeDisplay}' to report", "Listens on this device for the wake word and starts a report. Nothing leaves the device.", s.wakeEnabled) { v -> upd { it.copy(wakeEnabled = v) } }
                QDivider()
                InfoRow("Status", when { !s.wakeEnabled -> "Off"; w.error != null -> "Error: ${w.error}"; w.listening -> "Listening"; w.loaded -> "Paused while the microphone is in use"; else -> "Loading the model" })
                QDivider()
                InfoRow("Last heard", w.lastHeard.ifBlank { "–" } + (if (w.lastMatch.isNotBlank()) "  (last match: ${w.lastMatch})" else ""), mono = true)
                QDivider()
                InfoRow("Detections", "${w.detections}" + (if (w.strategy.isNotBlank()) " · ${w.strategy}" else ""), mono = true)
            }
            if (!android.provider.Settings.canDrawOverlays(ctx)) SecondaryButton("Allow opening from the home screen", {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.padding(top = 8.dp))
        }

        SectionHeader("Engine")
        FlatCard {
            val kind by c.engineKind.collectAsStateWithLifecycle()
            val llm by c.llmBackend.collectAsStateWithLifecycle()
            InfoRow("Active", when {
                kind != "JNI" -> "Mock (scripted demo responses)"
                llm == "NPU" -> "On device: Whisper speech recognition and ${c.modelFamily.collectAsStateWithLifecycle().value.display} on the Hexagon NPU"
                llm == "FALLBACK" -> "On device: Whisper speech recognition. Stand-in language model until the Llama bundle is on the device (then choose Auto to reload)"
                else -> "On device (not ready, see status)"
            })
            QDivider()
            when (val st = status) {
                is EngineStatus.Ready -> {
                    InfoRow("Status", "Ready · backend ${st.info.backend.ifBlank { "?" }}")
                    QDivider()
                    InfoRow("Models", "ASR ${st.info.asrModel} · LLM ${st.info.llmModel} (${st.info.llmVariant})" + (if (st.info.qairt.isNotBlank()) "\nQAIRT ${st.info.qairt} · ${st.info.soc} ${st.info.htpArch}" else "") +
                        (if (st.info.loadMs > 0) "\nload ${st.info.loadMs} ms" else ""), mono = true)
                }
                is EngineStatus.Loading -> InfoRow("Status", "Loading, ${(st.progress * 100).toInt()} % ${st.stage}")
                is EngineStatus.Error -> InfoRow("Status", "Error ${st.error.code}: ${st.error.message}", error = true)
                EngineStatus.Idle -> InfoRow("Status", "Not started")
            }
            QDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Mode", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AUTO" to "Auto", "JNI" to "On-device", "MOCK" to "Mock").forEach { (mode, label) ->
                        FilterChip(selected = s.engineMode == mode, onClick = { upd { it.copy(engineMode = mode) }; c.selectEngine(mode) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            QDivider()
            InfoRow("Model folder", c.modelsDir.absolutePath + "\nWhisper: ${if (c.whisperPresent()) "found" else "missing"} · Llama: ${if (c.llamaPresent()) "found" else "missing"}" +
                if (!c.whisperPresent()) " (Auto uses the Mock engine)" else "", mono = true)
        }
        SecondaryButton("Run warm-up again", { scope.launch { runCatching { c.engine.warmup() } } }, Modifier.padding(top = 8.dp))

        SectionHeader("Debug")
        FlatCard { SwitchRow("Show debug overlay", "Per-stage timings and tok/s on the conversation screen", s.showDebug) { v -> upd { it.copy(showDebug = v) } } }

        SectionHeader("Site registry")
        Text("${c.config.registry.site}: ${c.config.registry.locations.size} locations (read-only)")

        SectionHeader("Demo data")
        SecondaryButton("Load demo reports", { scope.launch { val n = DemoSeeder(c.config, c.repository).seed(s.reporter); seedMsg = "Added $n demo reports" } })
        seedMsg?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = secondaryText) }
        // Debug builds only: the repeat / hotspot demo set. Every row is marked as demo data; "Clear" removes only those.
        val debuggable = (androidx.compose.ui.platform.LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Seed demo data", { scope.launch { val n = DemoSeeder(c.config, c.repository).seedHotspots(s.reporter); seedMsg = "Added $n demo reports (2 leaks at Conference Room 2, a Workshop hotspot)" } })
                SecondaryButton("Clear demo data", { scope.launch { val n = c.repository.clearDemo(com.megathon.incidentvoice.data.Actor.System); seedMsg = "Removed $n demo reports; your own reports are kept" } })
            }
        }
        var confirmReset by remember { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Reset for demo (delete all, load demo reports)", { confirmReset = true }, danger = true)
        if (confirmReset) AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all reports?") },
            text = { Text("Every report, draft and recording on this device is deleted, then the 10 demo reports are loaded. Settings and models are kept.") },
            confirmButton = { TextButton({
                confirmReset = false
                scope.launch {
                    c.orchestrator.onCancel(); c.askController.clear()
                    val del = c.repository.deleteAll(com.megathon.incidentvoice.data.Actor.System)
                    val n = DemoSeeder(c.config, c.repository).seed(s.reporter)
                    seedMsg = "Deleted $del, loaded $n demo reports"
                }
            }) { Text("Reset") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
        )
        Spacer(Modifier.height(24.dp))
        Text("Qalam · config v${c.config.taxonomy.version} · no network permission", style = Mono.copy(fontSize = 12.sp), color = secondaryText)
        Text("Fonts: Inter and JetBrains Mono, SIL Open Font License.", style = MaterialTheme.typography.bodySmall, color = secondaryText)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryText) } }
        // neutral switch: the accent is kept for the report button
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface, checkedThumbColor = MaterialTheme.colorScheme.surface, checkedBorderColor = MaterialTheme.colorScheme.onSurface))
    }
}

/** Label above value, as everywhere else. */
@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, error: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = secondaryText)
        Text(value, style = if (mono) Mono else MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
