package io.github.mangi.eta.agent.terminal

import androidx.annotation.StringRes
import io.github.mangi.eta.R

/**
 * 插件库分类。四个分类对应四种完全不同的落地方式，不能混为一谈。
 */
internal enum class DshPluginCategory {
    /** 通过 `dsh plugin --profile <name> add` 安装的 dsh 插件包。 */
    DSH_PLUGIN,

    /** 通过 `$DSH_HOME/cordis.patch.yml` 追加 patch 条目启用的 MCP 服务器。 */
    MCP_SERVER,

    /** 写入 `$DSH_HOME/skills/<dir>/SKILL.md` 的 Agent 技能。 */
    AGENT_SKILL,

    /** 通过 `npm install -g` 安装的独立 CLI agent。 */
    CLI_AGENT,
}

/** 插件的落地方式；每一种的安装、卸载与探测命令都不同。 */
internal enum class DshPluginInstallKind {
    DSH_PLUGIN,
    MCP_SERVER,
    SKILL_FILE,
    NPM_GLOBAL,
}

internal data class DshPluginEntry(
    val id: String,
    val category: DshPluginCategory,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val installKind: DshPluginInstallKind,
    /** npm 包名；DSH_PLUGIN / MCP_SERVER / NPM_GLOBAL 三类必填。 */
    val packageName: String? = null,
    /** CLI agent 的可执行文件名，用于探测与展示。 */
    val commandName: String? = null,
    /** MCP 的 serverName，同时也是 patch 条目的 id 片段。 */
    val serverName: String? = null,
    /** 技能目录名（不含 SKILL.md）。 */
    val skillDirName: String? = null,
    /** 完整的 SKILL.md 正文（含 frontmatter）。 */
    val skillBody: String? = null,
    /** 需要用户自备凭据（如 GitHub token），UI 上要显式提示。 */
    val requiresToken: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "plugin id must not be blank" }
        when (installKind) {
            // 这三类都以 npm 包为身份，缺包名就没法拼安装命令。
            DshPluginInstallKind.DSH_PLUGIN,
            DshPluginInstallKind.MCP_SERVER,
            DshPluginInstallKind.NPM_GLOBAL,
            -> require(!packageName.isNullOrBlank()) { "$id requires packageName" }

            DshPluginInstallKind.SKILL_FILE -> require(
                !skillDirName.isNullOrBlank() && !skillBody.isNullOrBlank(),
            ) { "$id requires skillDirName and skillBody" }
        }
    }
}

/**
 * dsh 插件库目录。
 *
 * 所有路径都在 chroot 内用 `$HOME` 运行时解析：`DSH_HOME` 的真实位置取决于宿主启动时注入的
 * HOME，代码里硬编码必然出错。包名全部经过 registry 实际核对，不写未经证实的条目。
 */
internal object DshPluginCatalog {
    const val MIRROR_REGISTRY = "https://registry.npmmirror.com"

    /** dsh 的目标 profile；与用户手动执行 `dsh web` 时用的保持一致。 */
    const val PROFILE = "web"

    /** 探测脚本的输出前缀，便于从混合输出里稳定解析。 */
    const val PROBE_LINE_PREFIX = "ETA_PLUGIN"

