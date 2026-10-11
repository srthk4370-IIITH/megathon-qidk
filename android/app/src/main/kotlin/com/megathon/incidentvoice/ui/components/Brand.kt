package com.megathon.incidentvoice.ui.components

import android.net.TrafficStats
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.AppContainer
import com.megathon.incidentvoice.R
import com.megathon.incidentvoice.domain.engine.EngineStatus
import com.megathon.incidentvoice.ui.theme.FilledGreen
import com.megathon.incidentvoice.ui.theme.Mono
import com.megathon.incidentvoice.ui.theme.reduceMotion
import com.megathon.incidentvoice.ui.theme.secondaryText
import kotlinx.coroutines.delay

/** Top bar: the mark and the name on the left; a plain status label and the settings icon on the right. */
@Composable
fun QalamTopBar(c: AppContainer, onSettings: () -> Unit) {
    val status by c.engine.status.collectAsStateWithLifecycle()
    val ready = status is EngineStatus.Ready
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(if (isSystemInDarkTheme()) R.drawable.ic_qalam_mark_white else R.drawable.ic_qalam_mark), null, Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Qalam", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(if (ready) FilledGreen else secondaryText, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("On-device", style = MaterialTheme.typography.labelMedium, color = secondaryText)
            IconButton(onSettings, Modifier.size(48.dp)) { Icon(Icons.Outlined.Settings, "Settings") }
        }
        QDivider()
    }
}

/** Splash: the coral icon centred on the background colour with the name under it; fades out once. */
@Composable
fun SplashOverlay() {
    var shown by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { if (shown) { delay(700); shown = false } }
    AnimatedVisibility(shown, exit = fadeOut(tween(if (reduceMotion()) 0 else 200))) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            // same size and position as the system splash icon, so the hand-over does not jump
            Image(painterResource(R.drawable.ic_qalam_icon), null, Modifier.size(120.dp))
            Text("Qalam", Modifier.padding(top = 200.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private var lastTokPerSec by mutableDoubleStateOf(0.0)

/**
 * Proof line: where the language model ran, how fast, and how many bytes this app has sent over the network
 * (from the system's per-app counter; the app has no INTERNET permission, so it stays at 0).
 */
@Composable
fun ProofLine(c: AppContainer, modifier: Modifier = Modifier) {
    val ui by c.orchestrator.ui.collectAsStateWithLifecycle()
    val kind by c.engineKind.collectAsStateWithLifecycle()
    val llm by c.llmBackend.collectAsStateWithLifecycle()
    LaunchedEffect(ui.debug.decodeTps) { if (ui.debug.decodeTps > 0) lastTokPerSec = ui.debug.decodeTps }
    var sent by remember { mutableLongStateOf(TrafficStats.getUidTxBytes(android.os.Process.myUid())) }
    LaunchedEffect(Unit) { while (true) { sent = TrafficStats.getUidTxBytes(android.os.Process.myUid()); delay(2000) } }
    val where = when {
        kind == "JNI" && llm == "NPU" -> "Hexagon NPU · " + (if (lastTokPerSec > 0) "%.1f".format(lastTokPerSec) else "–") + " tok/s"
        kind == "JNI" -> "CPU · Whisper + rule engine"
        else -> "CPU · mock engine"
    }
    val net = if (sent == TrafficStats.UNSUPPORTED.toLong()) "no network access" else "$sent B sent"
    Text("$where · $net", modifier, style = Mono.copy(fontSize = 12.sp), color = secondaryText, maxLines = 1)
}
