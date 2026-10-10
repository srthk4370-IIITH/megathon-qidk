package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.llm.ParseFailure
import com.megathon.incidentvoice.core.llm.ParseResult
import com.megathon.incidentvoice.core.llm.ParsedCall
import com.megathon.incidentvoice.core.llm.PartialJson
import com.megathon.incidentvoice.core.llm.Tools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRD §13.6 — corpus of malformed / variant LLM outputs. */
class ToolCallParserTest {
    private val p = T.parser

    private fun ok(raw: String, tool: String = Tools.FILL, transcripts: List<String> = emptyList()): ParsedCall {
        val r = p.parse(raw, tool, transcripts)
        assertIs<ParseResult.Ok>(r, "expected Ok for: $raw → $r")
        return r.call
    }

    private fun ParsedCall.s(k: String): String? = (fields[k]?.value as? JsonPrimitive)?.content
    private fun ParsedCall.l(k: String): List<String>? = (fields[k]?.value as? JsonArray)?.map { (it as JsonPrimitive).content }

    /** (name, raw output, tool, check) */
    private data class Case(val name: String, val raw: String, val tool: String = Tools.FILL, val check: (ParsedCall) -> Unit)

    private val corpus: List<Case> = listOf(
        Case("clean slide", T.SLIDE_OUTPUT) { assertEquals("WATER", it.s("incident_type")); assertEquals("around 10 AM", it.s("occurred_at")); assertEquals("en", it.lang) },
        Case("code fence json", "```json\n${T.SLIDE_OUTPUT}\n```") { assertEquals("WATER", it.s("incident_type")) },
        Case("code fence plain + prose", "Here is the call:\n```\n${T.SLIDE_OUTPUT}\n```\nDone.") { assertEquals("near Conference Room 2", it.s("location")) },
        Case("leading prose", "Sure! " + T.SLIDE_OUTPUT) { assertEquals("WATER", it.s("incident_type")) },
        Case("python tag + eot", "<|python_tag|>" + T.SLIDE_OUTPUT + "<|eot_id|>") { assertEquals("WATER", it.s("incident_type")) },
        Case("trailing commas", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"Smoke in pantry",},}""") { assertEquals("FIRE", it.s("incident_type")) },
        Case("single quotes", """{'name': 'fill_incident_form', 'parameters': {'incident_type': 'WATER', 'description': 'Leak near the pantry'}}""") { assertEquals("Leak near the pantry", it.s("description")) },
        Case("single quotes with apostrophe", """{'name': 'fill_incident_form', 'parameters': {'incident_type': 'MEDICAL', 'description': 'Ramesh's hand got cut'}}""") { assertEquals("Ramesh's hand got cut", it.s("description")) },
        Case("python literals", """{"name":"fill_incident_form","parameters":{"incident_type":"STRUCTURAL","description":"Ceiling panel fell","near_miss":True,"title":None}}""") {
            assertEquals("true", it.s("near_miss")); assertNull(it.fields["title"])
        },
        Case("unquoted enum values", """{"name":"fill_incident_form","parameters":{"incident_type":WATER,"description":"Pipe burst","details":{"leak_status":ONGOING}}}""") {
            assertEquals("WATER", it.s("incident_type")); assertEquals("ONGOING", it.s("leak_status"))
        },
        Case("unquoted keys", """{name:"fill_incident_form",parameters:{incident_type:"FIRE",description:"Bin on fire"}}""") { assertEquals("FIRE", it.s("incident_type")) },
        Case("arguments alias", """{"name":"fill_incident_form","arguments":{"incident_type":"WATER","description":"Leak"}}""") { assertEquals("WATER", it.s("incident_type")) },
        Case("args alias", """{"name":"fill_incident_form","args":{"incident_type":"WATER","description":"Leak"}}""") { assertEquals("WATER", it.s("incident_type")) },
        Case("stringified arguments", """{"name":"fill_incident_form","arguments":"{\"incident_type\":\"FIRE\",\"description\":\"Fire\"}"}""") { assertEquals("FIRE", it.s("incident_type")) },
        Case("openai function envelope", """{"type":"function","function":{"name":"fill_incident_form","arguments":{"incident_type":"VEHICLE","description":"Forklift hit rack"}}}""") { assertEquals("VEHICLE", it.s("incident_type")) },
        Case("function key as name", """{"function":"fill_incident_form","parameters":{"incident_type":"OTHER","description":"Odd smell"}}""") { assertEquals("OTHER", it.s("incident_type")) },
        Case("bare parameters (no envelope)", """{"incident_type":"WATER","description":"Leak in washroom"}""") { assertEquals("WATER", it.s("incident_type")); assertTrue(it.issues.any { i -> i.code == "NO_TOOL_NAME" }) },
        Case("truncated mid-string", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"A water leak occurred near Confer""") {
            assertEquals("WATER", it.s("incident_type")); assertEquals("A water leak occurred near Confer", it.s("description")); assertTrue(it.truncated)
        },
        Case("truncated mid-key", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","descrip""") { assertEquals("FIRE", it.s("incident_type")); assertNull(it.fields["description"]) },
        Case("truncated after colon", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":""") { assertEquals("FIRE", it.s("incident_type")) },
        Case("truncated in array", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","actions_taken":["Area isolated","Mai""") {
            assertEquals(listOf("Area isolated", "Mai"), it.l("actions_taken"))
        },
        Case("truncated partial literal", """{"name":"fill_incident_form","parameters":{"incident_type":"STRUCTURAL","description":"x y","near_miss":tr""") { assertEquals("STRUCTURAL", it.s("incident_type")); assertNull(it.fields["near_miss"]) },
        Case("enum synonym under control", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"Fire","details":{"fire_status":"under control"}}}""") { assertEquals("CONTAINED", it.s("fire_status")) },
        Case("enum synonym put out", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"Fire","details":{"fire_status":"Put out"}}}""") { assertEquals("EXTINGUISHED", it.s("fire_status")) },
        Case("enum spaces to underscore", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"Fire","details":{"fire_status":"smoke only","evacuation_status":"in progress"}}}""") {
            assertEquals("SMOKE_ONLY", it.s("fire_status")); assertEquals("IN_PROGRESS", it.s("evacuation_status"))
        },
        Case("haan → YES, nahi → NO", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"Fire","details":{"fire_brigade_called":"haan","extinguisher_used":"nahi"}}}""") {
            assertEquals("YES", it.s("fire_brigade_called")); assertEquals("NO", it.s("extinguisher_used"))
        },
        Case("lower-case enum", """{"name":"fill_incident_form","parameters":{"incident_type":"water","description":"Leak","severity":"low","injuries":"no"}}""") {
            assertEquals("WATER", it.s("incident_type")); assertEquals("LOW", it.s("severity")); assertEquals("NO", it.s("injuries"))
        },
        Case("severity synonym minor", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","severity":"minor"}}""") { assertEquals("LOW", it.s("severity")) },
        Case("boolean as enum", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","injuries":false}}""") { assertEquals("NO", it.s("injuries")) },
        Case("invalid enum dropped", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","details":{"leak_status":"SPLASHING"}}}""") {
            assertNull(it.fields["leak_status"]); assertTrue(it.issues.any { i -> i.key == "leak_status" && i.code == "INVALID_VALUE" })
        },
        Case("unknown key dropped", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","weather":"rainy"}}""") {
            assertNull(it.fields["weather"]); assertTrue(it.issues.any { i -> i.key == "weather" && i.code == "UNKNOWN_KEY" })
        },
        Case("category field at top level routed", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","leak_status":"ongoing"}}""") { assertEquals("ONGOING", it.s("leak_status")) },
        Case("junk strings dropped", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","location":"unknown","title":"N/A","details":{"leak_source":"not mentioned"}}}""") {
            assertNull(it.fields["location"]); assertNull(it.fields["title"]); assertNull(it.fields["leak_source"])
        },
        Case("whitespace collapsed", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"  Leak   near\n pantry  "}}""") { assertEquals("Leak near pantry", it.s("description")) },
        Case("number words EN", """{"name":"fill_incident_form","parameters":{"incident_type":"MEDICAL","description":"x","injured_count":"two people"}}""") { assertEquals("2", it.s("injured_count")) },
        Case("number words Hinglish", """{"name":"fill_incident_form","parameters":{"incident_type":"MEDICAL","description":"x","injured_count":"teen"}}""") { assertEquals("3", it.s("injured_count")) },
        Case("number as string digits", """{"name":"fill_incident_form","parameters":{"incident_type":"MEDICAL","description":"x","injured_count":"4"}}""") { assertEquals("4", it.s("injured_count")) },
        Case("single string → list + dedupe", """{"name":"fill_incident_form","parameters":{"incident_type":"EQUIPMENT","description":"x","equipment":"Pump P-101","actions_taken":["Area isolated","area isolated"]}}""") {
            assertEquals(listOf("Pump P-101"), it.l("equipment")); assertEquals(listOf("Area isolated"), it.l("actions_taken"))
        },
        Case("secondary types normalised, primary removed, max 2", """{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","secondary_types":["fire","electrical","medical","water"],"description":"x"}}""") {
            assertEquals(listOf("ELECTRICAL", "MEDICAL"), it.l("secondary_types"))
        },
        Case("persons strings and roles", """{"name":"fill_incident_form","parameters":{"incident_type":"MEDICAL","description":"x","persons":["Ramesh",{"name":"Sita","role":"victim","injured":"yes"}]}}""") {
            val ps = it.fields["persons"]!!.value as JsonArray
            assertEquals("Ramesh", ((ps[0] as JsonObject)["name"] as JsonPrimitive).content)
            assertEquals("AFFECTED", ((ps[1] as JsonObject)["role"] as JsonPrimitive).content)
            assertEquals("true", ((ps[1] as JsonObject)["injured"] as JsonPrimitive).content)
        },
        Case("lang names", """{"name":"fill_incident_form","parameters":{"lang":"Hinglish","incident_type":"WATER","description":"x"}}""") { assertEquals("hinglish", it.lang) },
        Case("missing comma between members", """{"name":"fill_incident_form" "parameters":{"incident_type":"WATER" "description":"Leak"}}""") { assertEquals("Leak", it.s("description")) },
        Case("two objects → first", T.SLIDE_OUTPUT + "\n{\"name\":\"fill_incident_form\",\"parameters\":{\"incident_type\":\"FIRE\"}}") { assertEquals("WATER", it.s("incident_type")) },
        Case("null values ignored", """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak","severity":null}}""") { assertNull(it.fields["severity"]) },
        // patch
        Case("patch set", """{"name":"update_incident_fields","parameters":{"set":{"injuries":"NO","severity":"LOW"}}}""", Tools.UPDATE) {
            assertEquals("NO", it.s("injuries")); assertEquals("LOW", it.s("severity"))
        },
        Case("patch unknown + time key", """{"name":"update_incident_fields","parameters":{"set":{"time":"around 9"},"unknown":["leak_source","bogus"]}}""", Tools.UPDATE) {
            assertEquals("around 9", it.s("occurred_at")); assertEquals(listOf("leak_source"), it.unknown)
        },
        Case("patch pata nahi in set → unknown", """{"name":"update_incident_fields","parameters":{"set":{"leak_source":"pata nahi","leak_status":"stopped"}}}""", Tools.UPDATE) {
            assertEquals(listOf("leak_source"), it.unknown); assertEquals("STOPPED", it.s("leak_status"))
        },
        Case("patch skipped", """{"name":"update_incident_fields","parameters":{"set":{},"skipped":true}}""", Tools.UPDATE) { assertTrue(it.skipped) },
        Case("patch loose keys outside set", """{"name":"update_incident_fields","parameters":{"injuries":"haan"}}""", Tools.UPDATE) { assertEquals("YES", it.s("injuries")) },
        // propose
        Case("propose actions", """{"name":"propose_actions","parameters":{"actions":[{"sop":"water-01","text":"Close the valve near CR2"},{"sop":"CUSTOM","text":"Inform IT"},{"sop":"","text":"bad"}]}}""", Tools.PROPOSE) {
            assertEquals(listOf("WATER-01", "CUSTOM"), it.actions.map { a -> a.sop })
        },
    )

    @TestFactory
    fun corpus(): List<DynamicTest> = corpus.map { c ->
        DynamicTest.dynamicTest(c.name) { c.check(ok(c.raw, c.tool)) }
    }

    @Test fun corpusSize() = assertTrue(corpus.size >= 30)

    @Test fun wrongToolName() {
        val r = p.parse("""{"name":"propose_actions","parameters":{"actions":[]}}""", Tools.FILL)
        assertIs<ParseResult.Failed>(r)
        assertEquals(ParseFailure.WRONG_TOOL, r.reason)
    }

    @Test fun emptyOutputIsEngineErrorNotContent() {
        val r = p.parse("   ", Tools.FILL)
        assertEquals(ParseFailure.EMPTY_OUTPUT, (r as ParseResult.Failed).reason)
    }

    @Test fun noJson() {
        val r = p.parse("I am sorry, I cannot help with that.", Tools.FILL)
        assertEquals(ParseFailure.NO_JSON, (r as ParseResult.Failed).reason)
    }

    @Test fun groundingDropsInventedNamesAndInfersStrings() {
        val raw = """{"name":"fill_incident_form","parameters":{"incident_type":"VEHICLE","description":"x","location":"Bay 4",
            "persons":[{"name":"Suresh","role":"INVOLVED"}],"details":{"driver_name":"Ramesh","vehicles_involved":["forklift"],"damage_type":"rack bent"}}}"""
        val tr = listOf("bay 4 mein forklift ne rack ko thok diya, driver ramesh tha")
        val c = ok(raw, transcripts = tr)
        assertEquals("Ramesh", c.s("driver_name"))
        val persons = c.fields["persons"]!!.value as JsonArray
        assertNull((persons[0] as JsonObject)["name"], "invented name must be dropped")
        assertTrue(c.fields["location"]!!.grounded)
        assertTrue(c.fields["damage_type"]!!.grounded) // "rack" appears
        val raw2 = """{"name":"fill_incident_form","parameters":{"incident_type":"VEHICLE","description":"x","details":{"driver_name":"Mahesh"},"equipment":["crane"]}}"""
        val c2 = ok(raw2, transcripts = tr)
        assertNull(c2.fields["driver_name"])
        assertFalse(c2.fields["equipment"]!!.grounded)
    }

    @Test fun groundingUsesHinglishLexicon() {
        val raw = """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"x","location":"near the stairs","details":{"leak_source":"water tank"}}}"""
        val c = ok(raw, transcripts = listOf("seedhi ke paas paani ki tanki se leak ho raha hai"))
        assertTrue(c.fields["location"]!!.grounded)
        assertTrue(c.fields["leak_source"]!!.grounded)
    }

    @Test fun streamingPartialParse() {
        val full = T.SLIDE_OUTPUT
        var lastFields = 0
        for (n in listOf(30, 60, 90, 140, 200, full.length)) {
            val pc = p.parsePartial(full.take(n), Tools.FILL)
            if (pc != null) {
                assertTrue(pc.fields.size >= lastFields - 1)
                lastFields = pc.fields.size
            }
        }
        assertNotNull(p.parsePartial(full.take(120), Tools.FILL)?.fields?.get("incident_type"))
    }

    @Test fun autoCloseShapes() {
        assertEquals("""{"a":"b"}""", PartialJson.autoClose("""{"a":"b"""))
        assertEquals("""{"a":1}""", PartialJson.autoClose("""{"a":1,"""))
        assertEquals("""{}""", PartialJson.autoClose("""{"a":"""))
        assertEquals("""{"a":{"b":[1,2]}}""", PartialJson.autoClose("""{"a":{"b":[1,2"""))
        assertEquals("""{"a":{}}""", PartialJson.autoClose("""{"a":{"b""""))
    }
}
