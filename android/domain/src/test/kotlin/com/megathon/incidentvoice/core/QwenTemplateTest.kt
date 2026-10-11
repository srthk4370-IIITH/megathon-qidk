package com.megathon.incidentvoice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QwenTemplateTest {
    private val qwen = PromptBuilder(Fx.tx, Fx.templates, "base") { ModelFamily.QWEN }
    private val llama = PromptBuilder(Fx.tx, Fx.templates, "base")

    @Test fun family_isDetectedFromTheBundle() {
        assertEquals(ModelFamily.QWEN, ModelFamily.detect(null, """{"dialog":{"engine":{"model":{"binary":{"ctx-bins":["qwen3_4b_part_1_of_4.bin"]}}}}}"""))
        assertEquals(ModelFamily.QWEN, ModelFamily.detect("""{"family":"qwen","name":"Qwen3-4B"}""", "{}"))
        assertEquals(ModelFamily.LLAMA, ModelFamily.detect(null, """{"ctx-bins":["llama_v3_2_3b_instruct_part_1_of_3.bin"]}"""))
        assertEquals(ModelFamily.LLAMA, ModelFamily.detect(null, null))
        assertEquals(ModelFamily.QWEN, ModelFamily.detect(null, """{"dialog":{"context":{"size":4096,"eos-token": 151645},"ctx-bins":["part1_of_4.bin"]}}"""))
    }

    @Test fun chatMl_wrapsMessages_endsWithEmptyThinkBlock_noBos() {
        val p = qwen.chat("SYS", emptyList(), "USER")
        assertEquals("<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nUSER<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", p)
        assertFalse(p.contains("begin_of_text")); assertFalse(p.contains("start_header_id"))
        // the Llama path is unchanged
        assertTrue(llama.chat("SYS", emptyList(), "USER").startsWith("<|start_header_id|>system<|end_header_id|>\n\nSYS<|eot_id|>"))
    }

    @Test fun extractPrompt_isCompact_hasBothExamples_andEveryTaskUsesChatMl() {
        val p = qwen.extract("There was a water leak near Conference Room 2 around 10 AM.")
        assertTrue(p.startsWith("<|im_start|>system\nYou extract one workplace incident report"))
        assertTrue(p.contains("\"incident_type\":\"FIRE\"") && p.contains("\"lang\":\"hinglish\""))
        assertTrue(p.contains("Transcript:\n<<<\nThere was a water leak near Conference Room 2 around 10 AM.\n>>>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        val systemChars = p.substringAfter("<|im_start|>system\n").substringBefore("<|im_end|>").length
        assertTrue(systemChars < 3700, "system prompt is $systemChars characters (about ${systemChars / 4} tokens); keep it near 800 tokens")
        for (other in listOf(qwen.patch(Fx.form("incident_type" to "WATER"), "Was anyone hurt?", listOf("injuries"), "no"), qwen.repair(Tools.FILL, "{broken"), qwen.warmup()))
            assertTrue(other.contains("<|im_start|>user\n") && other.endsWith("</think>\n\n") && !other.contains("start_header_id"))
        // both examples in the prompt are themselves valid tool calls
        val examples = Fx.templates.extractSystemQwen!!.lines().filter { it.startsWith("{\"name\":\"fill_incident_form\"") }
        assertEquals(2, examples.size)
        for (e in examples) assertTrue(Fx.parser.parse(e, Tools.FILL) is ParseOutcome.Ok, e)
    }

    @Test fun modelOutput_thinkBlocksAndStopTokensAreRemoved_firstBalancedObjectIsTaken() {
        val call = """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak near the pantry"}}"""
        for (raw in listOf(
            call,
            "<think>\nThe user says {something} about a leak.\n</think>\n\n$call<|im_end|>",
            "$call<|im_end|>\n<|im_start|>user\nmore",
            "Sure, here it is:\n$call\nAnything else? {\"x\":1}",
        )) {
            val out = Fx.parser.parse(raw, Tools.FILL)
            assertTrue(out is ParseOutcome.Ok, raw)
            assertEquals("WATER", ((out as ParseOutcome.Ok).call.fields["incident_type"] as kotlinx.serialization.json.JsonPrimitive).content)
        }
        assertEquals("", ModelOutput.clean("<think>still thinking about {braces"))
        assertTrue(Fx.parser.parse("<think>never finished {\"name\":\"fill_incident_form\"", Tools.FILL) is ParseOutcome.Fail)
    }
}
