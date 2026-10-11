package com.megathon.incidentvoice.data

import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import java.time.Instant

/** Debug: fills the dashboard with realistic fake incidents using the real parse → merge → save path. */
class DemoSeeder(private val cfg: AppConfigBundle, private val repo: IncidentRepository) {
    private val parser = ToolCallParser(cfg.taxonomy)
    private val times = TimeResolver()
    private val merger = FormMerger(cfg.taxonomy, times, LocationMatcher(cfg.registry))
    private val sop = SopSelector(cfg.sop)

    private companion object { const val DAY = 24L * 60 }

    private class Demo(val ageMin: Long, val transcript: String, val json: String, val patch: String? = null)

    private fun fill(body: String) = """{"name":"fill_incident_form","parameters":{$body}}"""
    private fun upd(set: String) = """{"name":"update_incident_fields","parameters":{"set":{$set}}}"""

    private val demos = listOf(
        Demo(12, "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.",
            fill(""""lang":"en","incident_type":"WATER","title":"Water leak near Conference Room 2","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]"""),
            upd(""""injuries":"NO","severity":"LOW","leak_source":"burst pipe under the sink","leak_status":"STOPPED"""")),
        Demo(55, "Smoke coming from the DB-3 electrical panel in Block B floor 2, aag lagi hai, two people evacuated, fire brigade called",
            fill(""""lang":"hinglish","incident_type":"FIRE","secondary_types":["ELECTRICAL"],"title":"Fire at DB-3 panel","description":"Smoke and fire from the DB-3 electrical panel. Two people evacuated and the fire brigade has been called.","location":"DB-3 panel Block B floor 2","time":"just now","severity":"HIGH","injuries":"NO","details":{"fire_status":"ACTIVE","evacuation_status":"IN_PROGRESS","fire_brigade_called":"YES","electrical_hazard":"SPARKING","power_isolated":"NO"}"""), null),
        Demo(130, "Ramesh slipped on the wet floor near the pantry about an hour ago and hurt his ankle. First aid was given, he is conscious.",
            fill(""""lang":"en","incident_type":"MEDICAL","secondary_types":["STRUCTURAL"],"title":"Slip near pantry","description":"Ramesh slipped on the wet floor near the pantry and hurt his ankle. First aid was given.","location":"near pantry","time":"about an hour ago","injuries":"YES","injured_count":1,"persons":[{"name":"Ramesh","role":"AFFECTED","injured":true,"injury":"ankle injury"}],"actions_taken":["First aid given"],"details":{"patient_condition":"CONSCIOUS_STABLE","first_aid_given":"YES","ambulance_called":"NO","damage_type":"wet floor","area_cordoned":"YES"}"""), null),
        Demo(300, "Forklift ne bay 4 mein rack ko thok diya, driver ramesh tha, kisi ko chot nahi lagi, area band kar diya",
            fill(""""lang":"hinglish","incident_type":"VEHICLE","secondary_types":["STRUCTURAL"],"title":"Forklift hit a rack in Bay 4","description":"A forklift driven by Ramesh hit a rack in Bay 4. No one was injured. The area has been closed off.","location":"Bay 4","time":"half an hour ago","injuries":"NO","severity":"MEDIUM","persons":[{"name":"Ramesh","role":"INVOLVED","injured":false}],"actions_taken":["Area closed off"],"details":{"vehicles_involved":["forklift"],"collision_type":"VEHICLE_OBJECT","damage_type":"rack bent by forklift impact","area_cordoned":"YES"}"""), null),
        Demo(26 * 60, "Compressor P-101 in the utility room tripped and stopped, production line 2 is halted since morning.",
            fill(""""lang":"en","incident_type":"EQUIPMENT","title":"Compressor P-101 tripped","description":"Compressor P-101 in the utility room tripped and stopped; production line 2 is halted.","location":"utility room","time":"this morning","severity":"HIGH","injuries":"NO","equipment":["p-101"],"details":{"failure_description":"compressor tripped and stopped","operational_status":"STOPPED","production_impact":"line 2 halted"}"""), null),
        Demo(30 * 60, "Gas leak smell near the chemical store, workers have moved away. Ammonia I think.",
            fill(""""lang":"en","incident_type":"HAZMAT","title":"Suspected ammonia leak at chemical store","description":"A gas leak smell near the chemical store. Workers moved away. Suspected ammonia.","location":"chemical store","time":"just now","severity":"CRITICAL","injuries":"NO","details":{"substance":"ammonia","release_status":"ONGOING","area_evacuated":"YES"}"""), null),
        Demo(2 * 24 * 60, "Unauthorised person entered the server room last night, security was informed.",
            fill(""""lang":"en","incident_type":"SECURITY","title":"Unauthorised entry into server room","description":"An unauthorised person entered the server room last night. Security was informed.","location":"server room","time":"last night at 11","severity":"MEDIUM","injuries":"NO","details":{"security_event":"UNAUTHORIZED_ACCESS","security_informed":"YES"}"""), null),
        Demo(3 * 24 * 60, "Oil spill near the loading dock drain, contained with absorbent pads.",
            fill(""""lang":"en","incident_type":"ENVIRONMENTAL","title":"Oil spill near loading dock","description":"Oil spilled near the loading dock drain and was contained with absorbent pads.","location":"loading dock","time":"yesterday evening","severity":"LOW","injuries":"NO","actions_taken":["Absorbent pads placed"],"details":{"pollutant":"oil","release_medium":"DRAIN","containment_status":"CONTAINED"}"""), null),
        Demo(4 * 24 * 60, "Ceiling panel fell in the corridor on floor 1, nobody was hurt.",
            fill(""""lang":"en","incident_type":"STRUCTURAL","title":"Ceiling panel fell in corridor","description":"A ceiling panel fell in the corridor on floor 1. Nobody was hurt.","location":"corridor floor 1","time":"two days ago","severity":"LOW","injuries":"NO","near_miss":true,"details":{"damage_type":"ceiling panel fell","area_cordoned":"YES"}"""), null),
        Demo(5 * 24 * 60, "Light chali gayi in the reception area, sparking from the socket, humne power band kar di.",
            fill(""""lang":"hinglish","incident_type":"ELECTRICAL","title":"Sparking socket at reception","description":"Sparking from a socket at the reception and a power cut. Power has been switched off.","location":"reception","time":"yesterday morning","severity":"MEDIUM","injuries":"NO","details":{"electrical_hazard":"SPARKING","power_isolated":"YES"}"""), null),
    )

    /**
     * "Seed demo data" (Settings, debug builds): 12 reports spread over the last 30 days that make the repeat and hotspot
     * rules visible. Two water leaks at Conference Room 2 inside the last 7 days (the next one there is the 3rd: the
     * recurring-hazard card), five reports at the Workshop (a hotspot), two at the Loading Dock, three single ones.
     */
    private val hotspotDemos = listOf(
        Demo(5 * DAY, "Water is dripping from the ceiling in Conference Room 2 since this morning, we have put a bucket under it. Nobody is hurt.",
            fill(""""lang":"en","incident_type":"WATER","title":"Ceiling drip in Conference Room 2","description":"Water is dripping from the ceiling in Conference Room 2. A bucket has been placed under it.","location":"Conference Room 2","time":"this morning","severity":"LOW","injuries":"NO","actions_taken":["Bucket placed under the drip"],"details":{"leak_source":"ceiling","leak_status":"CONTROLLED"}""")),
        Demo(2 * DAY, "There is a water leak again in conf room 2, the carpet near the window is wet. Maintenance has been informed, no one is hurt.",
            fill(""""lang":"en","incident_type":"WATER","title":"Water leak in Conference Room 2 again","description":"A water leak in Conference Room 2 again; the carpet near the window is wet. Maintenance has been informed.","location":"conf room 2","time":"just now","severity":"LOW","injuries":"NO","actions_taken":["Maintenance informed"],"details":{"leak_source":"near the window","leak_status":"ONGOING"}""")),
        Demo(26 * DAY, "The lathe machine in the workshop jammed and stopped about an hour ago, nobody was hurt.",
            fill(""""lang":"en","incident_type":"EQUIPMENT","title":"Lathe jammed in the workshop","description":"The lathe machine in the workshop jammed and stopped. Nobody was hurt.","location":"Workshop","time":"about an hour ago","severity":"MEDIUM","injuries":"NO","equipment":["lathe machine"],"details":{"failure_description":"lathe jammed and stopped","operational_status":"STOPPED"}""")),
        Demo(11 * DAY, "Sparking from the welding socket in the workshop, humne power band kar di, kisi ko chot nahi lagi.",
            fill(""""lang":"hinglish","incident_type":"ELECTRICAL","title":"Sparking welding socket in the workshop","description":"Sparking from the welding socket in the workshop. Power has been switched off. No one was hurt.","location":"workshop","time":"just now","severity":"MEDIUM","injuries":"NO","actions_taken":["Power switched off"],"details":{"electrical_hazard":"SPARKING","power_isolated":"YES"}""")),
        Demo(6 * DAY, "Suresh cut his hand on the grinder in the workshop half an hour ago. First aid was given, he is conscious and talking.",
            fill(""""lang":"en","incident_type":"MEDICAL","secondary_types":["EQUIPMENT"],"title":"Hand cut on the workshop grinder","description":"Suresh cut his hand on the grinder in the workshop. First aid was given.","location":"Workshop","time":"half an hour ago","severity":"MEDIUM","injuries":"YES","injured_count":1,"persons":[{"name":"Suresh","role":"AFFECTED","injured":true,"injury":"cut hand"}],"equipment":["grinder"],"actions_taken":["First aid given"],"details":{"patient_condition":"CONSCIOUS_STABLE","first_aid_given":"YES","ambulance_called":"NOT_NEEDED","failure_description":"hand cut on the grinder","operational_status":"STOPPED"}""")),
        Demo(3 * DAY, "The grinder guard came loose in the workshop this morning, the machine has been stopped. No injuries.",
            fill(""""lang":"en","incident_type":"EQUIPMENT","title":"Grinder guard loose in the workshop","description":"The grinder guard came loose in the workshop. The machine has been stopped.","location":"the workshop","time":"this morning","severity":"MEDIUM","injuries":"NO","equipment":["grinder"],"actions_taken":["Machine stopped"],"details":{"failure_description":"grinder guard came loose","operational_status":"STOPPED"}""")),
        Demo(1 * DAY, "The drill press in the workshop is vibrating badly and running slow since yesterday evening, nobody is hurt.",
            fill(""""lang":"en","incident_type":"EQUIPMENT","title":"Drill press vibrating in the workshop","description":"The drill press in the workshop is vibrating badly and running slow.","location":"Workshop","time":"yesterday evening","severity":"LOW","injuries":"NO","equipment":["drill press"],"details":{"failure_description":"drill press vibrating and running slow","operational_status":"DEGRADED"}""")),
        Demo(22 * DAY, "A delivery truck hit the barrier at the loading dock a few minutes ago, no one was injured.",
            fill(""""lang":"en","incident_type":"VEHICLE","title":"Truck hit the loading dock barrier","description":"A delivery truck hit the barrier at the loading dock. No one was injured.","location":"Loading Dock","time":"a few minutes ago","severity":"LOW","injuries":"NO","details":{"vehicles_involved":["delivery truck"],"collision_type":"VEHICLE_OBJECT"}""")),
        Demo(12 * DAY, "Diesel spilled near the loading dock drain this afternoon, we placed absorbent pads and it is contained.",
            fill(""""lang":"en","incident_type":"ENVIRONMENTAL","title":"Diesel spill at the loading dock","description":"Diesel spilled near the loading dock drain. Absorbent pads were placed and it is contained.","location":"loading dock","time":"this afternoon","severity":"LOW","injuries":"NO","actions_taken":["Absorbent pads placed"],"details":{"pollutant":"diesel","release_medium":"DRAIN","containment_status":"CONTAINED"}""")),
        Demo(18 * DAY, "An unknown person was loitering near the server room last night, security was informed.",
            fill(""""lang":"en","incident_type":"SECURITY","title":"Unknown person near the server room","description":"An unknown person was loitering near the server room last night. Security was informed.","location":"server room","time":"last night","severity":"MEDIUM","injuries":"NO","details":{"security_event":"SUSPICIOUS_ACTIVITY","security_informed":"YES"}""")),
        Demo(15 * DAY, "A ceiling tile fell in the cafeteria at 1 pm, nobody was hurt and the area is cordoned off.",
            fill(""""lang":"en","incident_type":"STRUCTURAL","title":"Ceiling tile fell in the cafeteria","description":"A ceiling tile fell in the cafeteria. Nobody was hurt. The area is cordoned off.","location":"Cafeteria","time":"at 1 pm","severity":"LOW","injuries":"NO","near_miss":true,"actions_taken":["Area cordoned off"],"details":{"damage_type":"ceiling tile fell","area_cordoned":"YES"}""")),
        Demo(8 * DAY, "There is a chlorine smell near the chemical store, workers have moved away, the drum has been closed.",
            fill(""""lang":"en","incident_type":"HAZMAT","title":"Chlorine smell at the chemical store","description":"A chlorine smell near the chemical store. Workers moved away and the drum has been closed.","location":"chemical store","time":"just now","severity":"HIGH","injuries":"NO","actions_taken":["Drum closed"],"details":{"substance":"chlorine","release_status":"STOPPED","area_evacuated":"YES"}""")),
    )

    /** The 10 general demo reports. */
    suspend fun seed(reporter: Reporter, now: Long = System.currentTimeMillis()): Int = insert(demos, reporter, now)

    /** The 12 repeat / hotspot demo reports, oldest first so each one's tags are those it would have had on its day. */
    suspend fun seedHotspots(reporter: Reporter, now: Long = System.currentTimeMillis()): Int = insert(hotspotDemos.sortedByDescending { it.ageMin }, reporter, now)

    private suspend fun insert(list: List<Demo>, reporter: Reporter, now: Long): Int {
        var n = 0
        for (d in list) {
            val at = now - d.ageMin * 60_000
            var form = IncidentForm()
            val first = (parser.parse(d.json, Tools.FILL) as? ParseOutcome.Ok)?.call ?: continue
            form = merger.merge(form, first, FieldSource.LLM_EXTRACT, 0, d.transcript, Instant.ofEpochMilli(at)).form
            form = merger.applyTimeDefault(form, Instant.ofEpochMilli(at))
            d.patch?.let { p ->
                (parser.parse(p, Tools.UPDATE, form.types) as? ParseOutcome.Ok)?.call?.let { c ->
                    form = merger.merge(form, c, FieldSource.LLM_PATCH, 1, d.transcript, Instant.ofEpochMilli(at)).form
                }
            }
            form = Evidence.attach(form, listOf(d.transcript), cfg.taxonomy, Instant.ofEpochMilli(at), times)
            val id = repo.createDraft(reporter, at)
            repo.markDemo(id) // every seeded row is marked; "Clear demo data" deletes exactly these
            repo.appendTurn(id, TurnRecord("USER", "NARRATION", textRaw = d.transcript, lang = form.lang))
            repo.saveForm(id, form, Actor.Llm, emergency = form.str("severity") == "CRITICAL")
            repo.saveRecommendations(id, sop.recommend(form))
            repo.submit(id, Actor.System, at)
            n++
        }
        return n
    }
}
