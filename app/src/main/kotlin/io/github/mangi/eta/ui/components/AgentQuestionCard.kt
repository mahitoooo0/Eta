package io.github.mangi.eta.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi

/** Stateless card: all draft changes go to the conversation store; selection never auto-submits. */
@Composable
internal fun AgentQuestionCard(
    message: AgentQuestionMessageUi,
    modifier: Modifier = Modifier,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    val request = message.request
    val editable = message.status == AgentQuestionStatus.Waiting && !message.submitting
    val displayed = message.answer ?: AgentQuestionProjection.draftAnswer(message)
    val draft = AgentQuestionAnswer(message.answerKind, message.selectedOptionId,
        message.otherText, message.note)
    val valid = AgentQuestionCodec.validateAnswer(request, AgentQuestionProjection.draftAnswer(message)).accepted
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.question_heading), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(request.title, style = MaterialTheme.typography.titleMedium)
            Text(request.question, style = MaterialTheme.typography.bodyMedium)
            request.options.forEach { option ->
                QuestionOptionRow(label = option.label, description = option.description,
                    selected = displayed.kind == "option" && displayed.optionId == option.id,
                    enabled = editable, recommended = option.id == request.recommendedOptionId,
                    onClick = { onDraftChanged(draft.copy(kind = "option", optionId = option.id)) })
            }
            if (request.allowOther) {
                QuestionOptionRow(stringResource(R.string.question_other), "",
                    displayed.kind == "other", editable, false) {
                    onDraftChanged(draft.copy(kind = "other", optionId = null))
                }
                if (displayed.kind == "other") {
                    OutlinedTextField(value = if (message.answer != null) displayed.otherText else message.otherText,
                        onValueChange = { onDraftChanged(draft.copy(kind = "other", optionId = null, otherText = it.take(2000))) },
                        enabled = editable, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.question_other_hint)) }, maxLines = 5)
                }
            }
            if (request.allowDelegation) QuestionOptionRow(stringResource(R.string.question_delegate),
                stringResource(R.string.question_delegate_hint), displayed.kind == "delegate", editable, false) {
                onDraftChanged(draft.copy(kind = "delegate", optionId = null))
            }
            if (request.allowNote) OutlinedTextField(
                value = if (message.answer != null) displayed.note else message.note,
                onValueChange = { onDraftChanged(draft.copy(note = it.take(2000))) }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.question_note)) }, maxLines = 5)
            message.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            if (message.status == AgentQuestionStatus.Waiting) {
                Button(onClick = onSubmit, enabled = editable && valid, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (message.submitting) R.string.question_submitting else R.string.question_submit))
                }
            } else Text(stringResource(when (message.status) {
                AgentQuestionStatus.Answered -> R.string.question_answered
                AgentQuestionStatus.Cancelled -> R.string.question_cancelled
                AgentQuestionStatus.Interrupted -> R.string.question_interrupted
                AgentQuestionStatus.Waiting -> R.string.question_waiting
            }), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun QuestionOptionRow(label: String, description: String, selected: Boolean,
    enabled: Boolean, recommended: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recommended) Text(stringResource(R.string.question_recommended),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
