package cn.com.omnimind.baselib.shizuku

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

// Shizuku UserServiceManager allows 30 seconds for cold process startup.
// Wait for its callback, while caller cancellation still interrupts immediately.
internal suspend fun <T> awaitShizukuUserService(
    connection: Deferred<T>,
    timeoutMillis: Long = 30_000,
): T? = withTimeoutOrNull(timeoutMillis) { connection.await() }
