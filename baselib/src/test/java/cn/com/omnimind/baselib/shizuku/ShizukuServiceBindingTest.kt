package cn.com.omnimind.baselib.shizuku

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ShizukuServiceBindingTest {
    @Test fun coldStartLongerThanThreeSecondsStillConnects() = runBlocking {
        // Device regression: server started at 20:28:13.883, callback at 20:28:18.017.
        val connection = CompletableDeferred<String>()
        launch { delay(4_200); connection.complete("service") }
        assertEquals("service", awaitShizukuUserService(connection))
    }

    @Test fun stopCancelsWaitWithoutConsumingTheSharedConnection() = runBlocking {
        val connection = CompletableDeferred<String>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitShizukuUserService(connection)
        }
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertTrue(connection.isActive)
        connection.complete("service")
        assertEquals("service", awaitShizukuUserService(connection))
    }

    @Test fun unavailableServiceHasBoundedWaitAndLateConnectionCanBeReused() = runBlocking {
        val connection = CompletableDeferred<String>()
        assertNull(awaitShizukuUserService(connection, timeoutMillis = 20))
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(connection.isActive)
        connection.complete("late service")
        assertEquals("late service", awaitShizukuUserService(connection))
    }
}
