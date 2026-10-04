package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.ChildTaskConfigPolicy as Policy
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChildWorkerConfigResolverTest {
    private val owner = "conversation-a"
    private val profile = SubAgentProfile("worker-a", "Worker A", role = "review",
        providerId = "provider-a", modelId = "selection-old")
    private val provider = OpenAiCompatibleProviderSetting("provider-a", "Provider A", "https://example.invalid/v1",
        apiKey = "test-private-credential", models = listOf(
            Model("selection-old", "api-old", "Old"),
            Model("selection-new", "api-new", "New"),
        ))
    private fun model(selected: SubAgentProfile, source: OpenAiCompatibleProviderSetting = provider) =
        RuntimeConfigRepository.buildRuntimeConfig(source, source.models.single { it.id == selected.modelId })
    private suspend fun resolve(
        selected: SubAgentProfile = profile,
        config: ConversationSubAgentConfig = ConversationSubAgentConfig(listOf(selected)),
        source: OpenAiCompatibleProviderSetting = provider,
        build: suspend (SubAgentProfile) -> AgentModelClient.ModelConfig? = { model(it, source) },
    ) = ChildWorkerConfigResolver.resolveWorker(owner, config, selected.id, selected.role,
        providerLookup = { id -> source.takeIf { it.id == id } }, modelResolver = build)

    @Test fun childSnapshotInheritsParentsUnifiedReconnectPolicy() = runBlocking {
        val parent = model(profile).copy(errorReconnectPolicy = "continuous")
        val result = ChildWorkerConfigResolver.resolveWorker(owner, ConversationSubAgentConfig(listOf(profile)),
            profile.id, profile.role, providerLookup = { provider }, modelResolver = { model(it) }, parentConfig = parent)
        assertEquals("continuous", result.configuration?.model?.errorReconnectPolicy)
    }

    @Test fun selectedModelResolvedExactlyAndNeverReplacedByFirstModel() = runBlocking {
        val selected = profile.copy(modelId = "selection-new")
        val result = resolve(selected)
        assertEquals(Policy.Availability.AVAILABLE, result.availability)
        assertEquals("api-new", result.configuration?.model?.model)
        assertEquals("selection-new", result.configuration?.profile?.modelId)
        assertEquals(profile.id, result.worker.workerId)
    }

    @Test fun missingDisabledAndIncompatibleSelectionsReturnSpecificReasons() = runBlocking {
        assertEquals(Policy.Availability.SELECTION_MISSING, resolve(profile.copy(modelId = "")).availability)
        assertEquals(Policy.Availability.PROVIDER_UNAVAILABLE, resolve(profile.copy(providerId = "missing")).availability)
        assertEquals(Policy.Availability.PROVIDER_UNAVAILABLE, resolve(source = provider.copy(isEnabled = false)).availability)
        assertEquals(Policy.Availability.MODEL_UNAVAILABLE, resolve(profile.copy(modelId = "missing")).availability)
        val disabled = provider.copy(models = provider.models.map { it.copy(isEnabled = false) })
        assertEquals(Policy.Availability.MODEL_UNAVAILABLE, resolve(source = disabled).availability)
        assertEquals(Policy.Availability.ROLE_INCOMPATIBLE, resolve(profile.copy(role = "image_generation")).availability)
    }

    @Test fun disabledDelegationWorkerAndRemovedOriginalWorkerDoNotSelectAlternatives() = runBlocking {
        assertEquals(Policy.Availability.DELEGATION_DISABLED,
            resolve(config = ConversationSubAgentConfig(listOf(profile), enabled = false)).availability)
        assertEquals(Policy.Availability.WORKER_DISABLED, resolve(profile.copy(enabled = false)).availability)
        val otherWorker = profile.copy(id = "worker-b")
        assertEquals(Policy.Availability.WORKER_REMOVED,
            resolve(config = ConversationSubAgentConfig(listOf(otherWorker))).availability)
        assertEquals(Policy.Availability.INVALID_CONFIGURATION,
            resolve(config = ConversationSubAgentConfig(listOf(profile, profile))).availability)
    }

    @Test fun everyConfiguredWorkerHasAnAvailabilityRecordIncludingDisabledOnes() = runBlocking {
        val profiles = listOf(profile, profile.copy(id = "worker-b", enabled = false),
            profile.copy(id = "worker-c", modelId = "missing"))
        val result = ChildWorkerConfigResolver.resolve(owner, ConversationSubAgentConfig(profiles),
            providerLookup = { provider }, modelResolver = { model(it) })
        assertEquals(profiles.map { it.id }, result.map { it.worker.workerId })
        assertEquals(listOf(Policy.Availability.AVAILABLE, Policy.Availability.WORKER_DISABLED,
            Policy.Availability.MODEL_UNAVAILABLE), result.map { it.availability })
    }

    @Test fun credentialsEndpointAndModelFailuresAreNotSilentlyDropped() = runBlocking {
        assertEquals(Policy.Availability.CREDENTIALS_MISSING, resolve { model(it).copy(apiKey = "") }.availability)
        assertEquals(Policy.Availability.ENDPOINT_MISSING, resolve { model(it).copy(baseUrl = "") }.availability)
        val blankModel = provider.copy(models = listOf(provider.models.first().copy(modelId = "")))
        assertEquals(Policy.Availability.MODEL_NAME_MISSING, resolve(source = blankModel).availability)
        assertEquals(Policy.Availability.MODEL_UNAVAILABLE, resolve { null }.availability)
        assertEquals(Policy.Availability.INVALID_CONFIGURATION,
            resolve { throw IllegalArgumentException("sensitive-invalid-config") }.availability)
        val failed = resolve { throw IllegalStateException("sensitive-provider-failure") }
        assertEquals(Policy.Availability.RESOLUTION_FAILED, failed.availability)
        val description = ChildWorkerConfigResolver.describe(failed).toString()
        assertFalse(description.contains("sensitive-provider-failure"))
        assertFalse(description.contains(provider.apiKey))
        assertNull(failed.configuration)
    }

    @Test fun resolverCannotReturnAnotherProviderOrApiModel() = runBlocking {
        assertEquals(Policy.Availability.SELECTION_CHANGED_DURING_RESOLUTION,
            resolve { model(it).copy(providerId = "other-provider") }.availability)
        assertEquals(Policy.Availability.SELECTION_CHANGED_DURING_RESOLUTION,
            resolve { model(it).copy(model = "other-model") }.availability)
    }

    @Test fun injectedNullLookupsFailClosedInsteadOfRunningPersistedDefaults() = runBlocking {
        // providerLookup is injected and returns null although the id matches; the persisted
        // ProviderRepository default must not be consulted as a fallback.
        val providerMiss = ChildWorkerConfigResolver.resolveWorker(
            owner, ConversationSubAgentConfig(listOf(profile)), profile.id, profile.role,
            providerLookup = { null }, modelResolver = { model(it) })
        assertEquals(Policy.Availability.PROVIDER_UNAVAILABLE, providerMiss.availability)
        assertNull(providerMiss.configuration)
        // modelResolver is injected and returns null while the provider and model selection are
        // valid; the persisted ModelFeatureSelection default must not be consulted as a fallback.
        val modelMiss = ChildWorkerConfigResolver.resolveWorker(
            owner, ConversationSubAgentConfig(listOf(profile)), profile.id, profile.role,
            providerLookup = { id -> provider.takeIf { it.id == id } }, modelResolver = { null })
        assertEquals(Policy.Availability.MODEL_UNAVAILABLE, modelMiss.availability)
        assertNull(modelMiss.configuration)
    }

    @Test fun concurrentUserConfigurationChangeFailsClosedRatherThanMixingSnapshots() = runBlocking {
        var reads = 0
        val result = ChildWorkerConfigResolver.resolveWorker(owner, ConversationSubAgentConfig(listOf(profile)),
            profile.id, profile.role,
            providerLookup = { if (reads++ == 0) provider else provider.copy(baseUrl = "https://changed.invalid") },
            modelResolver = { model(it) })
        assertEquals(Policy.Availability.SELECTION_CHANGED_DURING_RESOLUTION, result.availability)
        assertNull(result.configuration)
    }

    @Test fun cancellationIsPropagatedNotReportedAsUnavailableConfiguration() = runBlocking {
        val cancelled = CancellationException("cancelled")
        try {
            resolve { throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        val stopped = AgentRunCancelledException()
        try {
            resolve { throw stopped }
            fail("Run cancellation must propagate")
        } catch (actual: AgentRunCancelledException) {
            assertSame(stopped, actual)
        }
    }

    @Test fun explicitSameProviderModelEditCanCreateLinkedSuccessorWithoutChangingFrozenOrdinaryDispatch() = runBlocking {
        val initial = resolve()
        val edited = resolve(profile.copy(modelId = "selection-new"))
        val key = initial.worker
        val frozen = Policy.Snapshot(key, "old-generation", requireNotNull(initial.configurationRevision),
            requireNotNull(initial.configuration))
        val task = Policy.TaskKey(key, "old-generation", "old-task")
        val evidence = Policy.Evidence(task, "review", "failed", "SUB_AGENT_PROVIDER_UNAVAILABLE",
            observationVersion = 2, handoffReadVersion = 2, executionStopped = true, canReplace = true)
        val decision = Policy.successor(task, key, "review", frozen, evidence) { edited }
        assertEquals(Policy.Code.EXPLICIT_SUCCESSOR_AVAILABLE, decision.code)
        assertEquals("api-new", decision.configuration?.model?.model)
        assertEquals(task, decision.predecessor)
        assertEquals("api-old", Policy.ordinary(key, frozen, true) { edited }.configuration?.model?.model)
    }

    @Test fun relevantUserEditsChangeRevisionButNamesAndOtherModelReasoningMemoryDoNot() {
        val base = model(profile)
        fun revision(selected: SubAgentProfile = profile, runtime: AgentModelClient.ModelConfig = base) =
            ChildWorkerConfigResolver.userConfigurationRevision(selected, runtime)
        val original = revision()
        assertEquals(original, revision(profile.copy(name = "Renamed worker")))
        assertEquals(original, revision(runtime = base.copy(providerName = "Renamed provider", modelDisplayName = "Renamed model")))
        assertEquals(original, revision(profile.copy(reasoningByModel = mapOf("other\u0000model" to ReasoningEffort.HIGH))))
        assertNotEquals(original, revision(profile.copy(reasoning = ReasoningEffort.HIGH)))
        assertNotEquals(original, revision(profile.copy(modelId = "selection-new")))
        assertNotEquals(original, revision(runtime = base.copy(baseUrl = "https://changed.invalid")))
        assertNotEquals(original, revision(runtime = base.copy(apiKey = "changed-private-credential")))
        assertNotEquals(original, revision(runtime = base.copy(extraBodyJson = "{\"temperature\":0.1}")))
    }

    @Test fun refreshedRuntimeCredentialsAreNotUserConfigurationEdits() = runBlocking {
        val before = resolve { model(it).copy(apiKey = "refreshed-token-1") }
        val after = resolve { model(it).copy(apiKey = "refreshed-token-2") }
        assertEquals(before.configurationRevision, after.configurationRevision)
        assertNotEquals(before.configuration?.model?.apiKey, after.configuration?.model?.apiKey)
    }

    @Test fun availabilityDescriptionDoesNotLeakConfigurationOrEqualityToken() = runBlocking {
        val result = resolve()
        val description = ChildWorkerConfigResolver.describe(result)
        assertTrue(description.getBoolean("configuration_available"))
        assertEquals("AVAILABLE", description.getString("configuration_code"))
        assertFalse(description.toString().contains(provider.apiKey))
        assertFalse(description.toString().contains(requireNotNull(result.configurationRevision)))
        assertFalse(result.toString().contains(provider.apiKey))
        assertFalse(result.configuration.toString().contains(provider.apiKey))
    }
}
