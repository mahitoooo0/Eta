package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    DOWNLOADING,
    INSTALLING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult

    /** 依赖的 profile 尚未安装，按依赖链先装它。 */
    data class DependencyMissing(val profileId: String) : PackageProfileInstallResult
    data object Installed : PackageProfileInstallResult
    data class Failed(val stage: PackageProfileInstallStage) : PackageProfileInstallResult
}

internal data class LinuxPackageSpec(
    val packages: List<String> = emptyList(),
    val managedTool: ManagedLinuxTool? = null,
    val setupScript: String? = null,
)

internal data class LinuxPackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val specs: Map<LinuxDistribution, LinuxPackageSpec>,
    /** 安装前必须就绪的前置 profile。 */
    val dependsOn: LinuxPackageProfile? = null,
    /**
     * 写入完成标记前必须通过的校验命令；用于拦截「npm 报成功但产物不可用」的情况
     * （例如平台二进制、原生扩展缺失）。任一行非零退出即视为安装失败。
     */
    val verifyScript: String? = null,
) {
    fun spec(distribution: LinuxDistribution): LinuxPackageSpec = requireNotNull(specs[distribution])
}

internal object LinuxPackageProfiles {
    private const val UV_PYTHON_SETUP_SCRIPT =
        "UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin " +
            "UV_PYTHON_INSTALL_BIN=1 uv python install --default --force"

    private const val SSH_KEYGEN_SETUP_SCRIPT = "ssh-keygen -A >/dev/null 2>&1 || true"

    val PYTHON = LinuxPackageProfile(
        id = "python",
        markerName = AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = UV_PYTHON_SETUP_SCRIPT,
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = UV_PYTHON_SETUP_SCRIPT,
            ),
            LinuxDistribution.UBUNTU to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = UV_PYTHON_SETUP_SCRIPT,
            ),
        ),
    )
    val NODE = LinuxPackageProfile(
        id = "node",
        markerName = AlpineEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.NODE_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("nodejs-current", "npm"),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                // Node 官方 arm64 二进制链接 libatomic.so.1，归档安装不含系统依赖，需补装。
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
            ),
            LinuxDistribution.UBUNTU to LinuxPackageSpec(
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
            ),
        ),
    )
    val SSH = LinuxPackageProfile(
        id = "ssh",
        markerName = AlpineEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.SSH_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("openssh"),
                setupScript = SSH_KEYGEN_SETUP_SCRIPT,
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = SSH_KEYGEN_SETUP_SCRIPT,
            ),
            LinuxDistribution.UBUNTU to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = SSH_KEYGEN_SETUP_SCRIPT,
            ),
        ),
    )

    /**
     * Kimi Code 使用 npm 分发，运行在 Node profile 之上；可选原生扩展由 npm 按平台安装。
     * 始终安装最新正式版（升级重装即可）；--prefix /usr/local 让 kimi 进入 PATH 首位，
     * 与 Node 归档自身的 prefix 无关。国内镜像优先，官方 registry 兜底。
     */
    private const val KIMI_INSTALL_SCRIPT =
        "npm install -g --prefix /usr/local --registry=https://registry.npmmirror.com " +
            "@moonshot-ai/kimi-code@latest || " +
            "npm install -g --prefix /usr/local @moonshot-ai/kimi-code@latest"

    val KIMI = LinuxPackageProfile(
        id = "kimi",
        markerName = AlpineEnvironmentPaths.KIMI_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.KIMI_TOOLS_REVISION,
        dependsOn = NODE,
        verifyScript = "kimi --version >/dev/null\nkimi web --help >/dev/null",
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
            LinuxDistribution.UBUNTU to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
        ),
    )

    /** 国内镜像源；官方 registry 在墙内不可达，重试也必须留在这里。 */
    private const val DSH_MIRROR_REGISTRY = "--registry=https://registry.npmmirror.com"

    /**
     * DeepSeek Harness（dsh）同样由 npm 分发，运行在 Node profile 之上。
     * 它比 Kimi 多一个原生依赖：`@deepseek-ai/dsh-fs-local` 依赖 koffi。koffi 的
     * 安装脚本会 `require` 自己来探测平台预编译包（`@koromix/koffi-linux-arm64`）；
     * 预编译包缺失时（例如 registry 不可达导致 optionalDependencies 被静默跳过）
     * 它会退回源码编译，此时需要 cmake + 工具链。
     *
     * 因此这里失败后先补编译工具链，再**留在镜像源上**重试一次——换回官方源毫无意义，
     * 墙内根本连不上。完成标记前额外校验 `dsh --version`，避免「装上了但跑不起来」
     * 被当成成功。
     */
    private const val DSH_INSTALL_SCRIPT =
        "npm install -g --prefix /usr/local $DSH_MIRROR_REGISTRY " +
            "@deepseek-ai/dsh@latest || {\n" +
            "echo 'eta: dsh install failed; adding build toolchain for koffi and retrying'\n" +
            "if [ -x /usr/local/bin/eta-apt ]; then /usr/local/bin/eta-apt install cmake build-essential; fi\n" +
            "if [ -x /usr/local/bin/eta-apk ]; then /usr/local/bin/eta-apk install cmake build-base; fi\n" +
            "npm install -g --prefix /usr/local $DSH_MIRROR_REGISTRY @deepseek-ai/dsh@latest\n" +
            "}"

    val DSH = LinuxPackageProfile(
        id = "dsh",
        markerName = AlpineEnvironmentPaths.DSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.DSH_TOOLS_REVISION,
        dependsOn = NODE,
        verifyScript = "dsh --version >/dev/null",
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(setupScript = DSH_INSTALL_SCRIPT),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(setupScript = DSH_INSTALL_SCRIPT),
            LinuxDistribution.UBUNTU to LinuxPackageSpec(setupScript = DSH_INSTALL_SCRIPT),
        ),
    )
    val ALL = listOf(PYTHON, NODE, SSH, KIMI, DSH)
}

