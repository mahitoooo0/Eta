package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.formatReconnectElapsed
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Stable lazy-row identity and one-line height: clock updates never insert or resize rows. */
@Composable
internal fun ErrorReconnectDivider(message: ErrorReconnectMessageUi, modifier: Modifier = Modifier) {
    val view = LocalView.current
    var showDetails by remember(message.id) { mutableStateOf(false) }
    var elapsed by remember(message.id) { mutableLongStateOf(message.elapsedMs.coerceAtLeast(0L)) }
    LaunchedEffect(message.id, message.status, message.elapsedMs) {
        elapsed = maxOf(elapsed, message.elapsedMs.coerceAtLeast(0L))
        if (message.status == ErrorReconnectStatus.Running) {
            val base = elapsed
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000L)
                val delta = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                elapsed = maxOf(elapsed, base + delta.coerceAtMost(Long.MAX_VALUE - base))
            }
        }
    }
    val label = errorReconnectLabel(message, elapsed)
    val title = stringResource(R.string.reconnect_error_title)
    val lineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f)
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = modifier.fillMaxWidth().testTag("error-reconnect:${message.id}")
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(lineColor))
        Text(
            text = label,
            modifier = Modifier.weight(3f, fill = false).clip(RoundedCornerShape(8.dp))
                .clickable { TouchHaptics.click(view); showDetails = true }
                .semantics { contentDescription = "$label. $title" }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(lineColor))
    }
    if (showDetails) {
        // This existing dialog has a bounded-height, selectable, vertically scrollable
        // body. Full diagnostics stay here, not in answer/copy/speech text.
        ContextCompactedSummarySheet(
            title = title,
            summary = listOf(message.reasonCode, message.reasonDetail)
                .filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { label },
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
internal fun errorReconnectLabel(message: ErrorReconnectMessageUi, elapsedMs: Long = message.elapsedMs): String =
    when (message.status) {
        ErrorReconnectStatus.Running -> stringResource(R.string.reconnect_running, formatReconnectElapsed(elapsedMs))
        ErrorReconnectStatus.Succeeded -> stringResource(R.string.reconnect_succeeded)
        ErrorReconnectStatus.Failed -> stringResource(
            if (message.isReconnect) R.string.reconnect_failed else R.string.system_notice_runtime_failed,
        )
        ErrorReconnectStatus.Stopped -> stringResource(R.string.reconnect_stopped)
    }
