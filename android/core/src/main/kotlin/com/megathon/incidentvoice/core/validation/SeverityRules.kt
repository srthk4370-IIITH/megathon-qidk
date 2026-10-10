package com.megathon.incidentvoice.core.validation

import com.megathon.incidentvoice.core.model.FieldSource
import com.megathon.incidentvoice.core.model.FieldState
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.model.Severity
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic severity floor, PRD §10.6. Raises, never lowers; sets source RULE.
 * A user override (USER_EDITED) is never touched.
 */
object SeverityRules {
    data class Floor(val level: Severity, val reason: String)

    fun floor(form: IncidentForm): Floor? {
        val out = mutableListOf<Floor>()
        val pc = form.enumValue("patient_condition")
        if (pc in setOf("UNCONSCIOUS", "NOT_BREATHING", "DECEASED")) out += Floor(Severity.CRITICAL, "patient_condition=$pc")
        if (form.enumValue("people_trapped") == "YES") out += Floor(Severity.CRITICAL, "people_trapped=YES")
        if (form.enumValue("fire_status") == "ACTIVE") {
            val ev = form.enumValue("evacuation_status")
            out += if (ev == "NOT_STARTED" || ev == "IN_PROGRESS") Floor(Severity.CRITICAL, "fire_status=ACTIVE,evacuation_status=$ev")
            else Floor(Severity.HIGH, "fire_status=ACTIVE")
        }
        if ("HAZMAT" in form.types && form.enumValue("release_status") == "ONGOING") out += Floor(Severity.HIGH, "HAZMAT release ONGOING")
        val injuries = form.enumValue("injuries")
        if (form.enumValue("electrical_hazard") == "SHOCK" && injuries == "YES") out += Floor(Severity.CRITICAL, "electrical SHOCK with injury")
        if (injuries == "YES") out += Floor(Severity.MEDIUM, "injuries=YES")
        if (form.enumValue("collision_type") == "VEHICLE_PERSON") out += Floor(Severity.HIGH, "collision_type=VEHICLE_PERSON")
        return out.maxByOrNull { it.level.ordinal }
    }

    /** Applies the floor. If severity was missing it becomes INFERRED (counts as filled). */
    fun apply(form: IncidentForm, turnId: Long? = null): IncidentForm {
        val f = floor(form) ?: return form
        val st = form.state("severity")
        if (st.status == FieldStatus.USER_EDITED) return form
        val current = if (st.status == FieldStatus.MISSING || st.status == FieldStatus.UNKNOWN) null else form.severity
        if (current != null && current.ordinal >= f.level.ordinal) return form
        return form.with(
            FieldState(
                key = "severity",
                value = JsonPrimitive(f.level.name),
                status = FieldStatus.INFERRED,
                source = FieldSource.RULE,
                turnId = turnId ?: st.turnId,
                grounded = true,
                askedCount = st.askedCount,
            ),
        ).withFlag("SEVERITY_RAISED_BY_RULE")
    }
}
