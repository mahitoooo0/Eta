package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.model.ProviderSetting
import org.robolectric.RuntimeEnvironment
import java.util.UUID

internal class SubAgentUiFixture(
    profiles: List<SubAgentProfile> = listOf(
        SubAgentProfile("legacy-0", "执行代理 1"),
        SubAgentProfile("legacy-2", "执行代理 2"),
        SubAgentProfile("legacy-3", "执行代理 3"),
        SubAgentProfile("legacy-1", "审查／总结代理", role = "review"),
    ),
    enabled: Boolean = true,
    canEdit: () -> Boolean = { true },
    providers: List<ProviderSetting>? = null,
    providerLookup: (suspend (String) -> ProviderSetting?)? = null,
) {
    val repository = ConversationSubAgentPreferences(
        RuntimeEnvironment.getApplication().getSharedPreferences("sub-agent-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    val owner = SubAgentConfigKey.Conversation("ui-${UUID.randomUUID()}")
    init {
        check(repository.update(owner) { ConversationSubAgentConfig(profiles = profiles, enabled = enabled) }
            is ConversationSubAgentPreferences.WriteResult.Saved)
    }
    val editor = if (providers == null && providerLookup == null) {
        ConversationSubAgentEditor(owner, repository, canEdit)
    } else {
        ConversationSubAgentEditor(owner, repository, { id ->
            if (providerLookup != null) providerLookup(id)
            else providers?.singleOrNull { it.id == id }
        }, canEdit)
    }
    fun snapshot(): ConversationSubAgentConfig = repository.snapshot(owner)
}

internal fun ComposeContentTestRule.setSubAgentContent(
    fixture: SubAgentUiFixture = SubAgentUiFixture(),
    content: @Composable () -> Unit,
) {
    setContent {
        CompositionLocalProvider(LocalConversationSubAgentEditor provides fixture.editor) {
            fixture.editor.observe()
            content()
        }
    }
}
