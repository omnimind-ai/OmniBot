package com.rk.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotSeccompCompatibilityTest {
    @Test
    fun enablesProotNoSeccompWhenSeccompCompatIsEnabled() {
        val env = linkedMapOf(
            "PATH" to "/bin",
            "PROOT_NO_SECCOMP" to "",
            "SECCOMP" to "0"
        )

        ProotSeccompCompatibility.applyEnvironment(env, seccompCompatEnabled = true)

        assertEquals("1", env["SECCOMP"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun preservesExplicitUserProotNoSeccompWhenCompatToggleIsOff() {
        val env = linkedMapOf(
            "PATH" to "/bin",
            "PROOT_NO_SECCOMP" to "1",
            "SECCOMP" to "1"
        )

        ProotSeccompCompatibility.applyEnvironment(env, seccompCompatEnabled = false)

        assertNull(env["SECCOMP"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun omitsProotNoSeccompByDefaultWhenUnconfigured() {
        val env = linkedMapOf(
            "PATH" to "/bin",
            "PROOT_NO_SECCOMP" to "   ",
            "SECCOMP" to "1"
        )

        ProotSeccompCompatibility.applyEnvironment(env, seccompCompatEnabled = false)

        assertNull(env["SECCOMP"])
        assertNull(env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun appliesConsistentRulesToSessionEnvironmentList() {
        val enabledList = mutableListOf("PATH=/bin", "SECCOMP=0")
        ProotSeccompCompatibility.applyEnvironmentList(enabledList, seccompCompatEnabled = true)
        assertTrue(enabledList.contains("SECCOMP=1"))
        assertTrue(enabledList.contains("PROOT_NO_SECCOMP=1"))

        val userOverrideList = mutableListOf("PATH=/bin", "PROOT_NO_SECCOMP=1", "SECCOMP=1")
        ProotSeccompCompatibility.applyEnvironmentList(userOverrideList, seccompCompatEnabled = false)
        assertFalse(userOverrideList.any { it.startsWith("SECCOMP=") })
        assertTrue(userOverrideList.contains("PROOT_NO_SECCOMP=1"))
    }

    @Test
    fun detectsSamsungAndKernelSeccompExecveFailures() {
        val enosysError = """
            proot error: execve("/system/bin/tar"): Function not implemented
            proot info: possible causes:
              * the loader was not found or doesn't work.
            fatal error: see `proot --help`.
        """.trimIndent()
        val epermError = """
            proot error: execve("/bin/sh"): Operation not permitted
            To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.
        """.trimIndent()

        assertTrue(ProotSeccompCompatibility.isProotSeccompExecveFailure(enosysError))
        assertTrue(ProotSeccompCompatibility.isProotSeccompExecveFailure(epermError))
        assertFalse(ProotSeccompCompatibility.isProotSeccompExecveFailure("sh: 1: git: not found"))
        assertFalse(ProotSeccompCompatibility.isProotSeccompExecveFailure(""))
    }
}
