package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RootAccessControllerTest {
    private class Preferences(
        override var enabled: Boolean = false,
        override var previouslyGranted: Boolean = false,
    ) : RootAccessPreferences

    @Test fun disabledStartupAndStatusReadsNeverProbeRoot() = runBlocking {
        var probes = 0
        val controller = RootAccessController(Preferences(previouslyGranted = true), this,
            probe = { probes++; RootAccessStatus.GRANTED }, revoke = {})
        controller.initialize()
        repeat(3) { assertFalse(controller.snapshot.enabled); controller.requestAccess() }
        assertEquals(0, probes)
    }

    @Test fun enablingRequiresSuccessfulProbeAndDoesNotRepeatWhenAlreadyGranted() = runBlocking {
        var probes = 0
        val preferences = Preferences()
        val controller = RootAccessController(preferences, this,
            probe = { probes++; RootAccessStatus.GRANTED }, revoke = {})
        assertTrue(controller.setEnabled(true).granted)
        controller.setEnabled(true)
        assertEquals(1, probes)
        assertTrue(preferences.previouslyGranted)
        assertTrue(preferences.enabled)
    }

    @Test fun refusalAndTimeoutAreNotRetriedOnRestart() = runBlocking {
        for (failure in listOf(RootAccessStatus.DENIED, RootAccessStatus.TIMED_OUT, RootAccessStatus.UNAVAILABLE)) {
            val preferences = Preferences()
            var probes = 0
            val first = RootAccessController(preferences, this, { probes++; failure }, {})
            assertEquals(failure, first.setEnabled(true).status)
            assertFalse(preferences.previouslyGranted)
            val restarted = RootAccessController(preferences, this, { probes++; RootAccessStatus.GRANTED }, {})
            restarted.initialize()
            yield()
            assertEquals(1, probes)
            assertTrue(restarted.requestAccess().granted)
            assertEquals(2, probes)
        }
    }

    @Test fun previouslyGrantedEnabledStateGetsExactlyOneRestoreProbe() = runBlocking {
        var probes = 0
        val controller = RootAccessController(Preferences(true, true), this, { probes++; RootAccessStatus.GRANTED }, {})
        repeat(3) { controller.initialize() }
        yield()
        assertEquals(1, probes)
        assertTrue(controller.snapshot.granted)
    }

    @Test fun disablingInvalidatesPendingAndLateAuthorization() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var revocations = 0
        val preferences = Preferences()
        val controller = RootAccessController(preferences, this, probe = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            RootAccessStatus.GRANTED
        }, revoke = { revocations++ })
        val enabling = async { controller.setEnabled(true) }
        entered.await()
        val oldGeneration = controller.snapshot.generation
        assertEquals(RootAccessStatus.DISABLED, controller.setEnabled(false).status)
        release.complete(Unit)
        enabling.join()
        assertFalse(controller.snapshot.granted)
        assertFalse(preferences.enabled)
        assertFalse(preferences.previouslyGranted)
        assertTrue(controller.snapshot.generation > oldGeneration)
        assertEquals(1, revocations)
    }

    @Test fun revokedAccessInvalidatesExistingReservations() = runBlocking {
        var stops = 0
        val preferences = Preferences()
        val controller = RootAccessController(preferences, this, { RootAccessStatus.GRANTED }, { stops++ })
        val granted = controller.setEnabled(true)
        controller.markDenied()
        assertEquals(RootAccessStatus.DENIED, controller.snapshot.status)
        assertTrue(controller.snapshot.generation > granted.generation)
        assertFalse(controller.snapshot.granted)
        assertFalse(preferences.previouslyGranted)
        assertEquals(1, stops)
    }
}
