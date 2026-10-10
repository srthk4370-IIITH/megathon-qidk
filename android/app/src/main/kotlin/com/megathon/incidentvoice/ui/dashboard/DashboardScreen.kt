package com.megathon.incidentvoice.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.core.Taxonomy
import com.megathon.incidentvoice.data.IncidentListItem
import com.megathon.incidentvoice.ui.components.SeverityChip
import com.megathon.incidentvoice.ui.components.StatusChip
import com.megathon.incidentvoice.ui.relativeTime
import com.megathon.incidentvoice.ui.theme.SevCritical

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, tx: Taxonomy, onOpen: (String) -> Unit, onSeed: (() -> Unit)?) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var typeMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ui.query, onValueChange = vm::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search incidents…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (ui.query.isNotEmpty()) IconButton({ vm.setQuery("") }) { Icon(Icons.Default.Clear, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
        )
        Spacer(Modifier.height(6.dp))
        // filters + count row scroll with the list (PRD §23.1 "collapsible on scroll"); only the search box stays pinned
        val header: @Composable () -> Unit = {
            Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box {
                    FilterChip(selected = ui.types.isNotEmpty(), onClick = { typeMenu = true },
                        label = { Text(if (ui.types.isEmpty()) "Type ▾" else "Type (${ui.types.size}) ▾") })
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        tx.categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(c.code in ui.types, null); Spacer(Modifier.width(8.dp)); Text(c.label) } },
                                onClick = { vm.toggleType(c.code) },
                            )
                        }
                    }
                }
            }
            items(listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")) { s ->
                FilterChip(selected = s in ui.severities, onClick = { vm.toggleSeverity(s) }, label = { Text(s.lowercase().replaceFirstChar { it.uppercase() }) })
            }
            items(DatePreset.entries.toList()) { p ->
                FilterChip(selected = ui.preset == p, onClick = { vm.setPreset(p) }, label = { Text(p.label) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Total ${counts.total} · Critical ${counts.critical} · Last 24 h ${counts.last24h}",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
            }
        }
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Column(Modifier.fillMaxSize(), Arrangement.Top, Alignment.CenterHorizontally) {
                header()
                Spacer(Modifier.height(48.dp))
                Text(if (ui.query.isBlank() && ui.types.isEmpty() && ui.severities.isEmpty() && counts.total == 0) "No incidents reported yet." else "No incidents match.",
                    style = MaterialTheme.typography.bodyLarge)
                if (onSeed != null && counts.total == 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onSeed) { Text("Load demo incidents") }
                }
                if (ui.types.isNotEmpty() || ui.severities.isNotEmpty() || ui.preset != DatePreset.ALL) {
                    TextButton(onClick = vm::clearFilters) { Text("Clear filters") }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") { header() }
                items(list, key = { it.id }) { IncidentCard(it, tx, onOpen) }
            }
        }
    }
}

@Composable
private fun IncidentCard(i: IncidentListItem, tx: Taxonomy, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(i.id) }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityChip(i.severity)
                Text(tx.category(i.type ?: "")?.label ?: (i.type ?: "Incident"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (i.emergency) Text("EMERGENCY", color = SevCritical, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(relativeTime(i.createdAt), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(i.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            i.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(i.status)
                Text(listOfNotNull(i.refNo, i.location).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
