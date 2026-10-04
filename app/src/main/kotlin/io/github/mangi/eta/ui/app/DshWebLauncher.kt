package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DshWebRuntimeStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val url: String? = null,
    val code: String? = null,
)

internal sealed interface DshWebLaunchResult {
    data class Opened(val url: String) : DshWebLaunchResult
    data class Failed(val code: String) : DshWebLaunchResult
}

/**
 * `dsh web` 的启停。
 *
 * 与 [KimiWebLauncher] 同构：**不探测端口，而是从日志里取带认证的地址**。
 *
 * 早先的实现假设「dsh 的监听地址与端口固定、日志里不一定给可解析的 URL」，
 * 于是直接打开 `http://127.0.0.1:3080`——实测被 dsh 拒绝，页面提示
 * `dsh web authentication required`。查 `@deepseek-ai/dsh-web-app` 源码确认：
 * dsh 就绪时会打印 `dsh web: <authenticatedUrl>`，必须用那一行里的地址。
 * 详见 [DshWebSession.addressFromLogs]。
 */
internal class DshWebLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    private val session = DshWebSession(
        tasks = object : DshWebSession.Tasks {
            override fun list() = daemonSupervisor.list()
            override fun start(environment: TerminalEnvironment, identity: String) = daemonSupervisor.start(
                command = DshWebSession.COMMAND,
                cwd = WORKDIR,
                identity = identity,
                environment = environment,
            )
            override fun logs(id: String) = daemonSupervisor.readLogs(id)
            override fun stop(id: String) { daemonSupervisor.stop(id) }
        },
        openUrl = { url ->
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )

    suspend fun launch(environment: TerminalEnvironment): DshWebLaunchResult = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val distribution = environment.linuxDistribution
                ?: return@withContext DshWebLaunchResult.Failed("INVALID_ENVIRONMENT")
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
                return@withContext DshWebLaunchResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
            }
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs.absolutePath)
            if (identity == "root" && !TerminalRuntime.rootAvailable) {
                return@withContext DshWebLaunchResult.Failed("ROOT_REQUIRED")
            }
            session.launch(
                environment = environment,
                identity = identity,
                backend = LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
            )
        }
    }

    suspend fun status(environment: TerminalEnvironment): DshWebRuntimeStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution ?: return@withContext DshWebRuntimeStatus()
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext DshWebRuntimeStatus()
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        if (identity == "root" && !TerminalRuntime.rootAvailable) return@withContext DshWebRuntimeStatus()
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == LinuxEnvironmentPaths.backendOf(rootfs.path) &&
                it.task.command.trim() in DshWebSession.COMMANDS
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext DshWebRuntimeStatus()
        if (!task.running) return@withContext DshWebRuntimeStatus(taskId = task.task.id, code = "DSH_EXITED")
        val logs = daemonSupervisor.readLogs(task.task.id)
        DshWebRuntimeStatus(
            taskId = task.task.id,
            running = true,
            url = if (logs.ok) DshWebSession.addressFromLogs(logs.text) else null,
            code = if (logs.ok) null else logs.code,
        )
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            // status 自身不取锁，所以这里不会与 launchMutex 重入。
            val status = status(environment)
            status.taskId?.let(daemonSupervisor::stop) ?: false
        }
    }

    private companion object {
        val launchMutex = Mutex()

        /** dsh 的工作目录固定为 /workspace，与 profile 解析保持一致。 */
        const val WORKDIR = "/workspace"
    }
}

/**
 * 失败码到用户可读文案的映射。
 *
 * 命名不与 `KimiWebLaunchResult.message` 冲突：两者都在 `io.github.mangi.eta.ui.app` 包里，
 * 同名扩展会直接编译冲突。
 */
internal fun DshWebLaunchResult.dshWebMessage(context: Context): String? = when (this) {
    is DshWebLaunchResult.Opened -> null
    is DshWebLaunchResult.Failed -> context.getString(
        when (code) {
            // 拿不到地址分两种：dsh 自己退了，或等到超时。给用户的信息不一样。
            "DSH_EXITED" -> R.string.linux_dsh_web_failed_start
            "URL_TIMEOUT" -> R.string.linux_dsh_web_failed_port
            "LOGS_UNAVAILABLE" -> R.string.linux_dsh_web_failed_logs
            "BROWSER_UNAVAILABLE" -> R.string.linux_dsh_web_failed_browser
            "ROOT_REQUIRED" -> R.string.linux_dsh_web_failed_root
            "LINUX_ENVIRONMENT_NOT_READY", "INVALID_ENVIRONMENT" -> R.string.linux_dsh_web_failed_env
            else -> R.string.linux_dsh_web_failed_start
        },
    )
}