    val ENTRIES: List<DshPluginEntry> = listOf(
        // ── dsh 插件：装到 profile 自己的 node_modules ────────────────────────────
        DshPluginEntry(
            id = "plugins-market",
            category = DshPluginCategory.DSH_PLUGIN,
            titleRes = R.string.linux_dsh_plugin_market_title,
            summaryRes = R.string.linux_dsh_plugin_market_summary,
            installKind = DshPluginInstallKind.DSH_PLUGIN,
            packageName = "dsh-agent-plugins-market",
        ),
        DshPluginEntry(
            id = "subagent-claude-code",
            category = DshPluginCategory.DSH_PLUGIN,
            titleRes = R.string.linux_dsh_plugin_subagent_cc_title,
            summaryRes = R.string.linux_dsh_plugin_subagent_cc_summary,
            installKind = DshPluginInstallKind.DSH_PLUGIN,
            packageName = "@deepseek-ai/dsh-subagent-claude-code",
        ),
        DshPluginEntry(
            id = "subagent-codex",
            category = DshPluginCategory.DSH_PLUGIN,
            titleRes = R.string.linux_dsh_plugin_subagent_codex_title,
            summaryRes = R.string.linux_dsh_plugin_subagent_codex_summary,
            installKind = DshPluginInstallKind.DSH_PLUGIN,
            packageName = "@deepseek-ai/dsh-subagent-codex",
        ),
        DshPluginEntry(
            id = "hooks-claude-code",
            category = DshPluginCategory.DSH_PLUGIN,
            titleRes = R.string.linux_dsh_plugin_hooks_cc_title,
            summaryRes = R.string.linux_dsh_plugin_hooks_cc_summary,
            installKind = DshPluginInstallKind.DSH_PLUGIN,
            packageName = "@deepseek-ai/dsh-hooks-claude-code",
        ),
        DshPluginEntry(
            id = "hooks-codex",
            category = DshPluginCategory.DSH_PLUGIN,
            titleRes = R.string.linux_dsh_plugin_hooks_codex_title,
            summaryRes = R.string.linux_dsh_plugin_hooks_codex_summary,
            installKind = DshPluginInstallKind.DSH_PLUGIN,
            packageName = "@deepseek-ai/dsh-hooks-codex",
        ),
        DshPluginEntry(
            id = "chat-import",
            category = DshPluginCategory.DSH_PLUGIN,
            titleRes = R.string.linux_dsh_plugin_chat_import_title,
            summaryRes = R.string.linux_dsh_plugin_chat_import_summary,
            installKind = DshPluginInstallKind.DSH_PLUGIN,
            packageName = "dsh-chat-import",
        ),

        // ── MCP 服务器：写 cordis patch 条目 ─────────────────────────────────────
        DshPluginEntry(
            id = "mcp-filesystem",
            category = DshPluginCategory.MCP_SERVER,
            titleRes = R.string.linux_dsh_mcp_filesystem_title,
            summaryRes = R.string.linux_dsh_mcp_filesystem_summary,
            installKind = DshPluginInstallKind.MCP_SERVER,
            packageName = "@modelcontextprotocol/server-filesystem",
            serverName = "filesystem",
        ),
        DshPluginEntry(
            id = "mcp-memory",
            category = DshPluginCategory.MCP_SERVER,
            titleRes = R.string.linux_dsh_mcp_memory_title,
            summaryRes = R.string.linux_dsh_mcp_memory_summary,
            installKind = DshPluginInstallKind.MCP_SERVER,
            packageName = "@modelcontextprotocol/server-memory",
            serverName = "memory",
        ),
        DshPluginEntry(
            id = "mcp-sequential-thinking",
            category = DshPluginCategory.MCP_SERVER,
            titleRes = R.string.linux_dsh_mcp_thinking_title,
            summaryRes = R.string.linux_dsh_mcp_thinking_summary,
            installKind = DshPluginInstallKind.MCP_SERVER,
            packageName = "@modelcontextprotocol/server-sequential-thinking",
            serverName = "thinking",
        ),
        DshPluginEntry(
            id = "mcp-github",
            category = DshPluginCategory.MCP_SERVER,
            titleRes = R.string.linux_dsh_mcp_github_title,
            summaryRes = R.string.linux_dsh_mcp_github_summary,
            installKind = DshPluginInstallKind.MCP_SERVER,
            packageName = "@modelcontextprotocol/server-github",
            serverName = "github",
            requiresToken = true,
        ),

        // ── CLI agent：npm 全局安装 ─────────────────────────────────────────────
        DshPluginEntry(
            id = "cli-claude-code",
            category = DshPluginCategory.CLI_AGENT,
            titleRes = R.string.linux_dsh_cli_claude_title,
            summaryRes = R.string.linux_dsh_cli_claude_summary,
            installKind = DshPluginInstallKind.NPM_GLOBAL,
            packageName = "@anthropic-ai/claude-code",
            commandName = "claude",
        ),
        DshPluginEntry(
            id = "cli-codex",
            category = DshPluginCategory.CLI_AGENT,
            titleRes = R.string.linux_dsh_cli_codex_title,
            summaryRes = R.string.linux_dsh_cli_codex_summary,
            installKind = DshPluginInstallKind.NPM_GLOBAL,
            packageName = "@openai/codex",
            commandName = "codex",
        ),
        DshPluginEntry(
            id = "cli-kimi",
            category = DshPluginCategory.CLI_AGENT,
            titleRes = R.string.linux_dsh_cli_kimi_title,
            summaryRes = R.string.linux_dsh_cli_kimi_summary,
            installKind = DshPluginInstallKind.NPM_GLOBAL,
            packageName = "@moonshot-ai/kimi-code",
            commandName = "kimi",
        ),
    ) + DshSkillContent.SKILL_ENTRIES

