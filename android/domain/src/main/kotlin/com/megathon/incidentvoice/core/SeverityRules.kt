package com.megathon.incidentvoice.core

/** PRD §10.6 deterministic floor: raises, never lowers. */
object SeverityRules {
    private val ORDER = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
    fun rank(s: String?): Int = ORDER.indexOf(s)

    /** Minimum severity implied by the form, or null if no rule fires. */
    fun floor(form: IncidentForm): String? {
        var min = -1
        fun raise(level: String) { min = maxOf(min, rank(level)) }
        val pc = form.str("patient_condition")
        if (pc in setOf("UNCONSCIOUS", "NOT_BREATHING", "DECEASED")) raise("CRITICAL")
        if (form.str("people_trapped") == "YES") raise("CRITICAL")
        if (form.str("fire_status") == "ACTIVE") {
            raise(if (form.str("evacuation_status") in setOf("NOT_STARTED", "IN_PROGRESS")) "CRITICAL" else "HIGH")
        }
        if ("HAZMAT" in form.types && form.str("release_status") == "ONGOING") raise("HIGH")
        if (form.str("electrical_hazard") == "SHOCK" && form.str("injuries") == "YES") raise("CRITICAL")
        if (form.str("injuries") == "YES") raise("MEDIUM")
        if (form.str("collision_type") == "VEHICLE_PERSON") raise("HIGH")
        return if (min < 0) null else ORDER[min]
    }
}
