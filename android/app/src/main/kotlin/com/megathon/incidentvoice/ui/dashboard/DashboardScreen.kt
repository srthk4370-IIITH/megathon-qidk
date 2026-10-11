package com.megathon.incidentvoice.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.background
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.secondaryText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megathon.incidentvoice.core.HotspotRules
import com.megathon.incidentvoice.core.HotspotStat
import com.megathon.incidentvoice.core.Hotspots
import com.megathon.incidentvoice.core.Taxonomy
import com.megathon.incidentvoice.core.Trend
import com.megathon.incidentvoice.data.IncidentListItem
import com.megathon.incidentvoice.ui.relativeTime
import com.megathon.incidentvoice.ui.theme.SevCritical

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, tx: Taxonomy, onOpen: (String) -> Unit, onSeed: (() -> Unit)?) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val hotspots by vm.hotspots.collectAsStateWithLifecycle()
    var typeMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = ui.query, onValueChange = vm::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search reports") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (ui.query.isNotEmpty()) IconButton({ vm.setQuery("") }) { Icon(Icons.Outlined.Clear, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
        )
        Spacer(Modifier.height(8.dp))
        // filters + count row scroll with the list (PRD §23.1 "collapsible on scroll"); only the search box stays pinned
        val header: @Composable () -> Unit = {
            Column {
        if (hotspots.isNotEmpty()) {
            SectionHeader("Hotspots", Modifier.padding(horizontal = 16.dp).padding(top = 0.dp))
            FlatCard(Modifier.padding(horizontal = 16.dp)) {
                hotspots.forEachIndexed { n, h -> if (n > 0) QDivider(); HotspotRow(h, tx, selected = ui.locationKey == h.key) { vm.toggleLocation(h) } }
            }
            Spacer(Modifier.height(12.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item {
                Box {
                    FilterChip(selected = ui.types.isNotEmpty(), onClick = { typeMenu = true },
                        label = { Text(if (ui.types.isEmpty()) "Type" else "Type (${ui.types.size})") }, trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, tint = secondaryText) })
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Stat(counts.total, "Reports", Modifier.weight(1f)); Stat(counts.critical, "Critical", Modifier.weight(1f)); Stat(counts.last24h, "Last 24 h", Modifier.weight(1f))
        }
        SectionHeader("Reports", Modifier.padding(horizontal = 16.dp))
        ui.locationLabel?.let { place ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Only reports at $place", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                PlainButton("Show all", vm::clearLocation)
            }
        }
        QDivider()
            }
        }
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Column(Modifier.fillMaxSize()) {
                header()
                Column(Modifier.padding(16.dp)) {
                    Text(if (ui.query.isBlank() && ui.types.isEmpty() && ui.severities.isEmpty() && ui.locationKey == null && counts.total == 0) "No reports yet. Tap Report to create one." else "No reports match.")
                    if (onSeed != null && counts.total == 0) SecondaryButton("Load demo reports", onSeed, Modifier.padding(top = 16.dp))
                    if (ui.types.isNotEmpty() || ui.severities.isNotEmpty() || ui.preset != DatePreset.ALL || ui.locationKey != null) PlainButton("Clear filters", vm::clearFilters)
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") { header() }
                items(list, key = { it.id }) { IncidentCard(it, tx, onOpen) }
            }
        }
    }
}

@Composable
private fun IncidentCard(i: IncidentListItem, tx: Taxonomy, onOpen: (String) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        IncidentRow(
            i.severity, i.title, listOfNotNull(i.location, relativeTime(i.createdAt)).joinToString(" · "), i.refNo, { onOpen(i.id) },
            extra = {
                Text(listOfNotNull(tx.category(i.type ?: "")?.label, i.status.lowercase().replaceFirstChar { it.uppercase() }, "Emergency".takeIf { i.emergency }, "Demo data".takeIf { i.demo }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ReportTagChips(i.tags, { tx.category(it)?.label ?: it }, Modifier.padding(top = 4.dp, bottom = 2.dp))
                i.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (i.accounts > 1) Text("${i.accounts} accounts · ${i.conflicts} conflict${if (i.conflicts != 1) "s" else ""}", style = MaterialTheme.typography.labelMedium,
                    color = if (i.conflicts > 0) SevCritical else MaterialTheme.colorScheme.onSurface)
                i.parentRef?.let { Text("Witness account of $it", style = MaterialTheme.typography.bodySmall, color = secondaryText) }
            },
        )
        QDivider()
    }
}

/** One hotspot: the place, its reports in 30 days and most common type; on the right the last 7 days and the trend against the 7 days before. */
@Composable
private fun HotspotRow(h: HotspotStat, tx: Taxonomy, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = "Show reports at ${h.location}", onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.location, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (h.trend) { Trend.MORE -> "↑ more"; Trend.FEWER -> "↓ fewer"; Trend.SAME -> "→ same" }, style = MaterialTheme.typography.labelMedium,
                    color = if (h.trend == Trend.MORE) MaterialTheme.colorScheme.primary else secondaryText)
            }
            Text("${h.total} report${if (h.total != 1) "s" else ""} in ${HotspotRules.HOTSPOT_WINDOW_DAYS} days" + (h.topType?.let { " · mostly " + Hotspots.shortType(tx.category(it)?.label ?: it) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${h.last7} in the last ${HotspotRules.TREND_DAYS} days, ${h.prev7} in the ${HotspotRules.TREND_DAYS} before", style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A plain statistic: a big semibold number with a small caption underneath. */
@Composable
private fun Stat(value: Int, caption: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("$value", style = MaterialTheme.typography.headlineLarge)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = secondaryText)
    }
}