    fun byId(id: String): DshPluginEntry? = ENTRIES.firstOrNull { it.id == id }

    /** 生成批量探测脚本：逐条判断是否已安装，已安装的输出一行标记。 */
    fun probeScript(): String = buildString {
        append("set -e\n")
        ENTRIES.forEach { entry ->
            append("if ").append(probeCommand(entry)).append(" >/dev/null 2>&1; then\n")
            append("  printf '").append(PROBE_LINE_PREFIX).append(" %s\\n' ").append(entry.id).append('\n')
            append("fi\n")
        }
    }

    fun installScript(entry: DshPluginEntry): String = when (entry.installKind) {
        DshPluginInstallKind.DSH_PLUGIN -> dshPluginInstallScript(entry)
        DshPluginInstallKind.MCP_SERVER -> mcpInstallScript(entry)
        DshPluginInstallKind.SKILL_FILE -> skillInstallScript(entry)
        DshPluginInstallKind.NPM_GLOBAL -> npmGlobalInstallScript(entry)
    }

    fun uninstallScript(entry: DshPluginEntry): String = when (entry.installKind) {
        DshPluginInstallKind.DSH_PLUGIN -> dshPluginUninstallScript(entry)
        DshPluginInstallKind.MCP_SERVER -> mcpUninstallScript(entry)
        DshPluginInstallKind.SKILL_FILE -> skillUninstallScript(entry)
        DshPluginInstallKind.NPM_GLOBAL -> npmGlobalUninstallScript(entry)
    }

