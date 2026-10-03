package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class DshPluginFailure {
    /** dsh 本体还没装好，插件无从谈起。 */
    DSH_NOT_READY,

    /** 没能启动 chroot 内的 shell。 */
    SHELL_UNAVAILABLE,

    /** 探测已装状态失败。 */
    PROBE_FAILED,

    /** 安装/卸载命令返回非零。 */
    COMMAND_FAILED,
}

internal sealed interface DshPluginResult {
    data class Succeeded(val output: String) : DshPluginResult
    data class Failed(val reason: DshPluginFailure, val output: String = "") : DshPluginResult
}

/**
 * dsh 插件库的安装执行器。
 *
 * 四类插件的落地方式差异极大（pnpm 包、patch 条目、markdown 文件、npm 全局包），
 * 但都统一走 [InstallerShellRunner] 在 chroot 内执行，命令文本由 [DshPluginCatalog] 生成。
 * 路径一律交给 chroot 内的 `$HOME` 解析——宿主注入的 HOME 才是权威，代码里猜不出来。
 */
internal class DshPluginInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

    private fun dshReady(): Boolean =
        linuxPackageProfileReady(rootfs, LinuxPackageProfiles.DSH)

    /**
     * 一次探测全部条目的安装状态。
     *
     * 逐条下发会让每个条目各起一次 shell（十几秒到几十秒），所以拼成一条脚本一次跑完，
     * 用固定前缀的行标记解析结果。
     */
    suspend fun probeInstalled(): Result<Set<String>> = withContext(Dispatchers.IO) {
        if (!dshReady()) return@withContext Result.failure(DshPluginNotReadyException)
        val result = run(DshPluginCatalog.probeScript(), PROBE_TIMEOUT_SECONDS)
        val ids = parseProbeOutput(result.output)
        if (result.exitCode != 0) {
            AndroidAgentLogger.warn(
                "Dsh plugin probe outcome=failed exitCode=${result.exitCode} " +
                    "outputChars=${result.output.length}",
            )
            return@withContext Result.failure(DshPluginProbeException(result.output.takeLast(400)))
        }
        Result.success(ids)
    }

    suspend fun install(entry: DshPluginEntry): DshPluginResult =
        mutate(entry, DshPluginCatalog.installScript(entry), INSTALL_TIMEOUT_SECONDS, "install")

    suspend fun uninstall(entry: DshPluginEntry): DshPluginResult =
        mutate(entry, DshPluginCatalog.uninstallScript(entry), UNINSTALL_TIMEOUT_SECONDS, "uninstall")

    private suspend fun mutate(
        entry: DshPluginEntry,
        command: String,
        timeoutSeconds: Long,
        action: String,
    ): DshPluginResult = installMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!dshReady()) return@withContext DshPluginResult.Failed(DshPluginFailure.DSH_NOT_READY)
            val result = run(command, timeoutSeconds)
            AndroidAgentLogger.info(
                "Dsh plugin action=$action id=${entry.id} kind=${entry.installKind} " +
                    "exitCode=${result.exitCode} outputChars=${result.output.length}",
            )
            if (result.exitCode == SHELL_UNAVAILABLE_EXIT) {
                return@withContext DshPluginResult.Failed(DshPluginFailure.SHELL_UNAVAILABLE)
            }
            if (result.exitCode != 0) {
                return@withContext DshPluginResult.Failed(
                    DshPluginFailure.COMMAND_FAILED,
                    result.output.takeLast(400),
                )
            }
            DshPluginResult.Succeeded(result.output.takeLast(400))
        }
    }

    private suspend fun run(command: String, timeoutSeconds: Long): InstallerCommandResult =
        InstallerShellRunner.run(
            command = command,
            timeoutSeconds = timeoutSeconds,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )

    private fun parseProbeOutput(output: String): Set<String> = output
        .lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith(DshPluginCatalog.PROBE_LINE_PREFIX)) return@mapNotNull null
            trimmed.removePrefix(DshPluginCatalog.PROBE_LINE_PREFIX).trim().ifBlank { null }
        }
        .filter { DshPluginCatalog.byId(it) != null }
        .toSet()

    private object DshPluginNotReadyException : Exception("dsh is not installed")

    private object DshPluginProbeException : Exception("dsh plugin probe failed")

    private companion object {
        val installMutex = Mutex()
        const val INSTALL_TIMEOUT_SECONDS = 900L
        const val UNINSTALL_TIMEOUT_SECONDS = 300L
        const val PROBE_TIMEOUT_SECONDS = 180L

        /** InstallerShellRunner 用 -1 表示 shell 都没起来。 */
        const val SHELL_UNAVAILABLE_EXIT = -1
    }
}
