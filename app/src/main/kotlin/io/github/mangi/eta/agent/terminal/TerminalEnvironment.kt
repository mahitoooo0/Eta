package io.github.mangi.eta.agent.terminal

internal const val SELECTED_LINUX_WIRE_NAME = "linux"

/** Eta 支持的 Linux 用户态发行版。内核仍由 Android 提供，发行版只替换 rootfs。 */
internal enum class LinuxDistribution(val wireName: String) {
    ALPINE("alpine"),
    DEBIAN("debian"),
    UBUNTU("ubuntu"),
}

internal enum class TerminalEnvironment(
    val wireName: String,
    val linuxDistribution: LinuxDistribution? = null,
) {
    ANDROID("android"),
    ALPINE("alpine", LinuxDistribution.ALPINE),
    DEBIAN("debian", LinuxDistribution.DEBIAN),
    UBUNTU("ubuntu", LinuxDistribution.UBUNTU),
}

internal val TerminalEnvironment.isLinux: Boolean
    get() = linuxDistribution != null

internal val LinuxDistribution.terminalEnvironment: TerminalEnvironment
    get() = when (this) {
        LinuxDistribution.ALPINE -> TerminalEnvironment.ALPINE
        LinuxDistribution.DEBIAN -> TerminalEnvironment.DEBIAN
        LinuxDistribution.UBUNTU -> TerminalEnvironment.UBUNTU
    }

/** Ubuntu/Debian 同属 apt 系 glibc 发行版，rootfs 与包管理命令形态一致。 */
internal val LinuxDistribution.isAptBased: Boolean
    get() = this == LinuxDistribution.DEBIAN || this == LinuxDistribution.UBUNTU

/**
 * 返回 UI 显示用的人类可读名称。
 */
internal fun TerminalEnvironment.label(): String = when (this) {
    TerminalEnvironment.ANDROID -> "Android"
    TerminalEnvironment.ALPINE -> "Alpine"
    TerminalEnvironment.DEBIAN -> "Debian"
    TerminalEnvironment.UBUNTU -> "Ubuntu"
}
