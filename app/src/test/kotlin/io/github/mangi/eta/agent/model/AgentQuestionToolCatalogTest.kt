package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuestionToolCatalogTest {
    @Test
    fun askUserIsPublishedByDefaultWithoutAnySetting() {
        val base = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        assertTrue("ask_user" in base.toolNames())
        assertTrue(AgentQuestionToolCatalog.NAME in AgentToolRequirements.toolNames)
        assertEquals(RootRequirement.NONE, AgentToolRequirements.find(AgentQuestionToolCatalog.NAME)?.rootRequirement)
        assertNull(AgentToolCapabilities(rootAvailable = false).unavailableCode(AgentQuestionToolCatalog.NAME))
    }

    @Test
    fun askUserStaysVisibleWithoutRootOrOrdinaryPermissions() {
        val restricted = AgentToolCapabilities(
            rootAvailable = false,
            accessibilityAvailable = false,
            notificationsAllowed = false,
            usageAllowed = false,
            locationAllowed = false,
            colorOs = false,
        )
        val catalog = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            capabilities = restricted,
        )

        assertTrue(AgentQuestionToolCatalog.NAME in catalog.toolNames())
    }

    @Test
    fun schemaDeclaresBoundedSingleQuestionContract() {
        val function = AgentToolCatalog.build(terminalTools = false, browserTools = false)
            .function(AgentQuestionToolCatalog.NAME)
        val parameters = function.getJSONObject("parameters")

        assertEquals("object", parameters.getString("type"))
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals(listOf("title", "question", "options"), parameters.getJSONArray("required").stringList())

        val properties = parameters.getJSONObject("properties")
        assertEquals(1, properties.getJSONObject("title").getInt("minLength"))
        assertEquals(200, properties.getJSONObject("title").getInt("maxLength"))
        assertEquals(1, properties.getJSONObject("question").getInt("minLength"))
        assertEquals(4_000, properties.getJSONObject("question").getInt("maxLength"))

        val options = properties.getJSONObject("options")
        assertEquals("array", options.getString("type"))
        assertEquals(2, options.getInt("minItems"))
        assertEquals(8, options.getInt("maxItems"))
        val item = options.getJSONObject("items")
        assertEquals("object", item.getString("type"))
        assertFalse(item.getBoolean("additionalProperties"))
        assertEquals(listOf("id", "label"), item.getJSONArray("required").stringList())
        assertEquals(64, item.getJSONObject("properties").getJSONObject("id").getInt("maxLength"))
        assertEquals(200, item.getJSONObject("properties").getJSONObject("label").getInt("maxLength"))
        assertEquals(1_000, item.getJSONObject("properties").getJSONObject("description").getInt("maxLength"))

        assertEquals("boolean", properties.getJSONObject("allow_other").getString("type"))
        assertEquals("boolean", properties.getJSONObject("allow_delegation").getString("type"))
        assertEquals("boolean", properties.getJSONObject("allow_note").getString("type"))
        assertEquals(64, properties.getJSONObject("recommended_option_id").getInt("maxLength"))
    }

    @Test
    fun mainPromptCarriesQuestionDiscipline() {
        val contents = AgentPromptBuilder.buildSystemMessages(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                terminalTools = false,
                browserTools = false,
            ),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
        ).contents()

        val guidance = contents.single { it.contains(AgentQuestionToolCatalog.NAME) }
        assertTrue(guidance.contains("单独成批"))
        assertTrue(guidance.contains("不自动替用户选择"))
        assertTrue(guidance.contains("你看着办"))
        assertTrue(guidance.contains("子代理不能直接向你提问"))
    }

    private fun JSONArray.toolNames(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.function(name: String): JSONObject = (0 until length())
        .map { getJSONObject(it).getJSONObject("function") }
        .single { it.getString("name") == name }

    private fun JSONArray.contents(): List<String> = (0 until length()).map { getJSONObject(it).getString("content") }

    private fun JSONArray.stringList(): List<String> = (0 until length()).map { getString(it) }
}
