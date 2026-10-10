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
fun DetailScreen(id: String, repo: IncidentRepository, tx: Taxonomy, disclaimer: String, onBack: () -> Unit, onDeleted: () -> Unit, onEdit: (String) -> Unit) {
    val detail by remember(id) { repo.observeDetail(id) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
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
        DetailBody(d, tx, disclaimer, Modifier.padding(pad)) { recId, done -> scope.launch { repo.setRecommendationDone(recId, done) } }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this incident?") }, text = { Text("The report, its recordings and search entry are removed. An audit entry is kept.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { repo.delete(id, com.megathon.incidentvoice.data.Actor.System); onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DetailBody(d: IncidentDetail, tx: Taxonomy, disclaimer: String, modifier: Modifier, onTick: (Long, Boolean) -> Unit) {
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
        item {
            SectionTitle("Reporter")
            Text("${d.meta.reporter.name.ifBlank { "—" }} ${d.meta.reporter.empId}".trim() + " · reported ${fmtDateTime(d.meta.reportedAt)}")
        }
    }
}

@Composable
private fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
