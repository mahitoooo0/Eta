package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

internal data class DshWebRuntimeStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val url: String? = null,
)

internal sealed interface DshWebLaunchResult {
    data class Opened(val url: String) : DshWebLaunchResult
    data class Failed(val code: String) : DshWebLaunchResult
}

/**
 * `dsh web` 的启停。
 *
 * 与 [KimiWebLauncher] 的差别：dsh 的监听地址与端口是固定的（日志里也不一定给可解析的 URL），
 * 所以不解析日志，而是直接探测 127.0.0.1:3080 是否已经 accepting，再交给系统浏览器。
 * 插件库装完技能或 MCP 后不需要重启（dsh 会热重载这两处），但装完 dsh 插件或 CLI agent
 * 需要重启才生效——这个入口就是为此准备的。
 */
internal class DshWebLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    suspend fun launch(environment: TerminalEnvironment): DshWebLaunchResult = withContext(Dispatchers.IO) {
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
        val backend = LinuxEnvironmentPaths.backendOf(rootfs.absolutePath)

        // 已经在跑就直接开浏览器，别重复起一个服务抢端口；
        // 但「在跑却没监听」说明它是上次失败留下的僵尸，先清掉再重起，
        // 否则它会一直占着记录让后续启动永远判定失败。
        val existing = findTask(environment, identity, backend)
        if (existing != null && existing.running) {
            if (awaitPort(waitAttempts = 1)) return@withContext openInBrowser()
            if (!daemonSupervisor.stop(existing.task.id)) {
                return@withContext DshWebLaunchResult.Failed("ZOMBIE_STOP_FAILED")
            }
        }
        val taskId = when (val started = daemonSupervisor.start(
            command = COMMAND,
            cwd = WORKDIR,
            identity = identity,
            environment = environment,
        )) {
            is DaemonStartResult.Started -> started.task.id
            is DaemonStartResult.Failed -> return@withContext DshWebLaunchResult.Failed(started.code)
        }
        if (!awaitPort()) {
            // 起不来就别留一个僵尸任务占着位置；诊断信息随任务日志一起留在守护任务里。
            if (!daemonSupervisor.stop(taskId)) {
                return@withContext DshWebLaunchResult.Failed("ZOMBIE_STOP_FAILED")
            }
            return@withContext DshWebLaunchResult.Failed("PORT_TIMEOUT")
        }
        openInBrowser()
    }

    suspend fun status(environment: TerminalEnvironment): DshWebRuntimeStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution
            ?: return@withContext DshWebRuntimeStatus()
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext DshWebRuntimeStatus()
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        val task = findTask(environment, identity, LinuxEnvironmentPaths.backendOf(rootfs.path))
            ?: return@withContext DshWebRuntimeStatus()
        // 端口只探一次：探两次会出现「running=true 但 url=null」的自相矛盾状态。
        val serving = task.running && portOpen()
        DshWebRuntimeStatus(taskId = task.task.id, running = serving, url = URL.takeIf { serving })
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution ?: return@withContext false
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext false
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        val task = findTask(environment, identity, LinuxEnvironmentPaths.backendOf(rootfs.path))
            ?: return@withContext false
        daemonSupervisor.stop(task.task.id)
    }

    private fun findTask(
        environment: TerminalEnvironment,
        identity: String,
        backend: LinuxExecutionBackend,
    ) = daemonSupervisor.list()
        .filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == backend && it.task.command.trim() == COMMAND
        }
        .lastOrNull()

    private fun openInBrowser(): DshWebLaunchResult = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        DshWebLaunchResult.Opened(URL)
    } catch (_: android.content.ActivityNotFoundException) {
        DshWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
    } catch (_: SecurityException) {
        DshWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
    }

    private suspend fun awaitPort(waitAttempts: Int = DEFAULT_WAIT_ATTEMPTS): Boolean {
        repeat(waitAttempts) {
            if (portOpen()) return true
            delay(WAIT_INTERVAL_MS)
        }
        return false
    }

    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val COMMAND = "dsh web"
        const val HOST = "127.0.0.1"
        const val PORT = 3080
        const val URL = "http://$HOST:$PORT"

        /** dsh 的工作目录固定为 /workspace，与 profile 解析保持一致。 */
        const val WORKDIR = "/workspace"

        private const val DEFAULT_WAIT_ATTEMPTS = 90
        private const val WAIT_INTERVAL_MS = 500L
        private const val PROBE_TIMEOUT_MS = 500
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
            "PORT_TIMEOUT", "PORT_NOT_OPEN" -> R.string.linux_dsh_web_failed_port
            "BROWSER_UNAVAILABLE" -> R.string.linux_dsh_web_failed_browser
            "ROOT_REQUIRED" -> R.string.linux_dsh_web_failed_root
            "LINUX_ENVIRONMENT_NOT_READY", "INVALID_ENVIRONMENT" -> R.string.linux_dsh_web_failed_env
            else -> R.string.linux_dsh_web_failed_start
        },
    )
}
