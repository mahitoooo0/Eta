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
    fun mcpInstallDelegatesToNodeWithGuardsNotSedRanges() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.MCP_SERVER
        }
        val script = DshPluginCatalog.installScript(entry)
        assertTrue(script.contains("cordis.patch.yml"))
        assertTrue(script.contains("@deepseek-ai/dsh-mcp-client"))
        // 绝对不能用 sed 地址区间删托管块：结束标记缺失时会一路删到文件末尾。
        assertFalse("不得用 sed 区间删托管块", script.contains("sed -i"))
        // 结束标记缺失必须拒绝动文件；写前备份；原子落盘；校验结束标记。
        assertTrue(script.contains("is truncated (end marker missing)"))
        assertTrue(script.contains(".eta-bak"))
        assertTrue(script.contains("renameSync"))
        assertTrue(script.contains("after.indexOf(END) >= 0"))
        assertTrue(DshPluginCatalog.uninstallScript(entry).contains("after.indexOf(START) < 0"))
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
    fun skillDirNameMatchesTheFrontmatterName() {
        // 两处字面量写歪的后果：dsh 按 frontmatter 发现了技能，探测却按目录名找文件，
        // 于是技能实际可用但 UI 永远显示「未安装」。
        DshPluginCatalog.ENTRIES
            .filter { it.installKind == DshPluginInstallKind.SKILL_FILE }
            .forEach { entry ->
                val body = requireNotNull(entry.skillBody)
                val declared = Regex("^name: (.+)$", RegexOption.MULTILINE)
                    .find(body)?.groupValues?.get(1)?.trim()
                assertEquals("${entry.id} 的目录名与 frontmatter name 不一致", entry.skillDirName, declared)
                assertTrue(
                    "${entry.id} 的 SKILL.md 缺少归属标记",
                    body.contains(DshPluginCatalog.SKILL_OWNER_MARKER),
                )
            }
    }

    @Test
    fun skillUninstallRefusesToTouchFilesWeDoNotOwn() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.SKILL_FILE
        }
        val uninstall = DshPluginCatalog.uninstallScript(entry)
        // 归属标记检查是防止误删用户自己同名技能的唯一防线，不能被优化掉。
        assertTrue(uninstall.contains(DshPluginCatalog.SKILL_OWNER_MARKER))
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
    fun harnessPluginProbeFallsBackToTheLockfile() {
        val entry = DshPluginCatalog.ENTRIES.first {
            it.installKind == DshPluginInstallKind.DSH_PLUGIN
        }
        val script = DshPluginCatalog.probeScript()
        val line = script.lineSequence().firstOrNull { it.contains("dsh plugin") || it.contains(requireNotNull(entry.packageName)) }
        assertNotNull("探测脚本里应能找到该包的探测分支", line)
        assertTrue("package.json 落点之外还应兜底 lockfile", requireNotNull(line).contains("pnpm-lock.yaml"))
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
    fun everyProbeSitsInAnIfConditionSoErrexitNeverSkipsTheRest() {
        // POSIX `set -e` 对 if/while/until 条件部分、以及 && || 列表的非末项都豁免。
        // 所以「某条探测返回非 0 就把整条脚本带崩」这个担心不成立——但前提是每条探测
        // 真的都写在条件位上。这里把这条不变量钉住，防止以后改成裸命令而无声退化。
        val script = DshPluginCatalog.probeScript()
        val ifCount = Regex("^if ", RegexOption.MULTILINE).findAll(script).count()
        val fiCount = Regex("^fi$", RegexOption.MULTILINE).findAll(script).count()
        assertEquals("if 与 fi 必须成对", ifCount, fiCount)
        assertEquals("每个条目一个探测分支", DshPluginCatalog.ENTRIES.size, ifCount)
        // 条件位之外不允许再有裸命令，否则 set -e 会在探测阶段真的生效并提前中断。
        val bare = script.lineSequence().filter { line ->
            val t = line.trim()
            t.isNotEmpty() && t != "set -e" && !t.startsWith("if ") && t != "fi" &&
                !t.startsWith("printf ") && !t.endsWith("; then") && !t.startsWith("then")
        }.toList()
        assertTrue("探测脚本里出现了条件位之外的裸命令: $bare", bare.isEmpty())
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
