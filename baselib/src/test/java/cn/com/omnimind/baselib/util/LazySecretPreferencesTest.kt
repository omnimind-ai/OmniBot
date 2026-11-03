package cn.com.omnimind.baselib.util

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LazySecretPreferencesTest {
    @Test
    fun preparationDefersOpeningAndKeepsTheFirstFactory() {
        val owner = LazySecretPreferences()
        val opens = AtomicInteger()
        val validations = AtomicInteger()
        val preferences = preferences { validations.incrementAndGet() }
        owner.prepare {
            opens.incrementAndGet()
            preferences
        }
        owner.prepare { error("preparation must not replace the registered factory") }

        assertEquals(0, opens.get())
        assertEquals(0, validations.get())
        assertSame(preferences, owner.getOrNull())
        assertSame(preferences, owner.getOrNull())
        assertEquals(1, opens.get())
        assertEquals(1, validations.get())
    }

    @Test
    fun accessBeforePreparationDoesNotPreventLaterOpening() {
        val owner = LazySecretPreferences()
        val preferences = preferences()

        assertNull(owner.getOrNull())
        assertNull(owner.getOrNull())
        owner.prepare { preferences }

        assertSame(preferences, owner.getOrNull())
    }

    @Test
    fun concurrentFirstAccessOpensAndValidatesOnlyOnce() {
        val owner = LazySecretPreferences()
        val opens = AtomicInteger()
        val validations = AtomicInteger()
        val preferences = preferences { validations.incrementAndGet() }
        val callersReady = CountDownLatch(6)
        val start = CountDownLatch(1)
        val opening = CountDownLatch(1)
        val finishOpening = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        owner.prepare {
            opens.incrementAndGet()
            opening.countDown()
            check(finishOpening.await(5, TimeUnit.SECONDS))
            preferences
        }
        try {
            val results = (1..6).map {
                executor.submit<SharedPreferences?> {
                    callersReady.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    owner.getOrNull()
                }
            }
            assertTrue(callersReady.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            assertEquals(1, opens.get())
            finishOpening.countDown()

            results.forEach { assertSame(preferences, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, opens.get())
            assertEquals(1, validations.get())
        } finally {
            start.countDown()
            finishOpening.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun openingFailureRemainsUnavailableAfterFurtherPreparation() {
        val owner = LazySecretPreferences()
        val opens = AtomicInteger()
        owner.prepare {
            opens.incrementAndGet()
            error("simulated Keystore failure")
        }

        assertNull(owner.getOrNull())
        owner.prepare {
            opens.incrementAndGet()
            preferences()
        }

        assertNull(owner.getOrNull())
        assertEquals(1, opens.get())
    }

    @Test
    fun storedValueValidationFailureNeverPublishesPreferences() {
        val owner = LazySecretPreferences()
        val validations = AtomicInteger()
        owner.prepare {
            preferences {
                validations.incrementAndGet()
                error("simulated stored value decryption failure")
            }
        }

        assertNull(owner.getOrNull())
        assertNull(owner.getOrNull())
        assertEquals(1, validations.get())
    }

    @Test
    fun furtherPreparationKeepsSuccessfullyOpenedPreferences() {
        val owner = LazySecretPreferences()
        val preferences = preferences()
        owner.prepare { preferences }
        assertSame(preferences, owner.getOrNull())

        owner.prepare { error("must not reopen an initialized store") }

        assertSame(preferences, owner.getOrNull())
    }

    private fun preferences(validate: () -> Unit = {}): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> {
                    validate()
                    emptyMap<String, Any>()
                }
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
}
