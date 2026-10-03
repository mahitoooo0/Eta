package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UbuntuEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesPinnedPluckyIntegrityMetadata() {
        val artifact = UbuntuEnvironmentInstaller.artifactForAbis(
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(artifact)
        assertEquals("25.04", artifact.version)
        assertEquals("ubuntu-plucky-aarch64-pd-v4.29.0.tar.xz", artifact.fileName)
        assertTrue(artifact.url.contains("termux/proot-distro/releases/download/v4.29.0"))
        assertEquals(1, artifact.preferredUrls.size)
        assertTrue(artifact.preferredUrls.single().startsWith("https://gh-proxy.com/"))
        assertTrue(artifact.preferredUrls.all { it.contains(artifact.fileName) })
        assertEquals(64, artifact.sha256.length)
        assertEquals(56_752_204L, artifact.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        assertNull(UbuntuEnvironmentInstaller.artifactForAbis(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun readinessUsesInstallerMarkerOnly() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val marker = File(rootfs, LinuxEnvironmentPaths.READY_MARKER)

        assertFalse(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        marker.writeText("version=25.04\n")
        assertTrue(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
    }

    @Test
    fun baseToolsetContainsGlibcAndAgentEssentials() {
        val packages = UbuntuEnvironmentInstaller.AGENT_PACKAGES

        assertTrue(packages.containsAll(listOf("bash", "coreutils", "git", "jq", "ripgrep", "sqlite3", "xz-utils")))
        assertFalse(packages.contains("nodejs"))
        assertFalse(packages.contains("npm"))
        assertFalse(packages.contains("python3"))
        assertFalse(packages.contains("python3-pip"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun aptMirrorsKeepDomesticCandidatesBeforeOfficialFallback() {
        assertEquals(listOf("tuna", "aliyun", "official"), UbuntuEnvironmentInstaller.APT_MIRRORS.map { it.id })
        assertTrue(UbuntuEnvironmentInstaller.APT_MIRRORS.dropLast(1).all { it.archiveBaseUrl.startsWith("https://mirrors.") })
        assertTrue(UbuntuEnvironmentInstaller.APT_MIRRORS.all { it.portsBaseUrl.endsWith("ubuntu-ports") })
        val script = UbuntuEnvironmentInstaller.aptMirrorScript()
        assertTrue(script.contains("apt-get -o Acquire::Retries=2"))
        assertFalse(script.contains("mirrors.ustc.edu.cn"))
    }

    @Test
    fun sourcesListUsesPortsRepositoryOnArm64() {
        assertEquals("ports", UbuntuEnvironmentInstaller.aptSourcePath("arm64-v8a"))
        assertEquals("archive", UbuntuEnvironmentInstaller.aptSourcePath("x86_64"))

        val portsEntries = UbuntuEnvironmentInstaller.sourcesListEntries("ports")
        assertEquals(3, portsEntries.size)
        assertTrue(portsEntries.all { it.contains("ubuntu-ports") })
        assertTrue(portsEntries.all { it.contains("plucky") })
        assertTrue(portsEntries.any { it.contains("plucky-security") })
        assertTrue(portsEntries.all { it.contains("main restricted universe multiverse") })

        val archiveEntries = UbuntuEnvironmentInstaller.sourcesListEntries("archive")
        assertEquals(3, archiveEntries.size)
        assertTrue(archiveEntries.none { it.contains("ubuntu-ports") })
    }

    @Test
    fun aptMirrorScriptRemovesDeb822SourcesBeforeWritingLegacyList() {
        // Ubuntu 24.04+ 默认用 DEB822 的 ubuntu.sources，残留会让 apt 继续访问官方源。
        assertTrue(UbuntuEnvironmentInstaller.aptMirrorScript().contains("/etc/apt/sources.list.d/*.sources"))
        val command = UbuntuEnvironmentInstaller.APT_MIRRORS // 保证镜像列表非空，脚本确实写入清理逻辑
        assertTrue(command.isNotEmpty())
    }

    @Test
    fun aptMirrorScriptIsValidPosixShell() {
        val process = ProcessBuilder("sh", "-n").start()
        process.outputStream.use { output ->
            output.write(UbuntuEnvironmentInstaller.aptMirrorScript().toByteArray())
        }
        assertEquals(0, process.waitFor())
    }

    @Test
    fun aptMirrorScriptPicksRepositoryByGuestArchitecture() {
        val script = UbuntuEnvironmentInstaller.aptMirrorScript()
        assertTrue(script.contains("uname -m"))
        assertTrue(script.contains("eta_apt_path=ports"))
        assertTrue(script.contains("eta_apt_path=archive"))
    }
}
