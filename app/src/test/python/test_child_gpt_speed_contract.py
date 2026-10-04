"""Static integration guards only: not Kotlin execution or rendered-UI proof."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

def source(path):
    return (ROOT / path).read_text(encoding='utf-8')

class ChildGptSpeedContractTest(unittest.TestCase):
    def test_both_layouts_use_the_same_speed_button(self):
        row = source('ui/components/SubAgentProfileRow.kt')
        self.assertEqual(row.count('SubAgentGptSpeedButton('), 2)
        self.assertIn('trailing = { SubAgentGptSpeedButton', row)
        card = row[row.rindex('SubAgentGptSpeedButton('):]
        self.assertIn('SubAgentTaskTierButton(', card)
        settings = source('ui/components/SubAgentSettingRow.kt')
        self.assertLess(settings.index('trailing?.invoke()'), settings.index('Icon(if (dropdown)'))

    def test_button_filters_actual_binding(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        for check in ('it.id == profile.providerId', 'it.id == profile.modelId',
                      'supportsGptSpeedBinding(provider, model)', 'if (!eligible) return'):
            self.assertIn(check, button)

    def test_animation_reuses_chat_policy_and_does_not_queue(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        for shared in ('GptSpeedAnimationDurationMillis', 'gptSpeedRotationDegrees(progress.value)',
                       'gptSpeedScale(progress.value)', 'gptSpeedChipColor(mode, normalColor)',
                       'GptSpeedAnimationGate()', 'gate.request()', 'gate.finish(token)'):
            self.assertIn(shared, button)
        self.assertIn('onLongClick = null', button)
        self.assertIn('onClick =', button)
        self.assertIn('if (token != 0)', button)
        self.assertNotIn('delay(', button)

    def test_failed_save_never_animates_or_claims_success(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        saved = button.index('result !is ConversationSubAgentPreferences.WriteResult.Saved')
        self.assertLess(button.index('editor?.cycleGptSpeed('), saved)
        self.assertLess(saved, button.index('Toast.makeText'))
        self.assertLess(saved, button.index('progress.animateTo('))
        self.assertIn('expected = profile', button)

    def test_each_profile_remembers_modes_by_provider_and_model(self):
        profile = source('agent/delegation/SubAgentProfile.kt')
        self.assertIn('gptSpeedByModel: Map<String, GptSpeedMode>', profile)
        self.assertIn('fun gptSpeedForModel(', profile)
        self.assertIn('modelReasoningKey(providerId, modelId)', profile)
        self.assertIn('gpt_speed_memory', profile)
        self.assertIn('GptSpeedMode.NORMAL', profile)

    def test_owner_snapshots_detach_speed_memory(self):
        store = source('agent/delegation/ConversationSubAgentPreferences.kt')
        resolver = source('agent/runtime/ChildWorkerConfigResolver.kt')
        self.assertIn('gptSpeedByModel = it.gptSpeedByModel.toMap()', store)
        self.assertIn('gptSpeedByModel = it.gptSpeedByModel.toMap()', resolver)
        self.assertIn('gpt_speed_memory', store)

    def test_editor_writes_under_owner_and_validates_live_binding(self):
        editor = source('ui/components/ConversationSubAgentEditor.kt')
        body = editor.split('fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        for check in ('updateProfile(id)', 'old.providerId != providerId', 'old.modelId != modelId',
                      'supportsGptSpeedBinding(provider, model)', 'gptSpeedByModel'):
            self.assertIn(check, body)
        self.assertNotIn('SubAgentPreferences.update(', body)
        self.assertNotIn('reasoning =', body)

    def test_runtime_applies_own_speed_and_checks_eligibility(self):
        resolver = source('agent/runtime/ChildWorkerConfigResolver.kt')
        for check in ('supportsGptSpeedBinding(provider, model)', 'supportsGptSpeedBinding(currentProvider, currentModel)',
                      'gptSpeedForModel(', 'gptSpeedMode =', 'isGptSpeedModel('):
            self.assertIn(check, resolver)
        self.assertNotIn('request.config.gptSpeedMode', resolver)

    def test_provider_lookup_is_suspend_and_outside_owner_transaction(self):
        editor = source('ui/components/ConversationSubAgentEditor.kt')
        self.assertNotIn('runBlocking', editor)
        self.assertIn('suspend fun cycleGptSpeed(', editor)
        body = editor.split('suspend fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        self.assertLess(body.index('providerLookup('), body.index('updateProfile(id)'))
        self.assertIn('CancellationException', editor)

    def test_existing_request_tier_mapping_is_unchanged(self):
        tier = source('agent/model/GptServiceTier.kt')
        for mode, value in [('NORMAL','default'), ('FAST','fast'), ('ULTRA_FAST','ultrafast')]:
            self.assertIn(f'GptSpeedMode.{mode} -> "{value}"', tier)
        for name in ('OpenAiChatCompletionsProvider.kt','ResponsesRequestBuilder.kt'):
            self.assertIn('GptServiceTier.apply(', source('agent/model/'+name))

if __name__ == '__main__':
    unittest.main()
