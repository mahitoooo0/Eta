package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal sealed interface SubAgentEditorState {
    data object Loading : SubAgentEditorState
    data class Loaded(val config: ConversationSubAgentConfig) : SubAgentEditorState
    data class Error(val reason: String) : SubAgentEditorState
}

/** Capture this object when opening a picker: callbacks must never retarget the currently selected owner. */
internal class ConversationSubAgentEditor(
    val owner: SubAgentConfigKey,
    val repository: ConversationSubAgentPreferences,
    private val providerLookup: suspend (String) -> ProviderSetting?,
    val canEdit: () -> Boolean,
) {
    constructor(owner: SubAgentConfigKey, repository: ConversationSubAgentPreferences, canEdit: () -> Boolean) :
        this(owner, repository, { id -> ProviderRepository.providerById(id) }, canEdit)

    private class LostOwner : RuntimeException()
    private var storedState: SubAgentEditorState by mutableStateOf(SubAgentEditorState.Loading)
    private var lifecycleFailure: (() -> String?)? = null
    val state: SubAgentEditorState
        get() = lifecycleFailure?.invoke()?.let { SubAgentEditorState.Error(it) } ?: storedState
    private var retryVersion by mutableIntStateOf(0)
    val enabled: Boolean get() = state is SubAgentEditorState.Loaded && canEdit()
    private fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        storedState = SubAgentEditorState.Error(failure.message ?: failure.javaClass.simpleName)
    }
    private var lifecycleRecovery: (() -> Boolean)? = null
    init {
        try { storedState = SubAgentEditorState.Loaded(repository.snapshot(owner)) }
        catch (failure: Exception) { fail(failure) }
    }
    fun bindLifecycleState(reason: () -> String?, recovery: () -> Boolean) {
        lifecycleFailure = reason
        lifecycleRecovery = recovery
    }
    /** Explicitly recover the durability fence, then restart the snapshot + live subscription. */
    fun retry() {
        try {
            check(repository.recoverDurability()) { "子代理配置恢复失败；请重试" }
            if (lifecycleFailure?.invoke() != null) {
                check(lifecycleRecovery?.invoke() == true) { "会话配置尚未恢复，原配置已保留" }
            }
            storedState = SubAgentEditorState.Loading
            retryVersion++
        } catch (failure: Exception) { fail(failure) }
    }
    @Composable
    fun observe(): SubAgentEditorState {
        val version = retryVersion
        LaunchedEffect(this, version) {
            // Opening another page cannot silently clear an error: only the retry action may do so.
            if (state is SubAgentEditorState.Error) return@LaunchedEffect
            try {
                storedState = SubAgentEditorState.Loaded(repository.snapshot(owner))
                repository.flow(owner).collect { config ->
                    if (state !is SubAgentEditorState.Error) storedState = SubAgentEditorState.Loaded(config)
                }
            } catch (failure: Exception) { fail(failure) }
        }
        return state
    }
    fun update(change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            repository.update(owner) { old ->
                if (!canEdit() || state !is SubAgentEditorState.Loaded) throw LostOwner()
                change(old)
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
          catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun add(): ConversationSubAgentPreferences.WriteResult = update { old ->
        var number = old.profiles.size + 1
        while (old.profiles.any { it.name == "子代理 $number" }) number++
        old.copy(profiles = old.profiles + SubAgentProfile(UUID.randomUUID().toString(), "子代理 $number"))
    }
    fun updateProfile(id: String, change: (SubAgentProfile) -> SubAgentProfile): ConversationSubAgentPreferences.WriteResult = update { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.map { profile ->
            if (profile.id != id) profile else change(profile).normalizedTaskTier().also { require(it.id == id) }
        })
    }
    fun remove(id: String) = update { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.filterNot { profile -> profile.id == id })
    }
    fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    fun setDiagnosticsEnabled(value: Boolean) = update { it.copy(diagnosticsEnabled = value) }
    fun saveParallelLimit(id: String, providerId: String, modelId: String, apiModel: String, limit: Int) = update { old ->
        if (limit < 0 || providerId.isBlank() || apiModel.isBlank() || old.profiles.none {
                it.id == id && it.providerId == providerId && it.modelId == modelId
            }) throw LostOwner()
        old.copy(parallelLimits = old.parallelLimits + (SubAgentParallelModel(providerId, apiModel) to limit))
    }
    suspend fun cycleGptSpeed(id: String, providerId: String, modelId: String, expected: SubAgentProfile? = null):
        ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            // Capture before suspension, even when the caller has no expected UI snapshot.
            val captured = repository.snapshot(owner).profiles.singleOrNull { it.id == id } ?: throw LostOwner()
            if (captured.providerId != providerId || captured.modelId != modelId ||
                (expected != null && captured != expected)) throw LostOwner()
            // Room lookup is suspendable and must never run inside the owner transaction/lock.
            val resolvedProvider = providerLookup(providerId)
            currentCoroutineContext().ensureActive()
            val provider = resolvedProvider?.takeIf { it.id == providerId } ?: throw LostOwner()
            val model = provider.models.singleOrNull { it.id == modelId } ?: throw LostOwner()
            if (!enabled || !supportsGptSpeedBinding(provider, model)) throw LostOwner()
            updateProfile(id) { old ->
                // updateProfile also rechecks canEdit/state under the owner transaction.
                if (old != (expected ?: captured) || old.providerId != providerId || old.modelId != modelId ||
                    !supportsGptSpeedBinding(provider, model)) throw LostOwner()
                old.copy(gptSpeedByModel = old.gptSpeedByModel +
                    (SubAgentProfile.modelReasoningKey(providerId, modelId) to old.gptSpeedForModel().next()))
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
          catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }

    fun saveModel(id: String, selection: ModelFeatureSelection, expected: SubAgentProfile? = null) = updateProfile(id) { old ->
        if (expected != null && (old.role != expected.role || old.providerId != expected.providerId || old.modelId != expected.modelId))
            throw LostOwner()
        val sameModel = old.providerId == selection.providerId && old.modelId == selection.modelId
        val memory = old.reasoningByModel.toMutableMap()
        if (old.providerId.isNotBlank() && old.modelId.isNotBlank() && old.reasoning != null)
            memory[SubAgentProfile.modelReasoningKey(old.providerId, old.modelId)] = old.reasoning
        val restored = when {
            sameModel -> old.reasoning
            selection.providerId.isBlank() || selection.modelId.isBlank() -> null
            else -> memory[SubAgentProfile.modelReasoningKey(selection.providerId, selection.modelId)]
        }
        old.copy(providerId = selection.providerId, modelId = selection.modelId,
            imageResolution = if (sameModel) old.imageResolution else null,
            reasoning = restored, reasoningByModel = memory)
    }
}

internal val LocalConversationSubAgentEditor = compositionLocalOf<ConversationSubAgentEditor?> { null }
