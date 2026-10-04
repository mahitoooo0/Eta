package io.github.mangi.eta.agent.terminal

import androidx.annotation.StringRes
import io.github.mangi.eta.R

/**
 * 插件库分类。四个分类对应四种完全不同的落地方式，不能混为一谈。
 */
internal enum class DshPluginCategory(val installKind: DshPluginInstallKind) {
    /** 通过 `dsh plugin --profile <name> add` 安装的 dsh 插件包。 */
    DSH_PLUGIN(DshPluginInstallKind.DSH_PLUGIN),

    /** 通过 `$DSH_HOME/cordis.patch.yml` 追加 patch 条目启用的 MCP 服务器。 */
    MCP_SERVER(DshPluginInstallKind.MCP_SERVER),

    /** 写入 `$DSH_HOME/skills/<dir>/SKILL.md` 的 Agent 技能。 */
    AGENT_SKILL(DshPluginInstallKind.SKILL_FILE),

    /** 通过 `npm install -g` 安装的独立 CLI agent。 */
    CLI_AGENT(DshPluginInstallKind.NPM_GLOBAL),
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
        // 分类与安装方式必须一一对应；漂移会让 UI 归类与实际安装行为不一致。
        require(category.installKind == installKind) {
            "$id: 分类 $category 与安装方式 $installKind 不匹配"
        }
        when (installKind) {
            // DSH_PLUGIN / NPM_GLOBAL 是安装目标；MCP_SERVER 只是 npx 拉起的包名。
            DshPluginInstallKind.DSH_PLUGIN -> require(!packageName.isNullOrBlank()) { "$id requires packageName" }
            DshPluginInstallKind.NPM_GLOBAL -> {
                require(!packageName.isNullOrBlank()) { "$id requires packageName" }
                require(!commandName.isNullOrBlank()) { "$id requires commandName" }
            }
            DshPluginInstallKind.MCP_SERVER -> {
                require(!packageName.isNullOrBlank()) { "$id requires packageName" }
                require(!serverName.isNullOrBlank()) { "$id requires serverName" }
            }
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

    /** 技能安装时写进 SKILL.md 的归属标记；探测与卸载都靠它避免误删用户自己写的同名技能。 */
    const val SKILL_OWNER_MARKER = "managed-by: eta-plugin-library"

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
        append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
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
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
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
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
            append("if ! command -v pnpm >/dev/null 2>&1; then\n")
            append("  npm install -g --prefix /usr/local --registry=").append(MIRROR_REGISTRY).append(" pnpm\n")
            append("fi\n")
            // 没装过就别去 remove，避免 pnpm 因找不到依赖报错导致整个卸载失败。
            append("if grep -q '").append(pkg).append("' \"${'$'}HOME/.dsh/profiles/")
                .append(PROFILE).append("/package.json\" \"${'$'}HOME/.dsh/profiles/")
                .append(PROFILE).append("/pnpm-lock.yaml\" 2>/dev/null; then\n")
            append("  dsh plugin --profile ").append(PROFILE).append(" remove ").append(pkg).append('\n')
            append("fi\n")
        }
    }

    /**
     * MCP 条目写进 home 级 cordis.patch.yml，对所有 profile 生效。
     * 每个服务器占一对独立标记，卸载时按标记整段删除，不碰用户自己写的部分。
     */
    private fun mcpInstallScript(entry: DshPluginEntry): String = mcpScript(entry, install = true)

    private fun mcpUninstallScript(entry: DshPluginEntry): String = mcpScript(entry, install = false)

    /**
     * MCP 条目写进 home 级 `cordis.patch.yml`，对所有 profile 生效。
     *
     * 这里刻意**不用 sed 的地址区间**去删托管块：`/起始标记/,/结束标记/` 在结束标记缺失时
     * 会一路匹配到文件末尾，把用户自己写的配置全部删掉。而半截块恰恰是可能出现的——
     * 写到一半被中断（关掉安装中的面板就会触发）就会留下有开始标记、没有结束标记的文件。
     *
     * 改用 Node 脚本（dsh 本身就依赖 node，客户机里一定有）做四件事：
     * 1. 结束标记缺失就**拒绝动文件**并以非 0 退出，宁可不装也不毁配置；
     * 2. 写之前把原文件备份成 `cordis.patch.yml.eta-bak`，用户可自己恢复；
     * 3. 临时文件 + `rename` 原子落盘，不会留下写一半的文件；
     * 4. 校验**结束标记**（而不是开始标记）确实在文件里，才算装成功。
     */
    private fun mcpScript(entry: DshPluginEntry, install: Boolean): String {
        val server = requireNotNull(entry.serverName)
        return buildString {
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
            append("ETA_MCP_MODE=").append(if (install) "install" else "uninstall")
                .append(" ETA_MCP_SERVER=").append(server)
                .append(" ETA_MCP_PKG=").append(requireNotNull(entry.packageName))
                .append(" ETA_MCP_TOKEN=").append(if (entry.requiresToken) "1" else "0")
                .append(" node <<'ETA_MCP_NODE_EOF'\n")
            append(MCP_NODE_SCRIPT)
            append("ETA_MCP_NODE_EOF\n")
        }
    }

    /**
     * 在 chroot 内执行的 Node 脚本。整段没有 shell 插值，heredoc 用单引号定界即可；
     * 脚本里的 `\n` 由 JS 自己解析，这里保持字面两字符。
     */
    private val MCP_NODE_SCRIPT = """
        const fs = require('fs');
        const path = require('path');
        const dir = path.join(process.env.HOME, '.dsh');
        const file = path.join(dir, 'cordis.patch.yml');
        const server = process.env.ETA_MCP_SERVER;
        const pkg = process.env.ETA_MCP_PKG;
        const install = process.env.ETA_MCP_MODE === 'install';
        const useToken = process.env.ETA_MCP_TOKEN === '1';
        const START = '# >>> eta mcp ' + server + ' >>>';
        const END = '# <<< eta mcp ' + server + ' <<<';
        const BAIL = 3;
        const VERIFY_FAIL = 4;
        fs.mkdirSync(dir, { recursive: true });
        let text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
        if (text.length > 0 && !text.endsWith('\n')) text += '\n';
        if (text.indexOf(START) >= 0) {
          const from = text.indexOf(START);
          const to = text.indexOf(END, from);
          if (to < 0) {
            console.error('eta: managed block for ' + server + ' is truncated (end marker missing);' +
              ' refusing to modify ' + file + '. Fix or remove that block by hand.');
            process.exit(BAIL);
          }
          let cut = to + END.length;
          if (text[cut] === '\n') cut += 1;
          text = text.slice(0, from) + text.slice(cut);
        }
        if (install) {
          if (text.length > 0 && !text.endsWith('\n')) text += '\n';
          text += [
            START,
            '# 由代鱼插件库管理，勿手改；改动会在下次安装/卸载时被覆盖。',
            '- insert:',
            '    - id: eta-mcp-' + server,
            "      name: '@deepseek-ai/dsh-mcp-client'",
            '      config:',
            '        serverName: ' + server,
            '        transport: stdio',
            '        command: npx',
            "        args: ['-y', '" + pkg + "']",
            '        env:',
            '          npm_config_registry: MIRROR_PLACEHOLDER',
            useToken ? '          GITHUB_TOKEN: !!js process.env.GITHUB_TOKEN' : null,
            END,
            '',
          ].filter((line) => line !== null).join('\n');
        }
        if (fs.existsSync(file) && fs.statSync(file).size > 0) {
          fs.copyFileSync(file, file + '.eta-bak');
        }
        fs.writeFileSync(file + '.eta-tmp', text);
        fs.renameSync(file + '.eta-tmp', file);
        const after = fs.readFileSync(file, 'utf8');
        const ok = install ? after.indexOf(END) >= 0 : after.indexOf(START) < 0;
        if (!ok) {
          console.error('eta: managed block verification failed for ' + server);
          process.exit(VERIFY_FAIL);
        }
    """.trimIndent().replace("MIRROR_PLACEHOLDER", MIRROR_REGISTRY) + "\n"

    /**
     * 技能就是 `$DSH_HOME/skills` 下的一个目录加一份 SKILL.md；
     * dsh 的 skill-filesystem 会热监听，新增后不必重启 dsh。
     */
    private fun skillInstallScript(entry: DshPluginEntry): String {
        val dir = requireNotNull(entry.skillDirName)
        val body = requireNotNull(entry.skillBody)
        return buildString {
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
            append("mkdir -p \"${'$'}HOME/.dsh/skills/").append(dir).append("\"\n")
            append("cat > \"${'$'}HOME/.dsh/skills/").append(dir).append("/SKILL.md\" <<'ETA_SKILL_EOF'\n")
            append(body)
            append("ETA_SKILL_EOF\n")
            append("grep -q '").append(SKILL_OWNER_MARKER)
                .append("' \"${'$'}HOME/.dsh/skills/").append(dir).append("/SKILL.md\"\n")
        }
    }

    /**
     * 只删带归属标记的 SKILL.md——用户自己在同名目录写的技能不能被误删。
     * 之后用 rmdir 收掉空目录；目录里若还有别的文件就保留。
     */
    private fun skillUninstallScript(entry: DshPluginEntry): String {
        val dir = requireNotNull(entry.skillDirName)
        return buildString {
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
            append("f=\"${'$'}HOME/.dsh/skills/").append(dir).append("/SKILL.md\"\n")
            append("if [ -f \"\$f\" ] && grep -q '").append(SKILL_OWNER_MARKER).append("' \"\$f\"; then\n")
            append("  rm -f \"\$f\"\n")
            append("fi\n")
            append("rmdir \"${'$'}HOME/.dsh/skills/").append(dir).append("\" 2>/dev/null || true\n")
        }
    }

    private fun npmGlobalInstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        val cmd = entry.commandName
        return buildString {
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
            append("npm install -g --prefix /usr/local --registry=").append(MIRROR_REGISTRY)
                .append(' ').append(pkg).append(" || ")
                .append("npm install -g --prefix /usr/local ").append(pkg).append('\n')
            if (cmd != null) append("command -v ").append(cmd).append('\n')
        }
    }

    private fun npmGlobalUninstallScript(entry: DshPluginEntry): String {
        val pkg = requireNotNull(entry.packageName)
        return buildString {
            append("set -e\n").append(LinuxPackageProfiles.PROXY_BOOTSTRAP_SHELL)
            append("npm uninstall -g --prefix /usr/local ").append(pkg).append(" || true\n")
        }
    }

    private fun probeCommand(entry: DshPluginEntry): String = when (entry.installKind) {
        // package.json 是 pnpm 写依赖的位置；lockfile 兜底以防版本差异改了落点。
        DshPluginInstallKind.DSH_PLUGIN -> "grep -q '${requireNotNull(entry.packageName)}' " +
            "\"${'$'}HOME/.dsh/profiles/$PROFILE/package.json\" " +
            "\"${'$'}HOME/.dsh/profiles/$PROFILE/pnpm-lock.yaml\""
        DshPluginInstallKind.MCP_SERVER ->
            "grep -q 'eta-mcp-${requireNotNull(entry.serverName)}' \"${'$'}HOME/.dsh/cordis.patch.yml\""
        DshPluginInstallKind.SKILL_FILE ->
            "grep -q '$SKILL_OWNER_MARKER' " +
                "\"${'$'}HOME/.dsh/skills/${requireNotNull(entry.skillDirName)}/SKILL.md\""
        DshPluginInstallKind.NPM_GLOBAL -> "command -v ${requireNotNull(entry.commandName)}"
    }
}
