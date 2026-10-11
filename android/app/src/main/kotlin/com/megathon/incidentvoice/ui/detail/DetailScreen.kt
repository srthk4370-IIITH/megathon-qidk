package com.megathon.incidentvoice.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.unit.sp
import com.megathon.incidentvoice.ui.components.FlatCard
import com.megathon.incidentvoice.ui.components.QDivider
import com.megathon.incidentvoice.ui.theme.secondaryText
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
import com.megathon.incidentvoice.ui.components.SeverityLabel
import com.megathon.incidentvoice.ui.components.StatusText
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
    val tags by produceState(com.megathon.incidentvoice.core.ReportTags(), id, detail) { value = repo.reportTags(id) ?: com.megathon.incidentvoice.core.ReportTags() }

    // nested inside the app Scaffold, which already applies the system-bar insets
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(detail?.meta?.refNo ?: "Report", style = com.megathon.incidentvoice.ui.theme.Mono.copy(fontSize = 17.sp)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton({ onEdit(id) }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "Edit") }
                IconButton({ confirmDelete = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Delete, "Delete") }
            },
        )
    }) { pad ->
        val d = detail
        if (d == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        DetailBody(d, tx, disclaimer, Modifier.padding(pad), similarHits, onOpen, case, tags, { key, value ->
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
private fun DetailBody(d: IncidentDetail, tx: Taxonomy, disclaimer: String, modifier: Modifier, similarHits: List<com.megathon.incidentvoice.ask.AskHit>, onOpen: (String) -> Unit, case: MergedCase?, tags: com.megathon.incidentvoice.core.ReportTags, onTrust: (String, String) -> Unit, onTick: (Long, Boolean) -> Unit) {
    val f = d.form
    // "Show your evidence": tap a short field to see the words it came from in the transcript pinned on top
    var evidenceFor by remember(d.meta.id) { mutableStateOf<String?>(null) }
    val said = remember(d.turns) { d.turns.filter { it.role == "USER" }.map { it.text } }
    Column(modifier.fillMaxSize()) {
    if (said.isNotEmpty()) com.megathon.incidentvoice.ui.components.TranscriptCard(said, evidenceFor?.let { f.evidence[it] }.orEmpty(), evidenceFor?.let { tx.label(it) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text(f.str("title") ?: "Report", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SeverityLabel(f.str("severity")); StatusText(d.meta.status.name)
            }
            Text(f.types.joinToString(" + ") { tx.category(it)?.label ?: it } + (if (d.meta.demo) " · Demo data" else ""), style = MaterialTheme.typography.bodySmall, color = secondaryText)
            com.megathon.incidentvoice.ui.components.ReportTagChips(tags, { tx.category(it)?.label ?: it }, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(4.dp))
            Text(f.str("description").orEmpty())
        }
        if (case != null) item(key = "case") { WitnessTable(case, d.meta.id, onOpen, onTrust) }
        item { SectionTitle("Details") }
        item {
            FlatCard {
                FormDisplay.rows(f, tx).forEachIndexed { n, r ->
                    if (n > 0) QDivider()
                    val tappable = r.key in com.megathon.incidentvoice.core.Evidence.FIELDS && said.isNotEmpty()
                    Column(Modifier.fillMaxWidth().background(if (evidenceFor == r.key) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if (tappable) Modifier.clickable(onClickLabel = "Show evidence for ${r.label}") { evidenceFor = if (evidenceFor == r.key) null else r.key } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = MaterialTheme.typography.bodySmall, color = secondaryText)
                            if (tappable && com.megathon.incidentvoice.core.Evidence.needsReview(f, r.key)) com.megathon.incidentvoice.ui.components.NeedsReviewChip()
                            else if (r.status == FieldStatus.DEFAULTED || r.status == FieldStatus.INFERRED) Text("Check", style = MaterialTheme.typography.labelMedium, color = Amber)
                        }
                        Text(r.value)
                    }
                }
            }
        }
        if (f.locationId != null) item { Text("Site location: ${f.locationId}", style = MaterialTheme.typography.bodySmall, color = secondaryText) }
        if (d.recommendations.isNotEmpty()) {
            item { SectionTitle("Recommended actions") }
            item {
                FlatCard {
                    d.recommendations.forEachIndexed { n, r ->
                        if (n > 0) QDivider()
                        Row(Modifier.padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(r.done, { onTick(r.id, it) }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface, checkmarkColor = MaterialTheme.colorScheme.surface))
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(r.text); if (r.immediate) Text("Immediate", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item { Text(disclaimer, style = MaterialTheme.typography.bodySmall, color = secondaryText) }
        }
        if (d.turns.isNotEmpty()) {
            item { SectionTitle("Transcript, word for word") }
            items(d.turns, key = { "t${it.idx}" }) { t ->
                Column {
                    Text(if (t.role == "ASSISTANT") "Qalam" else "Reporter", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                    Text(t.textRaw ?: t.text)
                    if (t.textEdited != null && t.textEdited != t.textRaw) Text("Corrected by the reporter to: ${t.textEdited}", style = MaterialTheme.typography.bodySmall, color = secondaryText)
                }
            }
        }
        d.meta.responseStartedAt?.let { start ->
            item {
                SectionTitle("Response timeline")
                Text(com.megathon.incidentvoice.core.ResponseClock.timeline(start, d.responseActions), style = com.megathon.incidentvoice.ui.theme.Mono)
                Text("Clock started ${fmtDateTime(start)} · ${d.responseActions.size} step${if (d.responseActions.size != 1) "s" else ""} logged", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (similarHits.isNotEmpty()) {
            item { SectionTitle("Similar incidents") }
            item { FlatCard { similarHits.forEachIndexed { n, h -> if (n > 0) QDivider(); com.megathon.incidentvoice.ui.ask.HitCard(h, tx, false, onOpen) } } }
        }
        item {
            SectionTitle("Reporter")
            Text("${d.meta.reporter.name.ifBlank { "—" }} ${d.meta.reporter.empId}".trim() + " · reported ${fmtDateTime(d.meta.reportedAt)}")
        }
    }
    }
}

/** Extra 3: Field × Witness table. A conflicting row is highlighted; tapping one of its cells marks that value as trusted (audit-logged). */
@Composable
private fun WitnessTable(case: MergedCase, currentId: String, onOpen: (String) -> Unit, onTrust: (String, String) -> Unit) {
    var showSummary by remember { mutableStateOf(false) }
    val open = case.openConflicts
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Witness accounts")
        Text("${case.accounts.size} accounts · $open conflict${if (open != 1) "s" else ""}" + (if (case.conflicts > open) " (${case.conflicts - open} resolved)" else ""), style = MaterialTheme.typography.titleMedium)
        Text("Each column is one person's report. Highlighted rows disagree: tap the value you trust.", style = MaterialTheme.typography.bodySmall, color = secondaryText)
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
                Row(Modifier.background(if (unresolved) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                    Text(f.label, Modifier.width(labelW).padding(4.dp), style = MaterialTheme.typography.labelMedium, color = if (unresolved) MaterialTheme.colorScheme.onPrimaryContainer else secondaryText)
                    f.values.forEach { v ->
                        val trusted = f.resolved != null && v == f.resolved
                        Text(
                            v + (if (trusted) " (trusted)" else ""),
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
        com.megathon.incidentvoice.ui.components.SecondaryButton(if (showSummary) "Hide summary" else "Summarise accounts", { showSummary = !showSummary })
        if (showSummary) Text(MergeEngine.summary(case), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(t: String) = com.megathon.incidentvoice.ui.components.SectionHeader(t)
