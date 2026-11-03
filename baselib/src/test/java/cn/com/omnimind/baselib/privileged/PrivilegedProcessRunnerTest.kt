package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference

class PrivilegedProcessRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deniedAdmissionCannotStartAProcess() {
        var starts = 0
        val runner = PrivilegedProcessRunner(admission = { false }, startProcess = { starts++; error("Unexpected process") })
        assertThrows(IllegalStateException::class.java) { runner.launch("request", listOf("id")) }
        assertEquals(0, starts)
    }

    @Test fun drainsAndBoundsOutputWithoutBlockingTheChild() {
        val runner = PrivilegedProcessRunner()
        val result = runner.execute("output", listOf("/bin/sh", "-c", "i=0; while [ \"\$i\" -lt 10000 ]; do printf 0123456789; i=\$((i+1)); done"), 5)
        assertTrue(result.success)
        assertTrue(result.truncated)
        assertEquals(PrivilegedProcessRunner.OUTPUT_BYTES, result.stdout.length)
    }

    @Test fun commandFailureIsNotMistakenForRootRevocation() {
        var denied = 0
        val runner = PrivilegedProcessRunner(root = true, leaseDirectory = temporary.newFolder(), accessDenied = { denied++ }, startProcess = { argv ->
            val marker = Regex("__OMNI_ROOT_[a-zA-Z0-9-]+__").find(argv.last())!!.value
            ProcessBuilder("/bin/sh", "-c", "printf '%s\\n' '$marker' >&2; exit 7").start()
        })
        val result = runner.execute("failure", listOf("false"), 2)
        assertEquals(7, result.exitCode)
        assertNull(result.errorCode)
        assertEquals(0, denied)
        assertFalse(result.stderr.contains("__OMNI_ROOT_"))
    }

    @Test fun missingUidHandshakeIsRootDenial() {
        var denied = 0
        val runner = PrivilegedProcessRunner(root = true, leaseDirectory = temporary.newFolder(), accessDenied = { denied++ },
            startProcess = { ProcessBuilder("/bin/sh", "-c", "exit 1").start() })
        assertEquals("root_denied", runner.execute("denial", listOf("id"), 2).errorCode)
        assertEquals(1, denied)
    }

    @Test fun cancellationStopsTheOwnedProcess() = runBlocking {
        val process = AtomicReference<Process>()
        val started = CompletableDeferred<Unit>()
        val runner = PrivilegedProcessRunner(startProcess = { args ->
            ProcessBuilder(args).start().also { process.set(it); started.complete(Unit) }
        })
        val execution = launch(Dispatchers.IO) { runInterruptible { runner.execute("sleep", listOf("/bin/sleep", "30"), 30) } }
        started.await()
        withTimeout(3000) { execution.cancelAndJoin() }
        assertFalse(process.get().isAlive)
    }

    @Test fun timeoutStopsTheOwnedProcess() {
        val process = AtomicReference<Process>()
        val runner = PrivilegedProcessRunner(startProcess = { args -> ProcessBuilder(args).start().also(process::set) })
        val result = runner.execute("timeout", listOf("/bin/sleep", "30"), 1)
        assertTrue(result.timedOut)
        assertFalse(process.get().isAlive)
    }
}
