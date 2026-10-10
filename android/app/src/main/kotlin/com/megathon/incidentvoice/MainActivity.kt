package com.megathon.incidentvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Mic
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IncidentVoiceApp).container
        setContent { IncidentVoiceTheme { AppRoot(container) } }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun AppRoot(c: AppContainer) {
    val nav = rememberNavController()
    val tabs = listOf(Tab("home", "Report", Icons.Default.Mic), Tab("dashboard", "Dashboard", Icons.Default.Dashboard), Tab("settings", "Settings", Icons.Default.Settings))
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
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { ReportFlow(c, onOpenDetail = { nav.navigate("detail/$it") }, onGoDashboard = { nav.navigate("dashboard") { launchSingleTop = true } }) }
            composable("dashboard") {
                DashboardScreen(dashVm, c.config.taxonomy, onOpen = { nav.navigate("detail/$it") },
                    onSeed = { scope.launch { DemoSeeder(c.config, c.repository).seed(c.settings.flow.first().reporter) } })
            }
            composable("detail/{id}") { be ->
                DetailScreen(be.arguments!!.getString("id")!!, c.repository, c.config.taxonomy, c.config.sop.disclaimer,
                    onBack = { nav.popBackStack() }, onDeleted = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(c) }
        }
    }
}
