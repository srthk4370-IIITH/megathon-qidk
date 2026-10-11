package com.megathon.incidentvoice.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import com.megathon.incidentvoice.ui.components.*
import com.megathon.incidentvoice.ui.theme.secondaryText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.orchestrator.*
import com.megathon.incidentvoice.ui.FormDisplay
import com.megathon.incidentvoice.ui.theme.Amber
import com.megathon.incidentvoice.ui.theme.FilledGreen
import com.megathon.incidentvoice.ui.theme.Grey
import com.megathon.incidentvoice.ui.theme.SevCritical
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(ui: ConversationUiState, cfg: AppConfigBundle, o: ConversationOrchestrator) {
    val tx = cfg.taxonomy
    val f = ui.form
    val v = ui.validation
    val missing = v?.missing.orEmpty()
    var editing by remember { mutableStateOf<String?>(null) }
    var newRec by remember { mutableStateOf("") }
    // Optional fields nobody mentioned are folded away so the screen shows what matters: filled values and gaps.
    var showOptional by remember { mutableStateOf(false) }
    fun visible(key: String) = showOptional || key in missing || (f.state(key)?.status ?: FieldStatus.MISSING) != FieldStatus.MISSING
    // "Show your evidence": tapping a short field highlights the words it came from; tapping it again clears the highlight
    var evidenceFor by remember(ui.incidentId) { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalEvidence provides EvidenceUi(f, evidenceFor) { k -> evidenceFor = if (evidenceFor == k) null else k }) {
    Column(Modifier.fillMaxSize()) {
    // Extra 1: the response clock stays pinned while the form scrolls underneath
    ui.response?.let { com.megathon.incidentvoice.ui.components.ResponseClockCard(it, o::onResponseStep, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
    if (ui.userTurns.isNotEmpty()) TranscriptCard(ui.userTurns, evidenceFor?.let { f.evidence[it] }.orEmpty(), evidenceFor?.let { tx.label(it) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ScreenTitle(if (ui.editingExisting) "Edit report" else "Review report")
            if (missing.isNotEmpty()) {
                Text("Needs details", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${missing.size} ${if (missing.size > 1) "fields" else "field"}: ${missing.take(3).joinToString { tx.label(it) }}${if (missing.size > 3) "…" else ""}")
            } else Text("All required fields complete.", color = secondaryText)
            ui.notice?.let { Text(it, color = if (it == ConversationOrchestrator.FALLBACK_NOTICE || it.startsWith("Picked up")) secondaryText else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ui.emergency?.takeIf { ui.response == null }?.let { Spacer(Modifier.height(8.dp)); EmergencyBannerView(it, o::onEmergencyCall, o::onEmergencyDismiss) }
        }

        // ---- Summary
        item { SectionHeader("Summary") }
        item {
            FlatCard {
                FieldRow("Type", tx.category(f.incidentType ?: "")?.label ?: "–", f.state("incident_type")?.status, "incident_type" in missing, evidenceKey = "incident_type") { editing = "incident_type" }
                QDivider()
                FieldRow("Also involves", f.secondaryTypes.joinToString(", ") { tx.category(it)?.label ?: it }.ifBlank { "Nothing else" }, null, false) { editing = "secondary_types" }
                QDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row {
                        Text("Severity" + (f.severitySource?.let { " · set by ${when (it) { FieldSource.USER -> "you"; FieldSource.RULE -> "a rule"; else -> "the model" }}" } ?: ""),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = secondaryText)
                        if ("severity" in missing) Text("Required", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("LOW", "MEDIUM", "HIGH", "CRITICAL").forEach { s ->
                            FilterChip(
                                selected = f.str("severity") == s, onClick = { o.onFieldEdited("severity", JsonPrimitive(s)) },
                                label = { Text(s.lowercase().replaceFirstChar { it.uppercase() }) }, leadingIcon = { SeverityDot(s) }, modifier = Modifier.heightIn(min = 48.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSurface),
                                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = f.str("severity") == s, borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.onSurface, selectedBorderWidth = 1.5.dp),
                            )
                        }
                        PlainButton("Unknown", { o.onFieldUnknown("severity") })
                    }
                }
                QDivider()
                FieldRow("Title", f.str("title") ?: "–", f.state("title")?.status, false) { editing = "title" }
                QDivider()
                FieldRow("Description", f.str("description") ?: "–", f.state("description")?.status, "description" in missing, onUnknown = null) { editing = "description" }
            }
        }

        // ---- When & where
        item { SectionHeader("When and where") }
        item {
            FlatCard {
                val st = f.state("occurred_at")
                val txt = FormDisplay.valueText("occurred_at", st?.value, f, tx)
                FieldRow("Date and time", if (st?.status == FieldStatus.UNKNOWN) "Unknown" else txt, st?.status, "occurred_at" in missing, onUnknown = { o.onFieldUnknown("occurred_at") },
                    hint = if (st?.status == FieldStatus.DEFAULTED) "Time was not mentioned; the time of the report is assumed" else null, evidenceKey = "occurred_at") { editing = "occurred_at" }
                QDivider()
                FieldRow("Location", f.str("location") ?: "–", f.state("location")?.status, "location" in missing, onUnknown = { o.onFieldUnknown("location") },
                    hint = null, evidenceKey = "location", note = f.locationId?.let { id -> cfg.registry.locations.firstOrNull { it.id == id }?.let { "Site location: ${it.display}" } }) { editing = "location" }
                f.locationSuggestionId?.let { id ->
                    cfg.registry.locations.firstOrNull { it.id == id }?.let { loc ->
                        PlainButton("Did you mean ${loc.name} (${loc.building})?", { o.onFieldEdited("location", JsonPrimitive(loc.name)) }, Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }

        // ---- People
        item { SectionHeader("People") }
        item { FieldGroup(listOf("injuries", "injured_count", "persons", "witnesses", "hazard_ongoing").filter { visible(it) }, ui, tx, missing) { editing = it } }

        // ---- Category details
        val details = tx.detailFields(f.types)
        if (details.isNotEmpty()) {
            item { SectionHeader("Category details") }
            item { FieldGroup(details.map { it.key }.filter { visible(it) }, ui, tx, missing) { editing = it } }
        }

        // ---- Equipment & actions
        item { SectionHeader("Equipment and actions taken") }
        item { FieldGroup(listOf("equipment", "actions_taken").filter { visible(it) }, ui, tx, missing) { editing = it } }
        item { PlainButton(if (showOptional) "Hide empty optional fields" else "Show all optional fields", { showOptional = !showOptional }) }

        // ---- Recommendations
        item { SectionHeader("Recommended actions") }
        item {
            FlatCard {
                ui.recommendations.forEachIndexed { n, r ->
                    if (n > 0) QDivider()
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(r.done, { chk -> o.onRecommendationEdited(ui.recommendations.map { if (it === r) it.copy(done = chk) else it }) },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface, checkmarkColor = MaterialTheme.colorScheme.surface))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(r.text)
                            if (r.immediate) Text("Immediate", style = MaterialTheme.typography.labelMedium, color = SevCritical)
                        }
                        IconButton({ o.onRecommendationEdited(ui.recommendations.filter { it !== r }) }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Remove", tint = secondaryText) }
                    }
                }
                if (ui.recommendations.isNotEmpty()) QDivider()
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newRec, { newRec = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add an action") })
                    PlainButton("Add", { if (newRec.isNotBlank()) { o.onRecommendationEdited(ui.recommendations + Recommendation("USER", newRec.trim(), false, "USER")); newRec = "" } })
                }
            }
            Text(cfg.sop.disclaimer, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = secondaryText)
        }

        // ---- Reporter + submit
        item {
            SectionHeader("Reporter")
            val rep = ui.reporterName ?: LocalReporter.current
            Text(if (rep.isBlank()) "Not set. Add your name in Settings." else rep)
            Text(if (ui.reporterName != null) "As introduced by voice." else "Taken from the device profile.", style = MaterialTheme.typography.bodySmall, color = secondaryText)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (ui.editingExisting) "Save changes" else "Submit report", o::onSubmit, enabled = ui.canSubmit)
            PlainButton(if (ui.editingExisting) "Close without saving further" else "Discard (keeps a draft)", o::onCancel)
        }
    }

    }
    }

    editing?.let { key ->
        FieldEditor(key, ui, tx, onDismiss = { editing = null }) { value ->
            o.onFieldEdited(key, value); editing = null
        }
    }
}

/** A group of form fields as one bordered list with dividers. */
@Composable
private fun FieldGroup(keys: List<String>, ui: ConversationUiState, tx: Taxonomy, missing: List<String>, onEdit: (String) -> Unit) {
    if (keys.isEmpty()) { Text("Nothing mentioned.", color = secondaryText); return }
    FlatCard { keys.forEachIndexed { n, k -> if (n > 0) QDivider(); GenericRow(k, ui, tx, missing, onEdit) } }
}

@Composable
private fun GenericRow(key: String, ui: ConversationUiState, tx: Taxonomy, missing: List<String>, onEdit: (String) -> Unit) {
    val def = tx.field(key) ?: return
    val st = ui.form.state(key)
    val txt = when (st?.status) {
        null, FieldStatus.MISSING -> "–"
        FieldStatus.UNKNOWN -> "Unknown"
        else -> FormDisplay.valueText(key, st.value, ui.form, tx)
    }
    FieldRow(def.label.ifBlank { key }, txt, st?.status, key in missing, unknownKey = key.takeUnless { it in setOf("incident_type", "description") }, evidenceKey = key) { onEdit(key) }
}

/**
 * One editable review row: label (secondary) above value (primary), "Required" in coral when a mandatory field is
 * empty, "Check" in amber when the value was inferred or assumed, an edit icon on the right.
 * "Unknown" is offered for every non-type/description field (PRD §14.5).
 */
@Composable
private fun FieldRow(
    label: String, value: String, status: FieldStatus?, requiredMissing: Boolean, onUnknown: (() -> Unit)? = null,
    hint: String? = null, unknownKey: String? = null, note: String? = null, evidenceKey: String? = null, onEdit: () -> Unit,
) {
    val check = status == FieldStatus.INFERRED || status == FieldStatus.DEFAULTED
    val orch = LocalOrchestrator.current
    val ev = LocalEvidence.current?.takeIf { evidenceKey in Evidence.FIELDS }
    val shown = ev != null && ev.selected == evidenceKey
    val needsReview = ev != null && Evidence.needsReview(ev.form, evidenceKey!!)
    Row(Modifier.fillMaxWidth().background(if (shown) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
        .then(if (ev != null) Modifier.clickable(onClickLabel = "Show evidence for $label") { ev.toggle(evidenceKey!!) } else Modifier)
        .heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = secondaryText)
                if (requiredMissing) Text("Required", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else if (needsReview) NeedsReviewChip()
                else if (check) Text("Check", style = MaterialTheme.typography.labelMedium, color = Amber)
            }
            Text(value, color = if (status == FieldStatus.UNKNOWN) secondaryText else MaterialTheme.colorScheme.onSurface)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Amber) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryText) }
        }
        val un: (() -> Unit)? = onUnknown ?: unknownKey?.let { k -> { orch?.onFieldUnknown(k); Unit } }
        if (un != null && label != "Title" && status != FieldStatus.UNKNOWN) TextButton(un, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = secondaryText)) { Text("Unknown") }
        IconButton(onEdit, Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "Edit $label", tint = secondaryText) }
    }
}

