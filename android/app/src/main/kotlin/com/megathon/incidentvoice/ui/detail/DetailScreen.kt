package com.megathon.incidentvoice.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.core.FieldStatus
import com.megathon.incidentvoice.core.MergeEngine
import com.megathon.incidentvoice.core.MergedCase
import com.megathon.incidentvoice.core.WitnessAccount
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.megathon.incidentvoice.core.Taxonomy
import com.megathon.incidentvoice.data.IncidentDetail
import com.megathon.incidentvoice.data.IncidentRepository
import com.megathon.incidentvoice.ui.FormDisplay
import com.megathon.incidentvoice.ui.components.SeverityChip
import com.megathon.incidentvoice.ui.components.StatusChip
import com.megathon.incidentvoice.ui.fmtDateTime
import com.megathon.incidentvoice.ui.theme.Amber
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    id: String, repo: IncidentRepository, tx: Taxonomy, disclaimer: String,
    similar: suspend (String) -> List<com.megathon.incidentvoice.ask.AskHit> = { emptyList() }, onOpen: (String) -> Unit = {},
    onBack: () -> Unit, onDeleted: () -> Unit, onEdit: (String) -> Unit,
) {
    val similarHits by produceState(initialValue = emptyList<com.megathon.incidentvoice.ask.AskHit>(), id) { value = similar(id) }
    val detail by remember(id) { repo.observeDetail(id) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // Extra 3: the witness accounts of this case, merged; recomputed when the report or a resolution changes
    var caseTick by remember { mutableIntStateOf(0) }
    val case by produceState<MergedCase?>(null, id, detail, caseTick) {
        val all = repo.caseOf(id)
        value = if (all.size < 2) null else MergeEngine.merge(all.map { WitnessAccount.of(it.meta, it.form) }, repo.conflictResolutions(all.first().meta.id)) { tx.category(it)?.label ?: it }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    // nested inside the app Scaffold, which already applies the system-bar insets
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(detail?.meta?.refNo ?: "Incident") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton({ onEdit(id) }) { Icon(Icons.Default.Edit, "Edit") }
                IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            },
        )
    }) { pad ->
        val d = detail
        if (d == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        DetailBody(d, tx, disclaimer, Modifier.padding(pad), similarHits, onOpen, case, { key, value ->
            scope.launch { case?.accounts?.firstOrNull()?.let { root -> repo.resolveConflict(root.id, key, value, com.megathon.incidentvoice.data.Actor.User(d.meta.reporter.empId.ifBlank { "supervisor" })); caseTick++ } }
        }) { recId, done -> scope.launch { repo.setRecommendationDone(recId, done) } }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this incident?") }, text = { Text("The report, its recordings and search entry are removed. An audit entry is kept.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { repo.delete(id, com.megathon.incidentvoice.data.Actor.System); onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DetailBody(d: IncidentDetail, tx: Taxonomy, disclaimer: String, modifier: Modifier, similarHits: List<com.megathon.incidentvoice.ask.AskHit>, onOpen: (String) -> Unit, case: MergedCase?, onTrust: (String, String) -> Unit, onTick: (Long, Boolean) -> Unit) {
    val f = d.form
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityChip(f.str("severity")); StatusChip(d.meta.status.name)
                Text(f.types.joinToString(" + ") { tx.category(it)?.label ?: it }, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(f.str("title") ?: "Incident", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(f.str("description").orEmpty())
        }
        if (case != null) item(key = "case") { WitnessTable(case, d.meta.id, onOpen, onTrust) }
        item { SectionTitle("Details") }
        items(FormDisplay.rows(f, tx), key = { it.key }) { r ->
            Row(Modifier.fillMaxWidth()) {
                Text(r.label, Modifier.weight(0.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.value, Modifier.weight(0.6f), color = if (r.status == FieldStatus.DEFAULTED || r.status == FieldStatus.INFERRED) Amber else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (f.locationId != null) item { Text("Matched site location: ${f.locationId}", style = MaterialTheme.typography.bodySmall) }
        if (d.recommendations.isNotEmpty()) {
            item { SectionTitle("Recommended actions") }
            items(d.recommendations, key = { "r${it.id}" }) { r ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(r.done, { onTick(r.id, it) })
                    Column {
                        Text(r.text); if (r.immediate) Text("Immediate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { Text(disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (d.turns.isNotEmpty()) {
            item { SectionTitle("Transcript") }
            items(d.turns, key = { "t${it.idx}" }) { t ->
                Text((if (t.role == "ASSISTANT") "Assistant: " else "Reporter: ") + t.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        d.meta.responseStartedAt?.let { start ->
            item {
                SectionTitle("Response timeline")
                Text(com.megathon.incidentvoice.core.ResponseClock.timeline(start, d.responseActions))
                Text("Clock started ${fmtDateTime(start)} · ${d.responseActions.size} step${if (d.responseActions.size != 1) "s" else ""} logged", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (similarHits.isNotEmpty()) {
            item { SectionTitle("Similar incidents") }
            items(similarHits, key = { "s${it.id}" }) { h -> com.megathon.incidentvoice.ui.ask.HitCard(h, tx, false, onOpen) }
        }
        item {
            SectionTitle("Reporter")
            Text("${d.meta.reporter.name.ifBlank { "—" }} ${d.meta.reporter.empId}".trim() + " · reported ${fmtDateTime(d.meta.reportedAt)}")
        }
    }
}

/** Extra 3: Field × Witness table. A conflicting row is highlighted; tapping one of its cells marks that value as trusted (audit-logged). */
@Composable
private fun WitnessTable(case: MergedCase, currentId: String, onOpen: (String) -> Unit, onTrust: (String, String) -> Unit) {
    var showSummary by remember { mutableStateOf(false) }
    val open = case.openConflicts
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Witness accounts · ${case.accounts.size} accounts · $open conflict${if (open != 1) "s" else ""}" + (if (case.conflicts > open) " (${case.conflicts - open} resolved)" else ""))
        Text("Each column is one person's report. Rows in amber disagree: tap the value you trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val labelW = 84.dp; val cellW = 112.dp
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Text("Field", Modifier.width(labelW).padding(4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                case.accounts.forEachIndexed { n, a ->
                    Column(Modifier.width(cellW).clickable(enabled = a.id != currentId) { onOpen(a.id) }.padding(4.dp)) {
                        Text(a.reporter.ifBlank { "Witness ${n + 1}" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text((a.refNo?.takeLast(9) ?: "") + if (a.id == currentId) " · this" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Text("Merged", Modifier.width(cellW).padding(4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            case.fields.forEach { f ->
                HorizontalDivider()
                val unresolved = f.conflict && f.resolved == null
                Row(Modifier.background(if (unresolved) Amber.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)) {
                    Text(f.label + if (f.conflict) " ⚠" else "", Modifier.width(labelW).padding(4.dp), style = MaterialTheme.typography.labelMedium)
                    f.values.forEach { v ->
                        val trusted = f.resolved != null && v == f.resolved
                        Text(
                            (if (trusted) "✓ " else "") + v,
                            Modifier.width(cellW).clickable(enabled = f.conflict && v != MergeEngine.NONE) { onTrust(f.key, v) }.padding(4.dp),
                            style = MaterialTheme.typography.bodySmall, fontWeight = if (trusted || unresolved) FontWeight.Bold else FontWeight.Normal,
                            maxLines = if (f.key == "description") 6 else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (f.key == "description") "${case.accounts.size} accounts, kept side by side" else f.merged + (if (f.resolved != null) "  (trusted)" else ""),
                        Modifier.width(cellW).padding(4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        TextButton({ showSummary = !showSummary }) { Text(if (showSummary) "Hide summary" else "Summarise accounts") }
        if (showSummary) Text(MergeEngine.summary(case), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
