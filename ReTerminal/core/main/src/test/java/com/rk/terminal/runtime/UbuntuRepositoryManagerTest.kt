package com.rk.terminal.runtime

import com.rk.settings.UbuntuPackageMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UbuntuRepositoryManagerTest {
    @Test
    fun tsinghuaMirrorAlsoMirrorsPip() {
        // 真机教训：Ubuntu 首启 pip install uv 直连 PyPI 12.3kB/s 超时；
        // apt 镜像选清华时 pip 必须跟随（真机弱网现场反馈）
        val cmd = UbuntuRepositoryManager.buildRepositorySetupCommand(UbuntuPackageMirror.TSINGHUA)
        assertTrue("pip.conf must be written during repository setup", cmd.contains("/etc/pip.conf"))
        assertTrue(cmd.contains("index-url = https://pypi.tuna.tsinghua.edu.cn/simple"))
        assertTrue("weak-network hardening: read timeout", cmd.contains("timeout = 120"))
        assertTrue("weak-network hardening: retries", cmd.contains("retries = 10"))
        assertFalse(cmd.contains("index-url = https://pypi.org/simple"))
    }

    @Test
    fun officialMirrorKeepsPypiOrg() {
        val cmd = UbuntuRepositoryManager.buildRepositorySetupCommand(UbuntuPackageMirror.OFFICIAL)
        assertTrue(cmd.contains("index-url = https://pypi.org/simple"))
        assertFalse(cmd.contains("tuna.tsinghua.edu.cn/simple"))
    }

    @Test
    fun pipConfContentIsWellFormedIni() {
        val conf = UbuntuRepositoryManager.buildPipConfContent(UbuntuPackageMirror.TSINGHUA)
        assertTrue(conf.startsWith("[global]"))
        assertTrue(conf.contains("index-url = https://pypi.tuna.tsinghua.edu.cn/simple"))
        assertTrue(conf.contains("timeout = 120"))
        assertTrue(conf.contains("retries = 10"))
    }

    @Test
    fun repositorySetupCommandIsValidPosixShell() {
        // 命令模板里嵌了多行单引号字面量（pip.conf 落盘），必须真过 sh -n 防语法回归
        val cmd = UbuntuRepositoryManager.buildRepositorySetupCommand(UbuntuPackageMirror.TSINGHUA)
        val file = File.createTempFile("ubuntu-repo-setup", ".sh").apply { writeText(cmd) }
        val process = ProcessBuilder("sh", "-n", file.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        file.delete()
        assertEquals("repository setup command must be POSIX-valid: $output", 0, code)
    }
}
