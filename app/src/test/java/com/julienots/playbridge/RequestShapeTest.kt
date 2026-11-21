package com.julienots.playbridge

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessageParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vérifie la forme JSON de la requête envoyée à l'API (sans appel réseau). */
class RequestShapeTest {
    @Test
    fun requestBody() {
        val params = ClaudeAgent.buildParams(
            ClaudeAgent.Config("x", "claude-opus-5-5", "medium"),
            BrowserTools.DEFINITIONS,
            listOf(BetaMessageParam.ofUser("Bonjour")),
        )
        val body = jsonMapper().readTree(jsonMapper().writeValueAsString(params._body()))
        val extra = params._additionalBodyProperties()
        println(body.toPrettyString().take(3000))
        println("extra=" + extra + " headers=" + params._headers().values("anthropic-beta"))
        assertEquals("claude-opus-5-5", body["model"].asText())
        assertEquals("adaptive", body["thinking"]["type"].asText())
        assertEquals("medium", body["output_config"]["effort"].asText())
        assertEquals(11, body["tools"].size())
        assertEquals("clear_tool_uses_20250919", body["context_management"]["edits"][0]["type"].asText())
        assertTrue(extra.containsKey("fallbacks"))
    }
}
