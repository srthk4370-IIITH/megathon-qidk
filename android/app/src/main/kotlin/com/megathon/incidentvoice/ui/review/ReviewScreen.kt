package com.megathon.incidentvoice.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.megathon.incidentvoice.ui.components.EmergencyBannerView
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            if (missing.isNotEmpty()) {
                Text("${missing.size} field${if (missing.size > 1) "s" else ""} need attention: ${missing.take(3).joinToString { tx.label(it) }}${if (missing.size > 3) "…" else ""}",
                    color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.fillMaxWidth().background(SevCritical, RoundedCornerShape(8.dp)).padding(10.dp))
            } else Text("All required fields complete.", color = FilledGreen, fontWeight = FontWeight.Bold)
            ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ui.emergency?.let { Spacer(Modifier.height(8.dp)); EmergencyBannerView(it, o::onEmergencyCall, o::onEmergencyDismiss) }
        }

        // ---- Summary
        item { Section("Summary") }
        item {
            Text("Type", style = MaterialTheme.typography.labelMedium, color = Grey)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { editing = "incident_type" }, label = { Text(tx.category(f.incidentType ?: "")?.label ?: "Choose type") },
                    border = if ("incident_type" in missing) AssistChipDefaults.assistChipBorder(true, SevCritical) else AssistChipDefaults.assistChipBorder(true))
                f.secondaryTypes.forEach { AssistChip(onClick = { editing = "secondary_types" }, label = { Text(tx.category(it)?.label ?: it) }) }
                AssistChip(onClick = { editing = "secondary_types" }, label = { Text("+ also involves") })
            }
        }
        item {
            Text("Severity" + (f.severitySource?.let { "  (${when (it) { FieldSource.USER -> "you"; FieldSource.RULE -> "rule"; else -> "AI" }})" } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = if ("severity" in missing) SevCritical else Grey)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("LOW", "MEDIUM", "HIGH", "CRITICAL").forEach { s ->
                    FilterChip(selected = f.str("severity") == s, onClick = { o.onFieldEdited("severity", JsonPrimitive(s)) }, label = { Text(s) })
                }
                TextButton({ o.onFieldUnknown("severity") }) { Text("Unknown") }
            }
        }
        item { FieldRow("Title", f.str("title") ?: "—", f.state("title")?.status, false) { editing = "title" } }
        item { FieldRow("Description", f.str("description") ?: "—", f.state("description")?.status, "description" in missing, onUnknown = null) { editing = "description" } }

        // ---- When & where
        item { Section("When & where") }
        item {
            val st = f.state("occurred_at")
            val txt = FormDisplay.valueText("occurred_at", st?.value, f, tx)
            FieldRow("Date & time", if (st?.status == FieldStatus.UNKNOWN) "Unknown" else txt, st?.status, "occurred_at" in missing, onUnknown = { o.onFieldUnknown("occurred_at") },
                hint = if (st?.status == FieldStatus.DEFAULTED) "Time assumed (not mentioned) — please check" else null) { editing = "occurred_at" }
        }
        item {
            FieldRow("Location", f.str("location") ?: "—", f.state("location")?.status, "location" in missing, onUnknown = { o.onFieldUnknown("location") }) { editing = "location" }
            f.locationId?.let { id -> cfg.registry.locations.firstOrNull { it.id == id }?.let { Text("✓ ${it.display}", color = FilledGreen, style = MaterialTheme.typography.bodySmall) } }
            f.locationSuggestionId?.let { id ->
                cfg.registry.locations.firstOrNull { it.id == id }?.let { loc ->
                    SuggestionChip(onClick = { o.onFieldEdited("location", JsonPrimitive(loc.name)) }, label = { Text("Did you mean ${loc.name} (${loc.building})?") })
                }
            }
        }

        // ---- People
        item { Section("People") }
        items(listOf("injuries", "injured_count", "persons", "witnesses", "hazard_ongoing")) { k -> GenericRow(k, ui, tx, missing) { editing = it } }

        // ---- Category details
        val details = tx.detailFields(f.types)
        if (details.isNotEmpty()) {
            item { Section("Category details") }
            items(details, key = { it.key }) { d -> GenericRow(d.key, ui, tx, missing) { editing = it } }
        }

        // ---- Equipment & actions
        item { Section("Equipment & actions taken") }
        items(listOf("equipment", "actions_taken")) { k -> GenericRow(k, ui, tx, missing) { editing = it } }

        // ---- Recommendations
        item { Section("Recommended actions") }
        items(ui.recommendations, key = { it.sopId + it.text }) { r ->
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(r.done, { chk -> o.onRecommendationEdited(ui.recommendations.map { if (it === r) it.copy(done = chk) else it }) })
                Column(Modifier.weight(1f)) {
                    Text(r.text)
                    if (r.immediate) Text("Immediate", style = MaterialTheme.typography.labelSmall, color = SevCritical)
                }
                IconButton({ o.onRecommendationEdited(ui.recommendations.filter { it !== r }) }) { Icon(Icons.Default.Close, "Remove") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newRec, { newRec = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add an action") })
                TextButton({ if (newRec.isNotBlank()) { o.onRecommendationEdited(ui.recommendations + Recommendation("USER", newRec.trim(), false, "USER")); newRec = "" } }) { Text("Add") }
            }
            Text(cfg.sop.disclaimer, style = MaterialTheme.typography.bodySmall, color = Grey)
        }

        // ---- Reporter + submit
        item {
            Section("Reporter")
            Text("Taken from the device profile (Settings).", style = MaterialTheme.typography.bodySmall, color = Grey)
            Spacer(Modifier.height(8.dp))
            Button(onClick = o::onSubmit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Submit report") }
            Spacer(Modifier.height(4.dp))
            TextButton(o::onCancel, Modifier.fillMaxWidth()) { Text("Discard (keeps a draft)") }
        }
    }

    editing?.let { key ->
        FieldEditor(key, ui, tx, onDismiss = { editing = null }) { value ->
            o.onFieldEdited(key, value); editing = null
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

@Composable
private fun GenericRow(key: String, ui: ConversationUiState, tx: Taxonomy, missing: List<String>, onEdit: (String) -> Unit) {
    val def = tx.field(key) ?: return
    val st = ui.form.state(key)
    val txt = when (st?.status) {
        null, FieldStatus.MISSING -> "—"
        FieldStatus.UNKNOWN -> "Unknown"
        else -> FormDisplay.valueText(key, st.value, ui.form, tx)
    }
    FieldRow(def.label.ifBlank { key }, txt, st?.status, key in missing, unknownKey = key.takeUnless { it in setOf("incident_type", "description") }) { onEdit(key) }
}

/** One editable review row. "Unknown" is offered for every non-type/description field (PRD §14.5). */
@Composable
private fun FieldRow(
    label: String, value: String, status: FieldStatus?, requiredMissing: Boolean, onUnknown: (() -> Unit)? = null,
    hint: String? = null, unknownKey: String? = null, onEdit: () -> Unit,
) {
    val color = when {
        requiredMissing -> SevCritical
        status == FieldStatus.INFERRED || status == FieldStatus.DEFAULTED -> Amber
        else -> Color.Transparent
    }
    val orch = LocalOrchestrator.current
    Column(
        Modifier.fillMaxWidth().then(if (color != Color.Transparent) Modifier.border(1.5.dp, color, RoundedCornerShape(8.dp)) else Modifier)
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label + if (requiredMissing) "  · required" else "", style = MaterialTheme.typography.labelMedium, color = if (requiredMissing) SevCritical else Grey)
                Text(value, color = if (status == FieldStatus.UNKNOWN) Grey else MaterialTheme.colorScheme.onSurface)
                hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Amber) }
            }
            TextButton(onEdit) { Text("Edit") }
            val un: (() -> Unit)? = onUnknown ?: unknownKey?.let { k -> { orch?.onFieldUnknown(k); Unit } }
            if (un != null && label != "Title") TextButton(onClick = un) { Text("Unknown") }
        }
    }
}

private typealias Color = androidx.compose.ui.graphics.Color
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
                            Row(Modifier.fillMaxWidth().selectable(opt in chosen, onClick = { if (multi) { if (opt in chosen) chosen.remove(opt) else if (chosen.size < 2) chosen.add(opt) } else { chosen.clear(); chosen.add(opt) } }), verticalAlignment = Alignment.CenterVertically) {
                                if (multi) Checkbox(opt in chosen, null) else RadioButton(opt in chosen, null)
                                Spacer(Modifier.width(8.dp)); Text(if (def.type == "enum") opt.replace('_', ' ') else (tx.category(opt)?.label ?: opt))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton({ onSave(if (multi) listValue(chosen.toList()) else chosen.firstOrNull()?.let { JsonPrimitive(it) }) }) { Text("Save") }
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
