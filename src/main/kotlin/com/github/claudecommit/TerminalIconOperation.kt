package com.github.claudecommit

/** Linearizes cancellation, UI commit, and rendered-resource ownership transfer. */
internal class TerminalIconOperation(private val connectionActive: () -> Boolean) {
    private var cancelled = false

    @Synchronized fun active(): Boolean = !cancelled && connectionActive()

    @Synchronized fun cancel() { cancelled = true }

    /** Once entered, cancellation waits for commit/resource handoff; otherwise it prevents commit. */
    @Synchronized fun <T> commit(action: () -> T): T {
        if (!active()) throw TerminalIconException("cancelled", "Request is no longer active")
        return action()
    }

    /** A worker interrupted while EDT is committing cannot close the resource before handoff finishes. */
    @Synchronized fun releaseIfUnclaimed(release: () -> Unit) {
        // Also closes the gate on helper timeout/interruption before EDT acquires commit rights.
        cancelled = true
        release()
    }
}
