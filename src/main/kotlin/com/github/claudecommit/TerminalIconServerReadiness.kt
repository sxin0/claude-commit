package com.github.claudecommit

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Shares only server readiness/port. A waiter never owns or cancels the shared startup task. */
internal class TerminalIconServerReadiness(
    private val start: () -> Int,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CC Kit terminal icon server readiness").apply { isDaemon = true }
    },
) : AutoCloseable {
    private var attempt: CompletableFuture<Int>? = null
    private var closed = false

    fun awaitPort(timeoutMillis: Long = 3000, checkCancelled: () -> Unit = {}): Int {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val shared = synchronized(this) {
            check(!closed) { "Server readiness disposed" }
            attempt?.takeUnless { it.isCompletedExceptionally || it.isCancelled } ?: CompletableFuture<Int>().also { next ->
                attempt = next
                executor.execute {
                    try {
                        val port = start()
                        check(port in 1..65535) { "Server port unavailable" }
                        next.complete(port)
                    } catch (e: Throwable) {
                        next.completeExceptionally(e)
                    }
                }
            }
        }
        while (true) {
            checkCancelled()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("Terminal icon server startup timed out")
            try {
                return shared.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Poll only the shared in-process completion, allowing platform cancellation to propagate promptly.
            } catch (_: ExecutionException) {
                throw TerminalIconException("unavailable", "Terminal icon server could not start")
            }
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            attempt?.completeExceptionally(IllegalStateException("Server readiness disposed"))
        }
        executor.shutdownNow()
    }
}