internal fun linuxPackageProfileReady(rootfs: File, profile: LinuxPackageProfile): Boolean =
    LinuxEnvironmentPaths.markerSatisfied(
        File(rootfs, profile.markerName),
        "profile=${profile.revision}",
    )

/** 为当前选中的发行版按需安装单个工具 profile；成功后只写对应完成标记。 */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)

    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        profile.dependsOn?.let { dependency ->
            if (!linuxPackageProfileReady(rootfs, dependency)) {
                return@withContext PackageProfileInstallResult.DependencyMissing(dependency.id)
            }
        }

        val spec = profile.spec(distribution)
        spec.managedTool?.let { tool ->
            val installed = managedToolInstaller.install(
                tool = tool,
                distribution = distribution,
                rootfs = rootfs,
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!installed) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                )
            }
        }

        val packageHelper = when (distribution) {
            LinuxDistribution.ALPINE -> "/usr/local/bin/eta-apk"
            LinuxDistribution.DEBIAN, LinuxDistribution.UBUNTU -> "/usr/local/bin/eta-apt"
        }
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        if (spec.packages.isNotEmpty()) {
            val installResult = InstallerShellRunner.run(
                command = "$packageHelper install ${spec.packages.joinToString(" ")}",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (installResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                )
            }
        }

        val activateCommand = buildString {
            append("set -e\n")
            spec.setupScript?.let { script -> append(script).append('\n') }
            profile.verifyScript?.let { script -> append(script).append('\n') }
            append("cat > /").append(profile.markerName).append(" <<'ETA_PROFILE_EOF'\n")
            append("profile=").append(profile.revision).append('\n')
            append("ETA_PROFILE_EOF\n")
            append("chmod 0644 /").append(profile.markerName).append(" || exit 71")
        }
        val result = InstallerShellRunner.run(
            command = activateCommand,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=activate distribution=${distribution.wireName} profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        if (result.exitCode != 0) {
            // 失败时把命令输出落盘。UI 只能给「安装失败，请稍后重试」这类泛化提示，
            // 真正的原因（npm 报错原文、apt 镜像报错）只存在于这段输出里，
            // 不落盘就无从定位——dsh 首次安装失败时靠它才查得清。
            val failureLog = File(context.cacheDir, "eta-profile-failures.log")
            runCatching {
                failureLog.appendText(
                    "==== ${profile.id} @ ${distribution.wireName} exit=${result.exitCode} ====\n" +
                        result.output.takeLast(4000) + "\n\n",
                )
                AndroidAgentLogger.info("Package profile failure detail at ${failureLog.absolutePath}")
            }
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}
