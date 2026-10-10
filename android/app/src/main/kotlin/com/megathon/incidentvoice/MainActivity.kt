package com.megathon.incidentvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.megathon.incidentvoice.data.DemoSeeder
import com.megathon.incidentvoice.ui.dashboard.DashboardScreen
import com.megathon.incidentvoice.ui.dashboard.DashboardViewModel
import com.megathon.incidentvoice.ui.detail.DetailScreen
import com.megathon.incidentvoice.ui.ReportFlow
import com.megathon.incidentvoice.ui.settings.SettingsScreen
import com.megathon.incidentvoice.ui.theme.IncidentVoiceTheme
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.megathon.incidentvoice.orchestrator.ConvState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // Android 15 draws edge-to-edge anyway; this also gives readable status-bar icons
        super.onCreate(savedInstanceState)
        val container = (application as IncidentVoiceApp).container
        // keep the screen on while a report is in progress (PRD §20.2)
        lifecycleScope.launch {
            container.orchestrator.ui.map { it.state != ConvState.IDLE && it.state != ConvState.SUBMITTED }.distinctUntilChanged().collect { busy ->
                if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent { IncidentVoiceTheme { AppRoot(container) } }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun AppRoot(c: AppContainer) {
    val nav = rememberNavController()
    val tabs = listOf(Tab("home", "Report", Icons.Default.Mic), Tab("dashboard", "Dashboard", Icons.Default.Dashboard), Tab("ask", "Ask", Icons.Default.QuestionAnswer), Tab("settings", "Settings", Icons.Default.Settings))
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val scope = rememberCoroutineScope()
    val dashVm: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(c.repository) } })

    val conv by c.orchestrator.ui.collectAsState()
    val inConversation = route == "home" && conv.state != com.megathon.incidentvoice.orchestrator.ConvState.IDLE
    Scaffold(bottomBar = {
        if (route in tabs.map { it.route } && !inConversation) NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(selected = route == t.route, onClick = {
                    nav.navigate(t.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
            }
        }
    }) { pad ->
        // consumeWindowInsets + imePadding: keep text fields above the on-screen keyboard (edge-to-edge on Android 15)
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        NavHost(nav, startDestination = "home", modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            composable("home") { ReportFlow(c, onOpenDetail = { nav.navigate("detail/$it") }, onGoDashboard = { nav.navigate("dashboard") { launchSingleTop = true } }) }
            composable("dashboard") {
                DashboardScreen(dashVm, c.config.taxonomy, onOpen = { nav.navigate("detail/$it") },
                    onSeed = { scope.launch { DemoSeeder(c.config, c.repository).seed(c.settings.flow.first().reporter) } })
            }
            composable("detail/{id}") { be ->
                DetailScreen(be.arguments!!.getString("id")!!, c.repository, c.config.taxonomy, c.config.sop.disclaimer, similar = { c.askController.similar(it) }, onOpen = { nav.navigate("detail/$it") },
                    onBack = { nav.popBackStack() }, onDeleted = { nav.popBackStack() },
                    onEdit = { id -> c.orchestrator.openForEdit(id); nav.navigate("home") { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true } })
            }
            composable("ask") {
                val s by c.settingsSnapshot.collectAsState()
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { c.askController.askByVoice() }
                com.megathon.incidentvoice.ui.ask.AskScreen(c.askController, c.config.taxonomy, s.showDebug, onOpen = { nav.navigate("detail/$it") }, onMic = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (s.useMic && !granted) perm.launch(android.Manifest.permission.RECORD_AUDIO) else c.askController.askByVoice()
                })
            }
            composable("settings") { SettingsScreen(c) }
        }
        }
    }
}
