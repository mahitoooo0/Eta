package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentCompressionPolicyTest {
    private val child = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test",
        model = "child", systemPrompt = "", contextWindow = 32000)

    @Test fun customSummaryInheritsFrozenParentReconnectPolicy() {
        val parent = child.copy(errorReconnectPolicy = "continuous")
        val summary = child.copy(model = "custom-summary", errorReconnectPolicy = "none")
        val policy = AgentCompressionPolicy.create(parent, summary, null, true)
        assertEquals("continuous", policy.compressModelConfig!!.errorReconnectPolicy)
        assertEquals("custom-summary", policy.compressModelConfig!!.model)
    }

    @Test fun defaultsOnForChildWhileExplicitPreferenceWins() {
        assertTrue(AgentCompressionPolicy.create(child, child, null, true).enabled)
        assertFalse(AgentCompressionPolicy.create(child, child, false, true).enabled)
        assertTrue(AgentCompressionPolicy.create(child, child, true, true).enabled)
        assertFalse(AgentCompressionPolicy.create(child, child, null, false).enabled)
    }

    @Test fun usesChildWindowAndPreservesSelectedSummaryModelConfiguration() {
        val summary = child.copy(model = "summary", contextWindow = 128000, openAiEndpointMode = "responses")
        val policy = AgentCompressionPolicy.create(child, summary, null, true)
        assertEquals(32000, policy.contextWindow)
        assertEquals(summary, policy.compressModelConfig)
        assertEquals(128000, policy.compressModelConfig!!.contextWindow)
    }

    @Test fun missingSummaryWindowInheritsChildButMissingChildWindowDoesNotInventLimit() {
        assertEquals(32000, AgentCompressionPolicy.create(child, child.copy(contextWindow = null), null, true)
            .compressModelConfig!!.contextWindow)
        assertFalse(AgentCompressionPolicy.create(child.copy(contextWindow = null), child, null, true).enabled)
        assertFalse(AgentCompressionPolicy.create(child.copy(contextWindow = 0), child, true, true).enabled)
    }
}