    /**
     * dsh 的插件命令把参数转发给 pnpm，而 pnpm 只有在 PATH 上才存在；
     * 缺 pnpm 时 dsh 会以 exit 127 失败，所以这里先补齐再安装。
     */
    private fun dshPluginInstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        return buildString {
            append("set -e\n")
            append("if ! command -v pnpm >/dev/null 2>&1; then\n")
            append("  echo 'eta: installing pnpm for dsh plugin management'\n")
            append("  npm install -g --prefix /usr/local --registry=").append(MIRROR_REGISTRY).append(" pnpm\n")
            append("fi\n")
            append("command -v pnpm >/dev/null 2>&1\n")
            append("dsh plugin --profile ").append(PROFILE).append(" add ").append(pkg).append('\n')
        }
    }

    private fun dshPluginUninstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        return buildString {
            append("set -e\n")
            append("if ! command -v pnpm >/dev/null 2>&1; then\n")
            append("  npm install -g --prefix /usr/local --registry=").append(MIRROR_REGISTRY).append(" pnpm\n")
            append("fi\n")
            // 没装过就别去 remove，避免 pnpm 因找不到依赖报错导致整个卸载失败。
            append("if grep -q '").append(pkg).append("' \"${'$'}HOME/.dsh/profiles/")
                .append(PROFILE).append("/package.json\" 2>/dev/null; then\n")
            append("  dsh plugin --profile ").append(PROFILE).append(" remove ").append(pkg).append('\n')
            append("fi\n")
        }
    }

    /**
     * MCP 条目写进 home 级 cordis.patch.yml，对所有 profile 生效。
     * 每个服务器占一对独立标记，卸载时按标记整段删除，不碰用户自己写的部分。
     */
    private fun mcpInstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        val server = requireNotNull(entry.serverName)
        val block = mcpBlock(entry)
        return buildString {
            append("set -e\n")
            append(mcpRemoveBlockCommand(server))
            append(mcpFileCommand())
            append("cat >> \"${'$'}HOME/.dsh/cordis.patch.yml\" <<'ETA_MCP_EOF'\n")
            append(block)
            append("ETA_MCP_EOF\n")
            append("grep -q 'eta-mcp-").append(server).append("' \"${'$'}HOME/.dsh/cordis.patch.yml\"\n")
        }.also { require(pkg.isNotBlank()) }
    }

    private fun mcpUninstallScript(entry: DshPluginEntry): String {
        val server = requireNotNull(entry.serverName)
        return buildString {
            append("set -e\n")
            append(mcpRemoveBlockCommand(server))
            append(mcpFileCommand())
            append("if grep -q 'eta-mcp-").append(server)
                .append("' \"${'$'}HOME/.dsh/cordis.patch.yml\"; then exit 1; fi\n")
        }
    }

    private fun mcpRemoveBlockCommand(server: String): String =
        "sed -i '/^# >>> eta mcp $server >>>$/,/^# <<< eta mcp $server <<<$/{d}' \"${'$'}HOME/.dsh/cordis.patch.yml\" 2>/dev/null || true\n"

    private fun mcpFileCommand(): String =
        "mkdir -p \"${'$'}HOME/.dsh\" && { [ -f \"${'$'}HOME/.dsh/cordis.patch.yml\" ] || : > \"${'$'}HOME/.dsh/cordis.patch.yml\"; }\n"

    private fun mcpBlock(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        val server = requireNotNull(entry.serverName)
        return buildString {
            append("# >>> eta mcp ").append(server).append(" >>>\n")
            append("# 由代鱼插件库管理，勿手改；改动会在下次安装/卸载时被覆盖。\n")
            append("- insert:\n")
            append("    - id: eta-mcp-").append(server).append('\n')
            append("      name: '@deepseek-ai/dsh-mcp-client'\n")
            append("      config:\n")
            append("        serverName: ").append(server).append('\n')
            append("        transport: stdio\n")
            append("        command: npx\n")
            append("        args: ['-y', '").append(pkg).append("']\n")
            append("        env:\n")
            append("          npm_config_registry: ").append(MIRROR_REGISTRY).append('\n')
            if (entry.requiresToken) {
                append("          GITHUB_TOKEN: !!js process.env.GITHUB_TOKEN\n")
            }
            append("# <<< eta mcp ").append(server).append(" <<<\n")
        }
    }

    /**
     * 技能就是 `$DSH_HOME/skills` 下的一个目录加一份 SKILL.md；
     * dsh 的 skill-filesystem 会热监听，新增后不必重启 dsh。
     */
    private fun skillInstallScript(entry: DshPluginEntry): String {
        val dir = requireNotNull(entry.skillDirName)
        val body = requireNotNull(entry.skillBody)
        return buildString {
            append("set -e\n")
            append("mkdir -p \"${'$'}HOME/.dsh/skills/").append(dir).append("\"\n")
            append("cat > \"${'$'}HOME/.dsh/skills/").append(dir).append("/SKILL.md\" <<'ETA_SKILL_EOF'\n")
            append(body)
            append("ETA_SKILL_EOF\n")
            append("test -s \"${'$'}HOME/.dsh/skills/").append(dir).append("/SKILL.md\"\n")
        }
    }

    /** 只删自己写的 SKILL.md，再用 rmdir 收掉空目录；目录里若还有别的文件就保留。 */
    private fun skillUninstallScript(entry: DshPluginEntry): String {
        val dir = requireNotNull(entry.skillDirName)
        return buildString {
            append("set -e\n")
            append("rm -f \"${'$'}HOME/.dsh/skills/").append(dir).append("/SKILL.md\"\n")
            append("rmdir \"${'$'}HOME/.dsh/skills/").append(dir).append("\" 2>/dev/null || true\n")
        }
    }

    private fun npmGlobalInstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        val cmd = entry.commandName
        return buildString {
            append("set -e\n")
            append("npm install -g --prefix /usr/local --registry=").append(MIRROR_REGISTRY)
                .append(' ').append(pkg).append(" || ")
                .append("npm install -g --prefix /usr/local ").append(pkg).append('\n')
            if (cmd != null) append("command -v ").append(cmd).append('\n')
        }
    }

    private fun npmGlobalUninstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        return buildString {
            append("set -e\n")
            append("npm uninstall -g --prefix /usr/local ").append(pkg).append(" || true\n")
        }
    }

    private fun probeCommand(entry: DshPluginEntry): String = when (entry.installKind) {
        DshPluginInstallKind.DSH_PLUGIN -> "grep -q '${requireNotNull(entry.packageName)}' " +
            "\"${'$'}HOME/.dsh/profiles/$PROFILE/package.json\""
        DshPluginInstallKind.MCP_SERVER ->
            "grep -q 'eta-mcp-${requireNotNull(entry.serverName)}' \"${'$'}HOME/.dsh/cordis.patch.yml\""
        DshPluginInstallKind.SKILL_FILE ->
            "test -s \"${'$'}HOME/.dsh/skills/${requireNotNull(entry.skillDirName)}/SKILL.md\""
        DshPluginInstallKind.NPM_GLOBAL -> "command -v ${requireNotNull(entry.commandName)}"
    }
}
