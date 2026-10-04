package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ask_user 只属于主代理；子代理白名单必须始终排除它。 */
class AgentQuestionChildExclusionTest {
    @Test
    fun childWhitelistNeverAllowsAskUser() {
        assertFalse(SubAgentTools.allows("ask_user"))

        val catalog = AgentToolCatalog.build(terminalTools = true, browserTools = true)
        assertTrue("ask_user" in catalog.names())
        assertFalse("ask_user" in SubAgentTools.filter(catalog).names())
    }

    @Test
    fun guardedChildExecutorRefusesAskUser() {
        var calls = 0
        val guarded = SubAgentTools.guarded {
            calls++
            AgentModelClient.ToolResult("{}")
        }

        val result = guarded.execute(AgentModelClient.ToolCall("id", "ask_user", "{}"))

        assertTrue(result.content.contains("SUB_AGENT_READ_ONLY"))
        assertEquals(0, calls)
    }

    @Test
    fun workspaceChildCatalogNeverExposesAskUser() {
        assertFalse("ask_user" in SubAgentWorkspace.childTools(writable = true).names())
        assertFalse("ask_user" in SubAgentWorkspace.childTools(writable = false).names())
    }

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }
}
