package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.DshPluginCategory
import io.github.mangi.eta.agent.terminal.DshPluginEntry
import io.github.mangi.eta.agent.terminal.DshPluginFailure
import io.github.mangi.eta.agent.terminal.DshPluginInstaller
import io.github.mangi.eta.agent.terminal.DshPluginProbeFailure
import io.github.mangi.eta.agent.terminal.DshPluginResult
import io.github.mangi.eta.agent.terminal.DshPluginCatalog
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * dsh 插件库。
 *
 * 四类插件的安装动作耗时差异很大（pnpm 拉包可能好几分钟），所以每条都自带独立状态，
 * 失败时把命令输出尾部直接显示出来——否则用户只会看到"点了没反应"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DshPluginLibrarySheet(
    installer: DshPluginInstaller,
    actionScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf(DshPluginCategory.DSH_PLUGIN) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var probed by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    var messageDetail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(installer) {
        // 探测失败也要把列表放出来，但必须让用户看见「读不到」而不是「什么都没装」。
        val result = installer.probeInstalled()
        result.onSuccess { installed = it }
        result.onFailure { error ->
            probed = false
            messageRes = when ((error as? Pair<*, *>)?.first) {
                DshPluginProbeFailure.DSH_NOT_READY -> R.string.linux_dsh_plugin_dsh_not_ready
                else -> R.string.linux_dsh_plugin_probe_failed
            }
        }
        probed = true
    }

    val entries = remember(category) {
        DshPluginCatalog.ENTRIES.filter { it.category == category }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            SmallTitle(stringResource(R.string.linux_dsh_plugin_library))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DshPluginCategory.entries.forEach { item ->
                    TextButton(
                        text = stringResource(category.titleRes()),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { category = item },
                    )
                }
            }
            if (!probed) {
                Text(
                    text = stringResource(R.string.linux_dsh_plugin_busy),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.linux_dsh_plugin_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    DshPluginRow(
                        entry = entry,
                        installed = entry.id in installed,
                        busy = busyId == entry.id,
                        enabled = busyId == null,
                        onToggle = {
                            busyId = entry.id
                            messageRes = null
                            messageDetail = null
                            // 用屏幕级 scope：关掉面板不该把写配置写到一半的安装打断。
                            actionScope.launch {
                                try {
                                    val result = if (entry.id in installed) {
                                        installer.uninstall(entry)
                                    } else {
                                        installer.install(entry)
                                    }
                                    if (result is DshPluginResult.Failed) {
                                        messageRes = result.messageRes()
                                        messageDetail = result.output.takeLast(200).ifBlank { null }
                                    } else {
                                        messageRes = null
                                        messageDetail = null
                                    }
                                    installed = installer.probeInstalled().getOrDefault(installed)
                                } catch (error: Throwable) {
                                    // 不接住的话 busyId 永远清不掉，整个面板会变成死局。
                                    messageRes = R.string.linux_dsh_plugin_failed
                                    messageDetail = error.message?.takeLast(200)
                                } finally {
                                    busyId = null
                                }
                            }
                        },
                    )
                }
                item(key = "footer") {
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.linux_dsh_plugin_restart_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        messageRes?.let {
                            Text(
                                text = stringResource(it) +
                                    (messageDetail?.let { detail -> ": " + detail } ?: ""),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DshPluginRow(
    entry: DshPluginEntry,
    installed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(entry.titleRes),
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(entry.summaryRes),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                val badge = entry.packageName ?: entry.skillDirName
                badge?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                if (entry.requiresToken) {
                    Text(
                        text = stringResource(R.string.linux_dsh_plugin_needs_token),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            TextButton(
                text = when {
                    busy -> stringResource(R.string.linux_dsh_plugin_busy)
                    installed -> stringResource(R.string.linux_dsh_plugin_uninstall)
                    else -> stringResource(R.string.linux_dsh_plugin_install)
                },
                enabled = enabled,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onToggle,
            )
        }
    }
}

private fun DshPluginCategory.titleRes(): Int = when (this) {
    DshPluginCategory.DSH_PLUGIN -> R.string.linux_dsh_plugin_category_plugin
    DshPluginCategory.MCP_SERVER -> R.string.linux_dsh_plugin_category_mcp
    DshPluginCategory.AGENT_SKILL -> R.string.linux_dsh_plugin_category_skill
    DshPluginCategory.CLI_AGENT -> R.string.linux_dsh_plugin_category_cli
}

/** 失败原因对应的文案；命令输出由调用方另行拼接，方便在界面上分行展示。 */
@androidx.annotation.StringRes
internal fun DshPluginResult.Failed.messageRes(): Int = when (reason) {
    DshPluginFailure.DSH_NOT_READY -> R.string.linux_dsh_plugin_dsh_not_ready
    DshPluginFailure.SHELL_UNAVAILABLE,
    DshPluginFailure.COMMAND_FAILED,
    -> R.string.linux_dsh_plugin_failed
    DshPluginFailure.COMMAND_TIMEOUT -> R.string.linux_dsh_plugin_timeout
}
