package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPluginCatalogTest {
    @Test
    fun entryIdsAreUniqueAndResolvable() {
        val ids = DshPluginCatalog.ENTRIES.map { it.id }
        assertEquals("插件 id 不得重复", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertNotNull("byId($id) 应能查到", DshPluginCatalog.byId(id))
        }
    }

    @Test
    fun everyCategoryHasAtLeastOneEntry() {
        DshPluginCategory.entries.forEach { category ->
            assertTrue(
                "分类 $category 没有任何条目",
                DshPluginCatalog.ENTRIES.any { it.category == category },
            )
        }
    }

    @Test
    fun everyEntryCarriesTheFieldsItsInstallKindNeeds() {
        DshPluginCatalog.ENTRIES.forEach { entry ->
            when (entry.installKind) {
                DshPluginInstallKind.DSH_PLUGIN,
                DshPluginInstallKind.MCP_SERVER,
                DshPluginInstallKind.NPM_GLOBAL,
                -> assertTrue("${entry.id} 缺少包名", !entry.packageName.isNullOrBlank())

                DshPluginInstallKind.SKILL_FILE -> {
                    assertTrue("${entry.id} 缺少技能目录名", !entry.skillDirName.isNullOrBlank())
                    assertTrue("${entry.id} 缺少技能正文", !entry.skillBody.isNullOrBlank())
                }
            }
        }
    }

    @Test
    fun harnessPluginInstallBootstrapsPnpmBecauseDshForwardsToIt() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.DSH_PLUGIN
        }
        val script = DshPluginCatalog.installScript(entry)
        val pkg = requireNotNull(entry.packageName)
        // dsh plugin 把参数转发给 pnpm，缺 pnpm 会以 exit 127 失败，所以必须先补。
        assertTrue(script.contains("command -v pnpm"))
        assertTrue(script.contains("npm install -g --prefix /usr/local"))
        assertTrue(script.contains("dsh plugin --profile ${DshPluginCatalog.PROFILE} add $pkg"))
    }

    @Test
    fun mcpInstallWritesHomeLevelPatchInsideManagedMarkers() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.MCP_SERVER
        }
        val server = requireNotNull(entry.serverName)
        val script = DshPluginCatalog.installScript(entry)
        assertTrue(script.contains("cordis.patch.yml"))
        assertTrue(script.contains("@deepseek-ai/dsh-mcp-client"))
        assertTrue(script.contains("serverName: $server"))
        // 卸载按标记整段删，不能波及用户自己写的内容。
        assertTrue(script.contains("# >>> eta mcp $server >>>"))
        assertTrue(script.contains("# <<< eta mcp $server <<<"))
        assertTrue(DshPluginCatalog.uninstallScript(entry).contains("# <<< eta mcp $server <<<"))
    }

    @Test
    fun mcpEntriesUseDistinctServerNames() {
        val servers = DshPluginCatalog.ENTRIES
            .filter { it.installKind == DshPluginInstallKind.MCP_SERVER }
            .map { requireNotNull(it.serverName) }
        assertEquals(servers.size, servers.toSet().size)
    }

    @Test
    fun skillInstallLandsInDshHomeSkillsDir() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.SKILL_FILE
        }
        val dir = requireNotNull(entry.skillDirName)
        val script = DshPluginCatalog.installScript(entry)
        assertTrue(script.contains("\$HOME/.dsh/skills/$dir/SKILL.md"))
        // 卸载只删自己写的文件，目录里有别的东西就保留。
        val uninstall = DshPluginCatalog.uninstallScript(entry)
        assertTrue(uninstall.contains("rm -f"))
        assertFalse("卸载技能不得直接 rm -rf", uninstall.contains("rm -rf"))
    }

    @Test
    fun skillBodiesNeverContainTheHeredocTerminator() {
        // 结束标记出现在正文里会把安装脚本拦腰截断，进而写坏 SKILL.md。
        DshPluginCatalog.ENTRIES
            .filter { it.installKind == DshPluginInstallKind.SKILL_FILE }
            .forEach { entry ->
                assertFalse(
                    "${entry.id} 的 SKILL.md 含 heredoc 结束标记",
                    requireNotNull(entry.skillBody).contains("ETA_SKILL_EOF"),
                )
                assertTrue(
                    "${entry.id} 的 SKILL.md 缺少 frontmatter",
                    requireNotNull(entry.skillBody).trimStart().startsWith("---"),
                )
            }
    }

    @Test
    fun npmGlobalInstallPrefersMirrorAndFallsBack() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.NPM_GLOBAL
        }
        val script = DshPluginCatalog.installScript(entry)
        assertTrue(script.contains("--registry=${DshPluginCatalog.MIRROR_REGISTRY}"))
        // 镜像与官方源各试一次，脚本才会出现两条 npm install。
        assertEquals(2, Regex("npm install -g").findAll(script).count())
    }

    @Test
    fun probeScriptCoversEveryEntryExactlyOnce() {
        val script = DshPluginCatalog.probeScript()
        DshPluginCatalog.ENTRIES.forEach { entry ->
            // 脚本里是 `printf 'ETA_PLUGIN %s\n' <id>`，占位符由 shell 在运行时替换；
            // Kotlin 里 `\\n` 才是脚本里那一个反斜杠加 n。
            val line = "printf '${DshPluginCatalog.PROBE_LINE_PREFIX} %s\\n' ${entry.id}"
            assertEquals(
                "条目 ${entry.id} 的探测分支应恰好出现一次",
                1,
                Regex(Regex.escape(line)).findAll(script).count(),
            )
        }
    }

    @Test
    fun probeScriptFailsFastOnShellErrors() {
        assertTrue(DshPluginCatalog.probeScript().trimStart().startsWith("set -e"))
    }

    @Test
    fun everyInstallAndUninstallScriptStartsWithFailFast() {
        DshPluginCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.id} 安装脚本缺少 set -e",
                DshPluginCatalog.installScript(entry).trimStart().startsWith("set -e"),
            )
            assertTrue(
                "${entry.id} 卸载脚本缺少 set -e",
                DshPluginCatalog.uninstallScript(entry).trimStart().startsWith("set -e"),
            )
        }
    }
}
