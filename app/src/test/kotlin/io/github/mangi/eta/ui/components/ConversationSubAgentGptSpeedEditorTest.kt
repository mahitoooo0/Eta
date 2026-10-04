package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConversationSubAgentGptSpeedEditorTest {
    private val profile = SubAgentProfile("worker", "Worker", providerId = "p", modelId = "m", reasoning = ReasoningEffort.HIGH)
    private val provider = OpenAiCompatibleProviderSetting("p", "Provider", "https://example.invalid", models = listOf(
        Model("m", "gpt-5", "GPT"), Model("m2", "gpt-6", "GPT 2"), Model("other", "claude-sonnet", "GPT label")))
    private fun repository() = ConversationSubAgentPreferences(RuntimeEnvironment.getApplication()
        .getSharedPreferences("speed-editor-${UUID.randomUUID()}", Context.MODE_PRIVATE))

    @Test fun customResponsesProviderCanPersistSpeed() = runBlocking {
        val repo = repository()
        val owner = repo.createDraft()
        repo.update(owner) { it.copy(profiles = listOf(profile)) }
        val custom = CustomProviderSetting("p", "GPT", "https://example.invalid",
            endpointMode = OpenAiEndpointMode.RESPONSES, models = listOf(Model("m", "gpt-6-astra", "GPT")))
        val editor = ConversationSubAgentEditor(owner, repo, { custom }) { true }
        for (mode in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL)) {
            val result = editor.cycleGptSpeed(profile.id, "p", "m")
            assertTrue(result is ConversationSubAgentPreferences.WriteResult.Saved)
            assertEquals(mode, repo.snapshot(owner).profiles.single().gptSpeedForModel())
        }
    }

    @Test fun cyclesAreIndependentAcrossModelsProvidersWorkersAndConversations() = runBlocking {
        val repo = repository()
        val owner = SubAgentConfigKey.Conversation("a")
        val other = SubAgentConfigKey.Conversation("b")
        repo.update(owner) { it.copy(profiles = listOf(profile, profile.copy(id = "worker2"))) }
        repo.createConversation(other, owner)
        val providers = listOf(provider, provider.copy(id = "p2"))
        val editor = ConversationSubAgentEditor(owner, repo, { id -> providers.singleOrNull { it.id == id } }) { true }
        fun current() = repo.snapshot(owner).profiles.first()
        suspend fun cycle() = editor.cycleGptSpeed(profile.id, current().providerId, current().modelId, current())
        fun select(p: String, m: String) { assertTrue(editor.saveModel(profile.id, ModelFeatureSelection(true, p, m)) is ConversationSubAgentPreferences.WriteResult.Saved) }
        listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL, GptSpeedMode.FAST).forEach { speed ->
            val result = cycle() as ConversationSubAgentPreferences.WriteResult.Saved
            assertEquals(speed, result.config.profiles.first().gptSpeedForModel())
            assertEquals(ReasoningEffort.HIGH, current().reasoning)
        }
        select("p", "m2")
        assertEquals(GptSpeedMode.NORMAL, current().gptSpeedForModel())
        cycle(); cycle()
        select("p2", "m")
        assertEquals(GptSpeedMode.NORMAL, current().gptSpeedForModel())
        select("p", "other")
        val before = repo.export(owner)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, cycle())
        assertEquals(before, repo.export(owner))
        select("p", "m")
        assertEquals(GptSpeedMode.FAST, current().gptSpeedForModel())
        assertEquals(ReasoningEffort.HIGH, current().reasoning)
        select("p", "m2")
        assertEquals(GptSpeedMode.ULTRA_FAST, current().gptSpeedForModel())
        assertTrue(repo.snapshot(owner).profiles.last().gptSpeedByModel.isEmpty())
        assertTrue(repo.snapshot(other).profiles.all { it.gptSpeedByModel.isEmpty() })
    }

    @Test fun staleBindingExpectedSnapshotLostOwnerAndRemovedProfileNeverWrite() = runBlocking {
        val repo = repository()
        val owner = repo.createDraft()
        repo.update(owner) { it.copy(profiles = listOf(profile)) }
        var editable = true
        var loseOwnerDuringLookup = false
        val editor = ConversationSubAgentEditor(owner, repo, {
            if (loseOwnerDuringLookup) editable = false
            provider
        }) { editable }
        suspend fun rejected(expected: SubAgentProfile? = null, modelId: String = "m") {
            val before = repo.export(owner)
            val version = repo.revision(owner).value
            assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected,
                editor.cycleGptSpeed(profile.id, "p", modelId, expected))
            assertEquals(before, repo.export(owner))
            assertEquals(version, repo.revision(owner).value)
        }
        rejected(modelId = "m2")
        editor.cycleGptSpeed(profile.id, "p", "m", profile)
        rejected(expected = profile)
        loseOwnerDuringLookup = true
        rejected()
        editable = true; loseOwnerDuringLookup = false
        editor.remove(profile.id)
        rejected()
    }

    @Test fun suspendedLookupRejectsModelChangeAndLostOwnerWithoutExpectedSnapshot() = runBlocking {
        for (changeModel in listOf(true, false)) {
            val repo = repository()
            val owner = repo.createDraft()
            repo.update(owner) { it.copy(profiles = listOf(profile)) }
            var editable = true
            val lookup = CompletableDeferred<ProviderSetting?>()
            val editor = ConversationSubAgentEditor(owner, repo, { lookup.await() }) { editable }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                editor.cycleGptSpeed(profile.id, "p", "m")
            }
            assertFalse(pending.isCompleted)
            if (changeModel) {
                assertTrue(editor.saveModel(profile.id, ModelFeatureSelection(true, "p", "m2"))
                    is ConversationSubAgentPreferences.WriteResult.Saved)
            } else editable = false
            val before = repo.export(owner)
            val revision = repo.revision(owner).value
            lookup.complete(provider)
            assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, pending.await())
            assertEquals(before, repo.export(owner))
            assertEquals(revision, repo.revision(owner).value)
        }
    }

    @Test fun qualificationComesFromCurrentProviderNotDisplayNameOrSavedMemory() = runBlocking {
        val repo = repository()
        val owner = repo.createDraft()
        repo.update(owner) { it.copy(profiles = listOf(profile)) }
        var source: ProviderSetting? = provider
        val editor = ConversationSubAgentEditor(owner, repo, { source }) { true }
        val invalid = listOf(null, provider.copy(isEnabled = false), provider.copy(models = listOf(provider.models.first().copy(isEnabled = false))),
            provider.copy(models = listOf(provider.models.first().copy(modelId = "claude-sonnet", displayName = "gpt-5"))))
        invalid.forEach {
            source = it
            val before = repo.export(owner)
            assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, editor.cycleGptSpeed(profile.id, "p", "m"))
            assertEquals(before, repo.export(owner))
        }
        source = provider
        repo.update(owner) { it.copy(profiles = listOf(profile.copy(role = "image_generation"))) }
        assertTrue(editor.cycleGptSpeed(profile.id, "p", "m") is ConversationSubAgentPreferences.WriteResult.Saved)
    }
}