private typealias Color = androidx.compose.ui.graphics.Color
val LocalReporter = androidx.compose.runtime.staticCompositionLocalOf { "" }
val LocalOrchestrator = androidx.compose.runtime.staticCompositionLocalOf<ConversationOrchestrator?> { null }

// ---------------------------------------------------------------------------------------------

@Composable
private fun FieldEditor(key: String, ui: ConversationUiState, tx: Taxonomy, onDismiss: () -> Unit, onSave: (JsonElement?) -> Unit) {
    val def = tx.field(key) ?: return onDismiss()
    val cur = ui.form.value(key)
    val curText = when {
        key == "occurred_at" -> ui.form.timeExpression.orEmpty()
        cur is JsonArray -> cur.joinToString(", ") { e -> if (e is JsonObject) (e["name"] as? JsonPrimitive)?.contentOrNull.orEmpty() else (e as? JsonPrimitive)?.contentOrNull.orEmpty() }
        else -> (cur as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    var text by remember { mutableStateOf(curText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(def.label.ifBlank { key }) },
        text = {
            when {
                def.type == "enum" || def.type == "enum_category" || def.type == "enum_category_list" -> {
                    val options = when (def.type) { "enum" -> def.enum!!; else -> tx.categoryCodes }
                    val multi = def.type == "enum_category_list"
                    val chosen = remember { mutableStateListOf<String>().also { l -> if (multi) l.addAll(ui.form.secondaryTypes) else (cur as? JsonPrimitive)?.contentOrNull?.let { l += it } } }
                    Column {
                        options.forEach { opt ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(opt in chosen, onClick = { if (multi) { if (opt in chosen) chosen.remove(opt) else if (chosen.size < 2) chosen.add(opt) } else { chosen.clear(); chosen.add(opt) } }), verticalAlignment = Alignment.CenterVertically) {
                                if (multi) Checkbox(opt in chosen, null) else RadioButton(opt in chosen, null)
                                Spacer(Modifier.width(8.dp)); Text(if (def.type == "enum") opt.replace('_', ' ') else (tx.category(opt)?.label ?: opt))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        PlainButton("Save", { onSave(if (multi) listValue(chosen.toList()) else chosen.firstOrNull()?.let { JsonPrimitive(it) }) })
                    }
                }
                def.type == "bool" -> Row { TextButton({ onSave(JsonPrimitive(true)) }) { Text("Yes") }; TextButton({ onSave(JsonPrimitive(false)) }) { Text("No") } }
                else -> OutlinedTextField(text, { text = it }, label = { Text(when (def.type) { "string_list", "person_list", "witness_list" -> "Comma-separated"; "time_expression" -> "e.g. around 10 AM, 30 minutes ago"; else -> "Value" }) },
                    singleLine = def.type != "string" || key != "description", modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (def.type !in setOf("enum", "enum_category", "enum_category_list", "bool")) TextButton({
                val t = text.trim()
                onSave(when {
                    t.isEmpty() -> null
                    def.type == "string_list" -> listValue(t.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    def.type == "person_list" -> JsonArray(t.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { JsonObject(mapOf("name" to JsonPrimitive(it), "role" to JsonPrimitive("INVOLVED"), "injured" to JsonPrimitive(false))) })
                    def.type == "witness_list" -> JsonArray(t.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { JsonObject(mapOf("name" to JsonPrimitive(it))) })
                    def.type == "int" -> t.toLongOrNull()?.let { JsonPrimitive(it) }
                    else -> JsonPrimitive(t)
                })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
