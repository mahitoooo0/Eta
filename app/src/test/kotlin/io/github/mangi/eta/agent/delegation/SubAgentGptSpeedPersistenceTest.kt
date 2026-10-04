package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentGptSpeedPersistenceTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("speed-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun profile() = SubAgentProfile("worker", "Worker", providerId = "p", modelId = "m",
        reasoning = ReasoningEffort.HIGH,
        reasoningByModel = mapOf("p\u0000m" to ReasoningEffort.HIGH),
        gptSpeedByModel = mapOf("p\u0000m" to GptSpeedMode.FAST, "p\u0000m2" to GptSpeedMode.ULTRA_FAST,
            "p2\u0000m" to GptSpeedMode.NORMAL))

    @Test fun profileRoundTripIsPerProviderAndModelAndOldRecordsDefaultToNormal() {
        val original = profile()
        val restored = SubAgentProfile.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, restored)
        assertEquals(GptSpeedMode.FAST, restored.gptSpeedForModel())
        assertEquals(GptSpeedMode.ULTRA_FAST, restored.gptSpeedForModel("p", "m2"))
        assertEquals(GptSpeedMode.NORMAL, restored.gptSpeedForModel("p2", "m"))
        assertEquals(GptSpeedMode.NORMAL, restored.gptSpeedForModel("missing", "m"))
        val legacy = original.toJson().also { it.remove("gpt_speed_memory") }
        val old = SubAgentProfile.fromJson(legacy)
        assertTrue(old.gptSpeedByModel.isEmpty())
        assertEquals(GptSpeedMode.NORMAL, old.gptSpeedForModel())
        assertEquals(original.reasoningByModel, old.reasoningByModel)
    }

    @Test fun tolerantLegacyReaderDropsMalformedSpeedEntriesWithoutLosingReasoning() {
        val malformed = JSONArray().put(42)
            .put(JSONObject().put("provider", "p").put("model", "m").put("speed", "TURBO"))
            .put(JSONObject().put("provider", 2).put("model", "m").put("speed", "FAST"))
            .put(JSONObject().put("provider", "p").put("model", "m\u0000bad").put("speed", "FAST"))
        val restored = SubAgentProfile.fromJson(profile().toJson().put("gpt_speed_memory", malformed))
        assertTrue(restored.gptSpeedByModel.isEmpty())
        assertEquals(ReasoningEffort.HIGH, restored.reasoning)
        assertTrue(SubAgentProfile.fromJson(profile().toJson().put("gpt_speed_memory", "bad")).gptSpeedByModel.isEmpty())
    }

    @Test fun globalSeedDraftBindingConversationAndArchiveRetainIndependentDetachedMaps() {
        val storage = prefs()
        storage.edit().putString(SubAgentPreferences.PROFILES_KEY, JSONObject().put("version", 1)
            .put("agents", JSONArray().put(profile().toJson())).toString()).commit()
        val repo = ConversationSubAgentPreferences(storage)
        val draft = repo.createDraft()
        val a = SubAgentConfigKey.Conversation("a")
        val b = SubAgentConfigKey.Conversation("b")
        repo.bindDraft(draft, a)
        repo.confirmBoundDraft(draft, a)
        repo.createConversation(b, a)
        val memory = profile().gptSpeedByModel.toMutableMap()
        val saved = repo.update(a) { it.copy(profiles = listOf(profile().copy(gptSpeedByModel = memory))) }
            as ConversationSubAgentPreferences.WriteResult.Saved
        memory.clear()
        assertEquals(profile(), saved.config.profiles.single())
        repo.update(a) { it.copy(profiles = listOf(profile().copy(gptSpeedByModel = emptyMap()))) }
        assertEquals(profile(), repo.snapshot(b).profiles.single())
        assertEquals(GptSpeedMode.NORMAL, repo.snapshot(a).profiles.single().gptSpeedForModel())
        val restored = ConversationSubAgentPreferences(prefs())
        restored.importOwner(b, repo.export(b))
        restored.validateRestoredPreferences()
        assertEquals(repo.snapshot(b), restored.snapshot(b))
        val source = profile().gptSpeedByModel.toMutableMap()
        val detached = ConversationSubAgentConfig(listOf(profile().copy(gptSpeedByModel = source))).detached()
        source.clear()
        assertEquals(profile(), detached.profiles.single())
    }

    @Test fun globalPreferencesPersistMemoryAcrossModelSwitchesAndReloads() {
        io.github.mangi.eta.config.Prefs.initLocal(RuntimeEnvironment.getApplication())
        val before = io.github.mangi.eta.config.Prefs.getString(SubAgentPreferences.PROFILES_KEY)
        try {
            io.github.mangi.eta.config.Prefs.putString(SubAgentPreferences.PROFILES_KEY,
                JSONObject().put("version", 1).put("agents", JSONArray().put(profile().toJson())).toString())
            SubAgentPreferences.saveModel("worker", io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "p", "non-gpt"))
            assertEquals(GptSpeedMode.NORMAL, SubAgentPreferences.profiles().single().gptSpeedForModel())
            SubAgentPreferences.saveModel("worker", io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "p", "m"))
            assertEquals(profile(), SubAgentPreferences.profiles().single())
        } finally {
            io.github.mangi.eta.config.Prefs.putString(SubAgentPreferences.PROFILES_KEY, before)
        }
    }

    @Test fun strictArchivesAcceptMissingMemoryButRejectBadTypesDuplicatesAndEnumsWithoutOverwrite() {
        val repo = ConversationSubAgentPreferences(prefs())
        val owner = SubAgentConfigKey.Conversation("archive")
        repo.update(owner) { it.copy(profiles = listOf(profile())) }
        val before = repo.export(owner)
        val legacy = JSONObject(before).also { it.getJSONArray("agents").getJSONObject(0).remove("gpt_speed_memory") }
        repo.validateArchive(legacy.toString())
        val valid = JSONObject().put("provider", "p").put("model", "m").put("speed", "FAST")
        val broken: List<Any> = listOf("bad", JSONObject.NULL, JSONArray().put(3),
            JSONArray().put(valid).put(valid),
            JSONArray().put(JSONObject(valid.toString()).put("speed", "TURBO")),
            JSONArray().put(JSONObject(valid.toString()).put("speed", 1)),
            JSONArray().put(JSONObject(valid.toString()).put("provider", "")),
            JSONArray().put(JSONObject(valid.toString()).put("model", "m\u0000bad")))
        broken.forEach { value ->
            val archive = JSONObject(before).also { it.getJSONArray("agents").getJSONObject(0).put("gpt_speed_memory", value) }
            assertThrows(Exception::class.java) { repo.importOwner(owner, archive.toString(), overwrite = true) }
            assertEquals(before, repo.export(owner))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.update(owner) { it.copy(profiles = listOf(profile().copy(gptSpeedByModel = mapOf("bad" to GptSpeedMode.FAST)))) }
        }
        assertEquals(before, repo.export(owner))
    }
}
