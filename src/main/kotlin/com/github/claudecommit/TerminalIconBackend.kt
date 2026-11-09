package com.github.claudecommit

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No Terminal plugin types may cross this optional service boundary. */
internal interface TerminalIconBackend {
    fun execute(request: TerminalIconRequest, operation: TerminalIconOperation): JsonObject
    fun reset()

    companion object {
        fun find(): TerminalIconBackend? = ApplicationManager.getApplication().getService(TerminalIconBackend::class.java)
    }
}

/** A timed-out queued EDT operation is invalidated before it can mutate UI. */
internal fun <T> terminalIconOnEdt(action: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return action()
    val enabled = AtomicBoolean(true)
    val result = CompletableFuture<T>()
    app.invokeLater({
        if (enabled.get() && !result.isCancelled) {
            try { result.complete(action()) } catch (t: Throwable) { result.completeExceptionally(t) }
        }
    }, com.intellij.openapi.application.ModalityState.any())
    try {
        return result.get(10, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } finally {
        enabled.set(false)
        result.cancel(false)
    }
}
