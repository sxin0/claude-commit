package com.github.claudecommit

import java.util.UUID

/** UI-independent lease rules, with a caller-supplied monotonic clock/revision source. */
internal class TerminalIconLease(
    val sessionId: String,
    val owner: String,
    var revision: Long,
    var ttl: Int?,
    now: Long,
    val token: String = UUID.randomUUID().toString(),
) {
    var deadline: Long? = deadlineFrom(now, ttl)
        private set

    fun check(session: String, ownerId: String, leaseToken: String?, expectedRevision: Long?) {
        if (sessionId != session || owner != ownerId || token != leaseToken) {
            throw TerminalIconException("lease_conflict", "Tab is owned by another lease")
        }
        if (revision != expectedRevision) throw TerminalIconException("revision_conflict", "Expected revision does not match")
    }

    fun renew(nextRevision: Long, requestedTtl: Int?, now: Long) {
        check(nextRevision > revision)
        revision = nextRevision
        ttl = requestedTtl ?: ttl
        deadline = deadlineFrom(now, ttl)
    }

    fun expired(now: Long): Boolean = deadline?.let { now - it >= 0L } == true

    private fun deadlineFrom(now: Long, ttl: Int?): Long? = ttl?.let { now + it * 1_000_000_000L }
}
