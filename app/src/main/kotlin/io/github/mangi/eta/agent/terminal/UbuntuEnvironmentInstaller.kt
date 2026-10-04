package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal enum class UbuntuEnvironmentState {
    NOT_INSTALLED,
    BASE_READY,
    READY,
}

internal data class UbuntuEnvironmentStatus(
    val state: UbuntuEnvironmentState,
    val version: String? = null,
)

internal data class UbuntuAptMirror(
    val id: String,
    val archiveBaseUrl: String,
    /** aarch64 走 ubuntu-ports 仓库，与 x86_64 的 archive 仓库不同源。 */
    val portsBaseUrl: String,
)

internal enum class UbuntuInstallStage {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING_TOOLS,
    COMPLETE,
}

internal data class UbuntuInstallProgress(
    val stage: UbuntuInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface UbuntuInstallResult {
    data object AlreadyReady : UbuntuInstallResult
    data class BaseInstalled(val version: String) : UbuntuInstallResult
    data class ToolsInstalled(val version: String) : UbuntuInstallResult
    data object BaseNotInstalled : UbuntuInstallResult
    data class UnsupportedAbi(val abi: String) : UbuntuInstallResult
    data object RootUnavailable : UbuntuInstallResult
    data object BusyBoxUnavailable : UbuntuInstallResult
    data object EnvironmentUnavailable : UbuntuInstallResult
    data class Failed(val stage: UbuntuInstallStage, val code: String? = null, val message: String? = null) : UbuntuInstallResult
}

/**
 * 下载固定版本的 Ubuntu glibc rootfs；Android 内核、挂载和会话仍由 Eta 复用。
 *
 * 与 Debian 的差异集中在三处：套件名（plucky 而非 trixie）、arm64 仓库路径
 * （ubuntu-ports 而非 debian 单一 archive）、以及版本号形如 25.04。
 */
internal class UbuntuEnvironmentInstaller(
    private val context: Context,
    httpClient: OkHttpClient = VerifiedArtifactDownloader.defaultHttpClient(),
) {
    private val artifactDownloader = VerifiedArtifactDownloader(httpClient)

    fun status(): UbuntuEnvironmentStatus {
        val rootfs = rootfsDir()
        val version = readInstalledVersion(rootfs)
        val state = when {
            commonToolsReady(rootfs) -> UbuntuEnvironmentState.READY
            baseRootfsReady(rootfs) -> UbuntuEnvironmentState.BASE_READY
            else -> UbuntuEnvironmentState.NOT_INSTALLED
        }
        return UbuntuEnvironmentStatus(state, version)
    }

    suspend fun installBase(
        onProgress: suspend (UbuntuInstallProgress) -> Unit = {},
    ): UbuntuInstallResult {
        installMutex.lock()
        return try {
            installBaseLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    suspend fun installTools(
        onProgress: suspend (UbuntuInstallProgress) -> Unit = {},
    ): UbuntuInstallResult {
        installMutex.lock()
        return try {
            installToolsLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installBaseLocked(
        onProgress: suspend (UbuntuInstallProgress) -> Unit,
    ): UbuntuInstallResult = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir()
        if (baseRootfsReady(rootfs)) {
            return@withContext UbuntuInstallResult.AlreadyReady
        }
        io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository.selectBackend(
            LinuxDistribution.UBUNTU, LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
        )
        val artifact = artifactForAbis(Build.SUPPORTED_ABIS.toList())
            ?: return@withContext UbuntuInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            )

        onProgress(UbuntuInstallProgress(UbuntuInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }

        val archive = File(context.cacheDir, artifact.fileName + ".download")
        try {
            onProgress(UbuntuInstallProgress(UbuntuInstallStage.DOWNLOADING))
            val downloaded = artifactDownloader.download(artifact, archive) { downloadedBytes, totalBytes ->
                onProgress(UbuntuInstallProgress(UbuntuInstallStage.DOWNLOADING, downloadedBytes, totalBytes))
            }
            if (!downloaded) return@withContext UbuntuInstallResult.Failed(UbuntuInstallStage.DOWNLOADING)
            coroutineContext.ensureActive()
            onProgress(UbuntuInstallProgress(UbuntuInstallStage.EXTRACTING))
            if (!installRootfs(artifact, archive, rootfs)) {
                return@withContext UbuntuInstallResult.Failed(UbuntuInstallStage.EXTRACTING)
            }
        } catch (failure: RootlessInstallFailure) {
            return@withContext UbuntuInstallResult.Failed(UbuntuInstallStage.EXTRACTING, failure.code, failure.message)
        } catch (_: java.io.IOException) {
            return@withContext UbuntuInstallResult.Failed(UbuntuInstallStage.EXTRACTING, "INSTALL_IO_FAILED", "安装文件无法读写，请检查内部存储空间并重试")
        } catch (_: IllegalArgumentException) {
            return@withContext UbuntuInstallResult.Failed(UbuntuInstallStage.EXTRACTING, "INVALID_ARCHIVE", "环境归档无效或包含不安全路径，请重新下载后重试")
        } finally {
            archive.delete()
        }

        onProgress(UbuntuInstallProgress(UbuntuInstallStage.COMPLETE))
        UbuntuInstallResult.BaseInstalled(artifact.version)
    }

    private suspend fun installToolsLocked(
        onProgress: suspend (UbuntuInstallProgress) -> Unit,
    ): UbuntuInstallResult = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir()
        if (!baseRootfsReady(rootfs)) return@withContext UbuntuInstallResult.BaseNotInstalled
        if (commonToolsReady(rootfs)) return@withContext UbuntuInstallResult.AlreadyReady
        onProgress(UbuntuInstallProgress(UbuntuInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }
        onProgress(UbuntuInstallProgress(UbuntuInstallStage.INSTALLING_TOOLS))
        if (!installCommonTools(rootfs)) {
            return@withContext UbuntuInstallResult.Failed(UbuntuInstallStage.INSTALLING_TOOLS)
        }
        onProgress(UbuntuInstallProgress(UbuntuInstallStage.COMPLETE))
        UbuntuInstallResult.ToolsInstalled(readInstalledVersion(rootfs) ?: UBUNTU_VERSION)
    }

    private suspend fun preflightFailure(): UbuntuInstallResult? = when (runPreflight().exitCode) {
        0 -> null
        PREFLIGHT_ROOT_UNAVAILABLE -> UbuntuInstallResult.RootUnavailable
        PREFLIGHT_BUSYBOX_UNAVAILABLE, PREFLIGHT_BUSYBOX_INCOMPLETE ->
            UbuntuInstallResult.BusyBoxUnavailable
        PREFLIGHT_ENVIRONMENT_UNAVAILABLE -> UbuntuInstallResult.EnvironmentUnavailable
        else -> UbuntuInstallResult.Failed(UbuntuInstallStage.CHECKING)
    }

    private suspend fun runPreflight(): InstallerCommandResult {
        if (LinuxEnvironmentPaths.backendOf(rootfsDir().absolutePath) == LinuxExecutionBackend.PROOT) {
            return InstallerCommandResult(if (ProotCommandBuilder.available()) 0 else PREFLIGHT_ENVIRONMENT_UNAVAILABLE, "")
        }
        if (!TerminalRuntime.rootAvailable) return InstallerCommandResult(PREFLIGHT_ROOT_UNAVAILABLE, "")
        val requiredApplets = listOf(
            "ash", "chroot", "grep", "gzip", "mount", "sha256sum", "tar", "unshare", "xz",
        ).joinToString(" ")
        val command = """
            if [ "${'$'}(id -u)" != 0 ]; then exit $PREFLIGHT_ROOT_UNAVAILABLE; fi
            ${AndroidBusyBox.discoveryScript()}
            if [ -z "${'$'}eta_busybox" ]; then exit $PREFLIGHT_BUSYBOX_UNAVAILABLE; fi
            for eta_applet in $requiredApplets; do
              "${'$'}eta_busybox" --list | "${'$'}eta_busybox" grep -qx "${'$'}eta_applet" || exit $PREFLIGHT_BUSYBOX_INCOMPLETE
            done
            "${'$'}eta_busybox" unshare -m --propagation private \
              "${'$'}eta_busybox" chroot / /system/bin/sh -c ':' || exit $PREFLIGHT_ENVIRONMENT_UNAVAILABLE
        """.trimIndent()
        return InstallerShellRunner.run(command, 15, TerminalEnvironment.ANDROID)
    }

    private suspend fun installRootfs(artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return RootlessLinuxInstaller.installBase(artifact, archive, rootfs, LinuxDistribution.UBUNTU)
        }
        val parent = rootfs.parentFile ?: return false
        val temporaryRootfs = File(parent, "rootfs.installing")
        val markerBody = "version=${artifact.version}\\ndistribution=ubuntu\\nsha256=${artifact.sha256}\\n"
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_parent=${shellQuote(parent.absolutePath)}
            eta_rootfs=${shellQuote(rootfs.absolutePath)}
            eta_temporary=${shellQuote(temporaryRootfs.absolutePath)}
            eta_actual_sha=${'$'}("${'$'}eta_busybox" sha256sum "${'$'}eta_archive" | "${'$'}eta_busybox" awk '{print ${'$'}1}')
            [ "${'$'}eta_actual_sha" = ${shellQuote(artifact.sha256)} ] || exit 65
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_parent" || exit 66
            "${'$'}eta_busybox" rm -rf "${'$'}eta_temporary"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary" || exit 66
            "${'$'}eta_busybox" tar -xJf "${'$'}eta_archive" -C "${'$'}eta_temporary" --strip-components=1 || exit 67
            "${'$'}eta_busybox" mkdir -p \
              "${'$'}eta_temporary/proc" \
              "${'$'}eta_temporary/sys" \
              "${'$'}eta_temporary/dev" \
              "${'$'}eta_temporary/workspace" \
              "${'$'}eta_temporary/storage/emulated/0" \
              "${'$'}eta_temporary/data/local/tmp" \
              "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" chmod 1777 "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary/sdcard"
            "${'$'}eta_busybox" ln -s /storage/emulated/0 "${'$'}eta_temporary/sdcard"
            cat > "${'$'}eta_temporary/etc/resolv.conf" <<'ETA_RESOLV_EOF'
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 1.1.1.1
            ETA_RESOLV_EOF
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary/etc/apt/apt.conf.d" "${'$'}eta_temporary/usr/local/bin"
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary"/etc/apt/sources.list.d/*.sources
            cat > "${'$'}eta_temporary/etc/apt/apt.conf.d/99eta-network" <<'ETA_APT_CONFIG_EOF'
            Acquire::Retries "2";
            Acquire::http::Pipeline-Depth "0";
            Acquire::https::Pipeline-Depth "0";
            ETA_APT_CONFIG_EOF
            printf '%s\n' ${sourcesListEntries(aptSourcePath()).joinToString(" ") { shellQuote(it) }} > "${'$'}eta_temporary/etc/apt/sources.list"
            printf '%s\n' '#!/bin/sh' > "${'$'}eta_temporary/usr/local/bin/eta-apt"
            printf %s ${shellQuote(aptMirrorScriptBody())} >> "${'$'}eta_temporary/usr/local/bin/eta-apt"
            "${'$'}eta_busybox" chmod 0755 "${'$'}eta_temporary/usr/local/bin/eta-apt"
            printf ${shellQuote(markerBody)} > "${'$'}eta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" chmod 0644 "${'$'}eta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" rm -rf "${'$'}eta_rootfs"
            "${'$'}eta_busybox" mv "${'$'}eta_temporary" "${'$'}eta_rootfs" || exit 69
        """.trimIndent()
        val result = InstallerShellRunner.run(command, 180, TerminalEnvironment.ANDROID)
        AndroidAgentLogger.info(
            "Ubuntu environment action=extract outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun installCommonTools(rootfs: File): Boolean {
        val packages = AGENT_PACKAGES.joinToString(" ")
        val command = """
            export DEBIAN_FRONTEND=noninteractive
            PROXY_CANDIDATES="${APT_PROXY_CANDIDATES.joinToString(" ")}"
            mkdir -p /usr/local/bin
            rm -f /etc/apt/sources.list.d/*.sources 2>/dev/null
            mkdir -p /etc/apt/apt.conf.d
            rm -f /etc/apt/apt.conf.d/99eta-proxy 2>/dev/null
            for eta_proxy in ${'$'}PROXY_CANDIDATES; do
              if timeout 3 bash -c "exec 3<>/dev/tcp/${'$'}{eta_proxy%:*}/${'$'}{eta_proxy##*:}" 2>/dev/null; then
                printf 'Acquire::http::Proxy "http://%s";\nAcquire::https::Proxy "http://%s";\n' "$eta_proxy" "$eta_proxy" > /etc/apt/apt.conf.d/99eta-proxy
                echo "eta: using apt proxy $eta_proxy"
                break
              fi
            done
            printf '%s\n' '#!/bin/sh' > /usr/local/bin/eta-apt
            printf %s ${shellQuote(aptMirrorScriptBody())} >> /usr/local/bin/eta-apt
            chmod 0755 /usr/local/bin/eta-apt
            /usr/local/bin/eta-apt install $packages || exit 70
            if command -v fdfind >/dev/null 2>&1; then ln -sf /usr/bin/fdfind /usr/local/bin/fd; fi
            cat > /${COMMON_TOOLS_MARKER} <<'ETA_TOOLSET_EOF'
            ubuntu=$UBUNTU_VERSION
            toolset=$TOOLSET_REVISION
            profiles=agent
            ETA_TOOLSET_EOF
            chmod 0644 /${COMMON_TOOLS_MARKER}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command,
            COMMON_TOOLS_TIMEOUT_SECONDS,
            TerminalEnvironment.UBUNTU,
            rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Ubuntu environment action=install_tools outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private fun rootfsDir(): File = LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.UBUNTU)

    private fun commonToolsReady(rootfs: File): Boolean {
        if (!baseRootfsReady(rootfs)) return false
        return LinuxEnvironmentPaths.markerSatisfied(
            File(rootfs, COMMON_TOOLS_MARKER),
            "toolset=$TOOLSET_REVISION",
        )
    }

    private fun readInstalledVersion(rootfs: File): String? = runCatching {
        File(rootfs, LinuxEnvironmentPaths.READY_MARKER).readLines()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) }
    }.getOrNull()

    companion object {
        private const val UBUNTU_VERSION = "25.04"
        private const val UBUNTU_SUITE = "plucky"
        private const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
        private const val TOOLSET_REVISION = 1
        private const val COMMON_TOOLS_TIMEOUT_SECONDS = 900L
        private const val PREFLIGHT_ROOT_UNAVAILABLE = 40
        private const val PREFLIGHT_BUSYBOX_UNAVAILABLE = 41
        private const val PREFLIGHT_BUSYBOX_INCOMPLETE = 42
        private const val PREFLIGHT_ENVIRONMENT_UNAVAILABLE = 43
        private val installMutex = Mutex()

        /** Ubuntu 组件分散在 main/restricted/universe/multiverse，缺一会导致常用包装不上。 */
        private val UBUNTU_COMPONENTS = "main restricted universe multiverse"

        internal fun baseRootfsReady(rootfs: File): Boolean =
            LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)

        internal val AGENT_PACKAGES = listOf(
            "bash", "ca-certificates", "coreutils", "curl", "diffutils", "file", "findutils",
            "gawk", "git", "grep", "gzip", "jq", "less", "openssl", "openssh-client",
            "patch", "procps", "ripgrep", "rsync", "sed", "sqlite3", "tar", "unzip", "util-linux", "wget",
            "xz-utils", "zip", "zstd", "fd-find",
        )

        /**
         * apt 候选代理端口。chroot 只隔离文件系统、不新建 network namespace，
         * 所以宿主 loopback 上的代理对 chroot 同样可达。
         * 逐个探测，谁在监听用谁；全都不在就保持直连（国内镜像本来也不需要代理）。
         */
        internal val APT_PROXY_CANDIDATES = listOf(
            "127.0.0.1:7080", // sing-box / boxproxy mixed 入站
            "127.0.0.1:7890", // Clash 默认混合端口
            "127.0.0.1:7897", // Clash Verge
            "127.0.0.1:1080", // 通用 SOCKS/HTTP
            "127.0.0.1:10808", // v2rayN
        )

        /** 真机链路只保留国内镜像和官方源，避免慢镜像串行拖长安装。 */
        internal val APT_MIRRORS = listOf(
            UbuntuAptMirror(
                id = "tuna",
                archiveBaseUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
                portsBaseUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            ),
            UbuntuAptMirror(
                id = "aliyun",
                archiveBaseUrl = "https://mirrors.aliyun.com/ubuntu",
                portsBaseUrl = "https://mirrors.aliyun.com/ubuntu-ports",
            ),
            UbuntuAptMirror(
                id = "official",
                archiveBaseUrl = "http://archive.ubuntu.com/ubuntu",
                portsBaseUrl = "http://ports.ubuntu.com/ubuntu-ports",
            ),
        )

        /** aarch64 上 Ubuntu 的官方仓库是 ubuntu-ports，x86_64 才是 ubuntu。 */
        internal fun aptSourcePath(abi: String? = Build.SUPPORTED_ABIS.firstOrNull()): String =
            if (abi == "arm64-v8a") "ports" else "archive"

        internal fun mirrorBaseUrl(mirror: UbuntuAptMirror, sourcePath: String): String =
            if (sourcePath == "ports") mirror.portsBaseUrl else mirror.archiveBaseUrl

        internal fun sourcesListEntries(sourcePath: String, mirror: UbuntuAptMirror = APT_MIRRORS.first()): List<String> {
            val base = mirrorBaseUrl(mirror, sourcePath)
            return listOf(
                "deb $base $UBUNTU_SUITE $UBUNTU_COMPONENTS",
                "deb $base $UBUNTU_SUITE-updates $UBUNTU_COMPONENTS",
                "deb $base $UBUNTU_SUITE-security $UBUNTU_COMPONENTS",
            )
        }

        /** 逐个尝试镜像并把成功者写回 sources.list，后续 apt 操作复用它。 */
        internal fun aptMirrorScript(): String = "#!/bin/sh\n${aptMirrorScriptBody()}"

        private fun aptMirrorScriptBody(): String = buildString {
            append("set -u; ")
            // 脚本在 guest 内执行，uname -m 才是 rootfs 的真实架构。
            append("case \"${'$'}(uname -m)\" in aarch64|arm64|armv8l) eta_apt_path=ports;; *) eta_apt_path=archive;; esac; ")
            append("eta_apt_write_sources() { ")
            // Ubuntu 24.04+ 默认改用 DEB822 的 ubuntu.sources；残留它会让 apt 继续访问官方源。
            append("rm -f /etc/apt/sources.list.d/*.sources 2>/dev/null; ")
            append("case \"${'$'}1\" in ")
            APT_MIRRORS.forEach { mirror ->
                append("${mirror.id}) ")
                append("if [ \"${'$'}eta_apt_path\" = ports ]; then eta_apt_base=${mirror.portsBaseUrl}; else eta_apt_base=${mirror.archiveBaseUrl}; fi;; ")
            }
            append("*) return 64;; esac; ")
            append("printf '%s\\n' \"deb ${'$'}eta_apt_base $UBUNTU_SUITE $UBUNTU_COMPONENTS\" ")
            append("\"deb ${'$'}eta_apt_base $UBUNTU_SUITE-updates $UBUNTU_COMPONENTS\" ")
            append("\"deb ${'$'}eta_apt_base $UBUNTU_SUITE-security $UBUNTU_COMPONENTS\" > /etc/apt/sources.list; ")
            append("}; ")
            append("case \"${'$'}{1:-}\" in ")
            append("install) shift; [ \"${'$'}#\" -gt 0 ] || exit 64; ")
            append("for eta_apt_mirror in ${APT_MIRRORS.joinToString(" ") { it.id }}; do ")
            append("eta_apt_write_sources \"${'$'}eta_apt_mirror\" || exit 65; ")
            append("if apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 update && apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 install -y --no-install-recommends \"${'$'}@\"; then exit 0; fi; ")
            append("done; exit 1;; ")
            append("update) for eta_apt_mirror in ${APT_MIRRORS.joinToString(" ") { it.id }}; do ")
            append("eta_apt_write_sources \"${'$'}eta_apt_mirror\" || exit 65; ")
            append("apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 update && exit 0; done; exit 1;; ")
            append("*) echo \"usage: eta-apt install PACKAGE... | update\" >&2; exit 64;; esac")
        }

        internal fun artifactForAbis(abis: List<String>): VerifiedArtifact? =
            abis.firstNotNullOfOrNull { abi ->
                when (abi) {
                    "arm64-v8a" -> ubuntuArtifact(
                        id = "ubuntu-plucky-aarch64-pd-v4.29.0",
                        fileName = "ubuntu-plucky-aarch64-pd-v4.29.0.tar.xz",
                        sha256 = "63cee3aecc0473785ef761ec1127387ed2abbea0b26d74e5187601568fbb335f",
                        sizeBytes = 56_752_204L,
                    )
                    "x86_64" -> ubuntuArtifact(
                        id = "ubuntu-plucky-x86_64-pd-v4.29.0",
                        fileName = "ubuntu-plucky-x86_64-pd-v4.29.0.tar.xz",
                        sha256 = "fcac0b71a98524e1dd10a3b1fe6753b8e85716b98207940169fe01bbd21b1538",
                        sizeBytes = 61_294_804L,
                    )
                    else -> null
                }
            }

        private fun ubuntuArtifact(
            id: String,
            fileName: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact {
            val officialUrl = "https://github.com/termux/proot-distro/releases/download/v4.29.0/$fileName"
            return VerifiedArtifact(
                id = id,
                version = UBUNTU_VERSION,
                fileName = fileName,
                url = officialUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                preferredUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
            )
        }

        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://gh-proxy.com/",
        )
    }
}
