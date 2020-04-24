package org.hildan.krossbow.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertNotNull
import kotlin.test.fail

fun <T> runAsyncTestWithTimeout(millis: Long = 4000, block: suspend CoroutineScope.() -> T) = runAsyncTest {
    val result = withTimeoutOrNull(millis) {
        block()
        Unit
    }
    assertNotNull(result, "this test should run in less than ${millis}ms")
}

expect fun <T> runAsyncTest(block: suspend CoroutineScope.() -> T)

expect fun getCause(exception: Exception): Throwable?

suspend fun <T> assertCompletesSoon(deferred: Deferred<T>, message: String, timeoutMillis: Long = 100): T {
    return withTimeoutOrNull(timeoutMillis) { deferred.await() } ?: fail(message)
}

suspend fun <T> CoroutineScope.assertCompletesSoon(
    message: String,
    timeoutMillis: Long = 200,
    block: suspend CoroutineScope.() -> T
): T {
    val scope = this
    val deferred = scope.async { block() }
    return assertCompletesSoon(deferred, message, timeoutMillis)
}

@OptIn(ExperimentalStdlibApi::class)
suspend inline fun <T : Throwable> assertTimesOutWith(
    expectedExceptionClass: KClass<T>,
    expectedTimeoutMillis: Long,
    timeoutMarginMillis: Long = 200,
    crossinline block: suspend () -> Unit
): T {
    val result = runCatching {
        withTimeoutOrNull(expectedTimeoutMillis + timeoutMarginMillis) {
            block()
        }
    }
    val ex = result.exceptionOrNull()
    if (ex == null) {
        if (result.getOrNull() == null) {
            fail("expected time out after ${expectedTimeoutMillis}ms (with ${expectedExceptionClass.simpleName}) but " +
                    "nothing happened in ${expectedTimeoutMillis + timeoutMarginMillis}ms")
        } else {
            fail("expected time out after ${expectedTimeoutMillis}ms (with ${expectedExceptionClass.simpleName}) but " +
                    "the block completed successfully")
        }
    }
    if (!expectedExceptionClass.isInstance(ex)) {
        fail("expected time out with ${expectedExceptionClass.simpleName} (after ${expectedTimeoutMillis}ms) but " +
                "an exception of type ${ex::class.simpleName} was thrown instead")
    }
    return expectedExceptionClass.cast(ex)
}
