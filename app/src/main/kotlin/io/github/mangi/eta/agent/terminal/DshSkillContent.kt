package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.R

/**
 * 插件库内置的 Agent 技能。
 *
 * 技能是 dsh 的一等公民：web profile 默认 preset 已挂载 `skill-filesystem` 与 `tool-skill`，
 * 只要在 `$DSH_HOME/skills/<dir>/SKILL.md` 落盘即可被发现，且目录被热监听，无需重启 dsh。
 * 因此这里不需要任何插件依赖，纯靠写文件生效——这是插件库里最可靠的一类。
 *
 * 正文一律避免出现 heredoc 结束标记，防止安装脚本被提前截断。
 */
internal object DshSkillContent {
    private val WEB_DIGEST = """
        ---
        name: web-digest
        description: 把一篇长网页或一组链接读成可执行的要点，而不是复述全文。
        ---

        <!-- ${DshPluginCatalog.SKILL_OWNER_MARKER} -->

        # 网页速读

        适用于「这篇文章讲了什么 / 我该不该读 / 重点是哪几条」这类请求。

        ## 步骤

        1. 先用 `web_fetch` 抓取目标 URL。抓取失败就如实说明失败原因，不要凭 URL 猜内容。
        2. 通读后先给一句话结论，再展开要点。要点控制在 3 到 7 条，每条不超过一行。
        3. 单独标出「与你当前任务相关」的部分；如果没有，明确说没有，不要硬凑。
        4. 最后给出可执行动作（要读什么、要改什么、要不要存下来），而不是泛泛的感想。

        ## 约束

        - 不要逐段复述原文。用户要的是判断，不是搬运。
        - 数字、日期、版本号、命令、路径必须原文引用，不要改写。
        - 观点和事实要分开：作者的主张标注为「作者认为」。
        - 只抓了部分内容（付费墙、登录墙、超长截断）时，明说抓到的范围。
    """.trimIndent() + "\n"
    private val REPO_BRIEF = """
        ---
        name: repo-brief
        description: 快速摸清一个陌生仓库的结构、构建方式与风险点，适合接手别人的项目。
        ---

        <!-- ${DshPluginCatalog.SKILL_OWNER_MARKER} -->

        # 仓库速览

        适用于「这个仓库是干什么的 / 怎么构建 / 从哪下手看」这类请求。

        ## 步骤

        1. 先看顶层清单：`README`、`LICENSE`、`package.json` 或 `pyproject.toml` 或
           `build.gradle`、`Makefile`、CI 配置。有 README 就先读完再动手。
        2. 找出真正的入口点：可执行入口、路由注册处、`main`、`index`、启动脚本。
           顺着入口读一层，不要一上来就遍历全部文件。
        3. 判断构建与测试方式：实际执行一次最小验证（安装依赖、跑一次测试或构建），
           把真实命令和结果记下来，而不是抄 README 里的命令。
        4. 指出风险：未提交的生成物、硬编码路径与密钥、缺失的测试、超大文件、
           明显的临时代码。

        ## 输出

        用四段：项目一句话定位、目录导航（不超过 8 条）、可复现的构建与测试命令、风险清单。
        每条结论都要能指向具体文件。
    """.trimIndent() + "\n"
    private val DAILY_REPORT = """
        ---
        name: daily-report
        description: 从真实工作痕迹生成日报或周报，不编造没有发生过的进展。
        ---

        <!-- ${DshPluginCatalog.SKILL_OWNER_MARKER} -->

        # 工作日报

        ## 步骤

        1. 先确认素材来源：git 提交记录、任务清单、issue、或者用户口述。
           素材不足就直接说明缺什么，不要用常识补全。
        2. 按「进展 / 结论 / 阻塞」三段组织。每条进展都要能对应到具体提交、文件或决定。
        3. 单独列出未完成项和它们卡在哪里，下一步是什么。
        4. 涉及日期、编号、数量时核对原始记录。

        ## 约束

        - 只写真的做过的事。没有证据的进展一律不写，宁可短。
        - 不把「开始做」写成「完成」。
        - 全文控制在 300 字以内，手机上要能一屏读完。
    """.trimIndent() + "\n"
    private val CHANGE_REVIEW = """
        ---
        name: change-review
        description: 审查一段改动，按正确性、边界、可测性排序给出可执行的修改意见。
        ---

        <!-- ${DshPluginCatalog.SKILL_OWNER_MARKER} -->

        # 改动审查

        ## 步骤

        1. 先看改动的意图：读提交信息、issue 或用户描述，不清楚就先问。
        2. 逐个文件看差异，重点找这几类问题：
           - 正确性：空值、越界、并发、错误被吞掉、退出码被忽略
           - 边界：输入校验缺失、资源未释放、超时未设置、重试无上限
           - 可测性：新增逻辑没有对应测试、测试只断言了实现细节
           - 影响面：改了共享路径却没更新调用方，或枚举新增导致穷举 `when` 编译失败
        3. 每条意见给出：问题、触发条件、建议改法。指明文件和行。
        4. 先说阻塞项，再说可选改进。不要把风格偏好混成缺陷。

        ## 约束

        - 不为了显得严谨而罗列无关紧要的问题。没有实质问题就直说没有。
        - 不给没有依据的断言，猜测要标明是猜测。
    """.trimIndent() + "\n"

    val SKILL_ENTRIES: List<DshPluginEntry> = listOf(
        DshPluginEntry(
            id = "skill-web-digest",
            category = DshPluginCategory.AGENT_SKILL,
            titleRes = R.string.linux_dsh_skill_web_digest_title,
            summaryRes = R.string.linux_dsh_skill_web_digest_summary,
            installKind = DshPluginInstallKind.SKILL_FILE,
            skillDirName = "web-digest",
            skillBody = WEB_DIGEST.trimIndent(),
        ),
        DshPluginEntry(
            id = "skill-repo-brief",
            category = DshPluginCategory.AGENT_SKILL,
            titleRes = R.string.linux_dsh_skill_repo_brief_title,
            summaryRes = R.string.linux_dsh_skill_repo_brief_summary,
            installKind = DshPluginInstallKind.SKILL_FILE,
            skillDirName = "repo-brief",
            skillBody = REPO_BRIEF.trimIndent(),
        ),
        DshPluginEntry(
            id = "skill-daily-report",
            category = DshPluginCategory.AGENT_SKILL,
            titleRes = R.string.linux_dsh_skill_daily_report_title,
            summaryRes = R.string.linux_dsh_skill_daily_report_summary,
            installKind = DshPluginInstallKind.SKILL_FILE,
            skillDirName = "daily-report",
            skillBody = DAILY_REPORT.trimIndent(),
        ),
        DshPluginEntry(
            id = "skill-change-review",
            category = DshPluginCategory.AGENT_SKILL,
            titleRes = R.string.linux_dsh_skill_change_review_title,
            summaryRes = R.string.linux_dsh_skill_change_review_summary,
            installKind = DshPluginInstallKind.SKILL_FILE,
            skillDirName = "change-review",
            skillBody = CHANGE_REVIEW.trimIndent(),
        ),
    )
}
