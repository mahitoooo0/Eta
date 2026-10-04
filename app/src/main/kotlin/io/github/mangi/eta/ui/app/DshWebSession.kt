package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.delay

/**
 * dsh 启动和复用的事务边界：只有本次创建且未能打开浏览器的任务才会被回收。
 *
 * 与 `KimiWebSession` 同构——dsh 和 kimi 的 Web UI 都不能直接用裸端口访问：
 * 两者都会在就绪时把**带认证信息**的地址打印到日志，必须取那一行才能打开。
 */
internal class DshWebSession(
    private val tasks: Tasks,
    private val openUrl: (String) -> Boolean,
    private val waitAttempts: Int = 120,
    private val waitIntervalMs: Long = 500,
) {
    interface Tasks {
        fun list(): List<DetachedTaskStatus>
        fun start(environment: TerminalEnvironment, identity: String): DaemonStartResult
        fun logs(id: String): DaemonLogsResult
        fun stop(id: String)
    }

    suspend fun launch(
        environment: TerminalEnvironment,
        identity: String,
        backend: LinuxExecutionBackend,
    ): DshWebLaunchResult {
        var createdTaskId: String? = null
        var opened = false
        try {
            val existing = tasks.list().firstOrNull {
                it.running && it.task.environment == environment && it.task.identity == identity &&
                    it.task.backend == backend && it.task.command.trim() in COMMANDS
            }
            val taskId = existing?.task?.id ?: when (val started = tasks.start(environment, identity)) {
                is DaemonStartResult.Started -> started.task.id.also { createdTaskId = it }
                is DaemonStartResult.Failed -> return DshWebLaunchResult.Failed(started.code)
            }
            repeat(waitAttempts) {
                val status = tasks.list().firstOrNull { it.task.id == taskId }
                if (status == null || !status.running) return DshWebLaunchResult.Failed("DSH_EXITED")
                val logs = tasks.logs(taskId)
                if (!logs.ok) return DshWebLaunchResult.Failed(logs.code.ifBlank { "LOGS_UNAVAILABLE" })
                val url = addressFromLogs(logs.text)
                if (url != null) {
                    opened = openUrl(url)
                    return if (opened) {
                        DshWebLaunchResult.Opened(url)
                    } else {
                        DshWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
                    }
                }
                delay(waitIntervalMs)
            }
            return DshWebLaunchResult.Failed("URL_TIMEOUT")
        } finally {
            if (!opened) createdTaskId?.let(tasks::stop)
        }
    }

    companion object {
        /**
         * `--no-open` 必须带：dsh 默认会自己拉起默认浏览器，但 chroot 里没有可用浏览器，
         * 只会多打一行失败提示。改由 Eta 从日志取地址、用系统浏览器打开。
         */
        const val COMMAND = "dsh web --no-open"

        /** 复用时两种写法都要认：历史任务可能是用户手动敲的。 */
        val COMMANDS = setOf(COMMAND, "dsh web")

        /**
         * 从 `dsh web` 的输出里取回**带认证的**本机地址。
         *
         * dsh 就绪时打印：`dsh web: <authenticatedUrl> (LAN: <lanUrl>)`。
         * 直接访问裸的 `127.0.0.1:3080` 会被拒绝，页面提示
         * "dsh web authentication required. Reopen the URL printed by `dsh web`."
         * 所以只能用日志里这一行给出的地址。
         */
        fun addressFromLogs(text: String): String? {
            // 先剥 ANSI 颜色序列，避免转义尾巴被吃进 URL。
            val plain = ANSI_REGEX.replace(text, "")
            // 锚定字面 `dsh web: ` 前缀取第一个非空白串：
            // 这样既能避开 authentication required 那句提示，也能自然跳过后面的
            // ` (LAN: ...)` 尾巴（auth 与 LAN 之间有空格）。
            val url = WEB_URL_REGEX.find(plain)?.groupValues?.get(1) ?: return null
            // 不赌 token 的具体形状（是 `#token=`、`?token=` 还是别的），只认主机名，
            // 顺手把 LAN 地址与 0.0.0.0 挡在外面。
            return url.takeIf { it.startsWith(LOOPBACK_PREFIX) }
        }

        private val ANSI_REGEX = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

        private val WEB_URL_REGEX = Regex("""dsh web:\s+(\S+)""")

        private const val LOOPBACK_PREFIX = "http://127.0.0.1:"
    }
}
