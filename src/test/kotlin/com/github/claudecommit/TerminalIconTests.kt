package com.github.claudecommit

private val session = "a5747e71-903d-415f-a15f-0cb79ff33f24"
private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6\" fill=\"#d77757\"/></svg>"

fun main() {
    runTerminalIconEnvironmentTests()
    runTerminalIconAssetTests()
    val jsonSvg = com.google.gson.Gson().toJson(svg)
    val request = """{"id":"one","method":"surface.icon.set","params":{"terminal_session_id":"$session","owner_id":"tests","asset":{"frames":[{"svg":$jsonSvg,"duration_ms":100}],"loop":false}}}"""
    val parsed = TerminalIconProtocol.parse(request)
    check(parsed.sessionId == session && parsed.asset!!.frames.size == 1 && !parsed.clear)
    fun invalid(body: String) {
        check(runCatching { TerminalIconProtocol.parse(body) }.exceptionOrNull() is TerminalIconException)
    }
    invalid(request.replace("\"loop\":false", "\"loop\":\"false\""))
    invalid(request.replace("\"duration_ms\":100", "\"duration_ms\":1"))
    invalid(request.replace("\"owner_id\":\"tests\"", "\"owner_id\":\"tests\",\"owner_id\":\"other\""))
    invalid(request.replace("\"id\":\"one\"", "\"id\":\"one\",\"unknown\":true"))
    invalid(request.replace(session, "not-a-uuid"))
    invalid(request.replace("\"duration_ms\":100", "\"duration_ms\":1e2"))
    invalid(request + "{}")
    invalid(request.replace("\"id\":\"one\",", ""))
    invalid(request.replace("\"loop\":false", "\"loop\":false,\"static_frame\":1"))
    val heartbeat = """{"id":2,"method":"surface.icon.set","params":{"terminal_session_id":"$session","owner_id":"tests","lease_token":"token","revision":1,"ttl_seconds":60}}"""
    check(TerminalIconProtocol.parse(heartbeat).asset == null)
    invalid(heartbeat.replace(",\"lease_token\":\"token\"", ""))
    val lease = TerminalIconLease(session, "owner", 10, 2, 1000, "token")
    lease.check(session, "owner", "token", 10)
    check(!lease.expired(1_000_001_000))
    check(lease.expired(2_000_001_000))
    check(runCatching { lease.check(session, "owner", "token", 9) }.exceptionOrNull() is TerminalIconException)
    check(runCatching { lease.check(session, "other", "token", 10) }.exceptionOrNull() is TerminalIconException)
    lease.renew(11, null, 3_000_000_000)
    check(lease.ttl == 2 && !lease.expired(4_000_000_000))
    check(lease.expired(5_000_000_000))
    val next = TerminalIconLease(session, "owner", 12, null, 0, "new-token")
    check(runCatching { next.check(session, "owner", "token", 10) }.exceptionOrNull() is TerminalIconException)
    check(!next.expired(Long.MAX_VALUE))
    testOperationHandoff()
    println("Terminal icon protocol, lease, cancellation and asset tests passed")
}

private fun testOperationHandoff() {
    val cancelled = TerminalIconOperation { true }
    cancelled.cancel()
    var mutated = false
    check(runCatching { cancelled.commit { mutated = true } }.exceptionOrNull() is TerminalIconException)
    check(!mutated)
    val timeoutBeforeCommit = TerminalIconOperation { true }
    var closed = false
    timeoutBeforeCommit.releaseIfUnclaimed { closed = true }
    check(closed)
    check(runCatching { timeoutBeforeCommit.commit { mutated = true } }.exceptionOrNull() is TerminalIconException)
    check(!mutated)

    val operation = TerminalIconOperation { true }
    val entered = java.util.concurrent.CountDownLatch(1)
    val resume = java.util.concurrent.CountDownLatch(1)
    val cancelStarted = java.util.concurrent.CountDownLatch(1)
    val cancelDone = java.util.concurrent.CountDownLatch(1)
    val released = java.util.concurrent.atomic.AtomicBoolean(false)
    var installed = false
    val commitThread = Thread {
        operation.commit {
            entered.countDown()
            check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
            check(!released.get())
            installed = true
        }
    }
    commitThread.start()
    check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
    val cancelThread = Thread {
        cancelStarted.countDown()
        operation.cancel()
        operation.releaseIfUnclaimed { if (!installed) released.set(true) }
        cancelDone.countDown()
    }
    cancelThread.start()
    check(cancelStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
    check(!cancelDone.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
    resume.countDown()
    commitThread.join(5000)
    cancelThread.join(5000)
    check(!commitThread.isAlive && !cancelThread.isAlive && installed && !released.get())
    check(runCatching { operation.commit { error("Must not commit twice after cancellation") } }.exceptionOrNull() is TerminalIconException)
}
