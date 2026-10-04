package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.delay

/**
 * dsh 的启动与复用。
 *
 * 刻意**不在失败时回收刚创建的任务**：回收会连诊断现场（daemon 日志）一起删掉，
 * dsh 起不来时无从排查。残留任务无害——下次启动的预清理会杀掉旧进程，
 * 记录也会被 list() 的 prune 清理。
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
        val existing = tasks.list().firstOrNull {
            it.running && it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == backend && it.task.command.trim() in COMMANDS
        }
        val taskId = existing?.task?.id ?: when (val started = tasks.start(environment, identity)) {
            is DaemonStartResult.Started -> started.task.id
            is DaemonStartResult.Failed -> return DshWebLaunchResult.Failed(started.code)
        }
        repeat(waitAttempts) {
            val status = tasks.list().firstOrNull { it.task.id == taskId }
            if (status == null || !status.running) return DshWebLaunchResult.Failed("DSH_EXITED")
            val logs = tasks.logs(taskId)
            if (!logs.ok) return DshWebLaunchResult.Failed(logs.code.ifBlank { "LOGS_UNAVAILABLE" })
            val url = addressFromLogs(logs.text)
            if (url != null) {
                val opened = openUrl(url)
                return if (opened) {
                    DshWebLaunchResult.Opened(url)
                } else {
                    DshWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
                }
            }
            delay(waitIntervalMs)
        }
        return DshWebLaunchResult.Failed("URL_TIMEOUT")
    }

    companion object {
        /**
         * 启动命令的三件事，缺一不可：
         *
         * 1. **代理**：dsh 的 server 端会发起模型请求，而 chroot 内 DNS 不可用
         *    （Android 拦明文 DNS、代理走 VPN/eBPF 只接管宿主流量），必须走代理。
         *    daemon 用的是非登录 shell，`/etc/profile.d` 不会自动生效，所以显式 source。
         * 2. **预清理**：上一轮的 `dsh web` 可能还活着——重装 App 杀不掉 setsid 脱离的
         *    daemon，重装 rootfs 也杀不掉运行中的进程（文件没了、进程还在内存里）。
         *    它占着端口，新实例会 EADDRINUSE 秒退。
         *
         *    pkill 的自匹配要防**两层**：pkill 自身的 argv（`[d]sh` 写法可防），以及
         *    **父 shell 的 cmdline**——daemon 的 `sh -c` 会把整条脚本原样放进 argv，
         *    脚本里 `exec dsh web` 那段就含 `dsh web` 字样，pkill 一扫就把父 shell 杀了：
         *    实测症状正是「log 0 字节 + 进程秒死」。所以 exec 段写成 `dsh w''eb`——
         *    POSIX sh 会把空引号拼掉（`w''eb` == `web`），但 cmdline 里不再有连续的
         *    `dsh web`，pkill 就不会误伤自己人；exec 之后的 node 进程 cmdline 虽会
         *    变回 `dsh web`，那时 pkill 早已跑完，且它本就是下一轮要清理的对象。
         * 3. `--no-open` 必须带：chroot 里没有可用浏览器，dsh 自己开只会报错；
         *    由 Eta 从日志取地址后用系统浏览器打开。
         */
        const val COMMAND =
            "if [ -r /etc/profile.d/99eta-proxy.sh ]; then . /etc/profile.d/99eta-proxy.sh; fi; " +
                "pkill -f '[d]sh web' 2>/dev/null; exec dsh w''eb --no-open"

        /** 复用时历史写法都要认：旧记录可能是 `dsh web` 或无预清理的版本。 */
        val COMMANDS = setOf(COMMAND, "dsh web --no-open", "dsh web")

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
