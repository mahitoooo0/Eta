package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxPackageProfilesTest {
    @Test
    fun everyProfileSpecifiesEachSupportedDistribution() {
        LinuxDistribution.entries.forEach { distribution ->
            LinuxPackageProfiles.ALL.forEach { profile ->
                assertNotNull(
                    "profile=${profile.id} distribution=${distribution.wireName}",
                    profile.spec(distribution),
                )
            }
        }
    }

    @Test
    fun dshInstallsOnTopOfNode() {
        assertEquals(LinuxPackageProfiles.NODE, LinuxPackageProfiles.DSH.dependsOn)
    }

    @Test
    fun dshInstallScriptInstallsFromMirrorThenRetriesWithBuildToolchain() {
        val script = LinuxPackageProfiles.DSH.spec(LinuxDistribution.UBUNTU).setupScript
        assertNotNull(script)
        requireNotNull(script)

        // 两次 npm 安装都必须走镜像源：官方 registry 在墙内不可达，重试换源没有意义。
        assertEquals(2, Regex("--registry=https://registry.npmmirror.com").findAll(script).count())
        // koffi 取不到预编译二进制时要退回源码编译，脚本需先补工具链再重试安装。
        assertTrue(script.contains("cmake"))
        assertTrue(script.contains("/usr/local/bin/eta-apt install"))
        assertTrue(script.contains("/usr/local/bin/eta-apk install"))
        assertEquals(2, Regex("@deepseek-ai/dsh@latest").findAll(script).count())
    }

    @Test
    fun npmProfilesVerifyTheirCliBeforeWritingTheReadyMarker() {
        assertTrue(LinuxPackageProfiles.KIMI.verifyScript.orEmpty().contains("kimi --version"))
        assertTrue(LinuxPackageProfiles.DSH.verifyScript.orEmpty().contains("dsh --version"))
    }

    @Test
    fun allProfilesAreExposedToTheUi() {
        assertEquals(
            listOf(
                LinuxPackageProfiles.PYTHON,
                LinuxPackageProfiles.NODE,
                LinuxPackageProfiles.SSH,
                LinuxPackageProfiles.KIMI,
                LinuxPackageProfiles.DSH,
            ),
            LinuxPackageProfiles.ALL,
        )
    }
}
