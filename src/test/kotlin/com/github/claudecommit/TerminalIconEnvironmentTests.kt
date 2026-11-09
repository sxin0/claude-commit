package com.github.claudecommit

import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun runTerminalIconEnvironmentTests() {
    val url = TerminalIconEnvironment.URL
    val token = TerminalIconEnvironment.TOKEN
    fun initial() = mutableMapOf(url to "old-url", token to "old-token", "PATH" to "/bin", "TERM_SESSION_ID" to "test-session")
    val values = mapOf(url to "instance-one-url", token to "synthetic-token")
    val env = initial()
    TerminalIconEnvironment.customize(env, true) { it.putAll(values) }
    check(env[url] == values[url] && env[token] == values[token])
    check(env["PATH"] == "/bin" && env["TERM_SESSION_ID"] == "test-session")
    check(TerminalIconEnvironment.exports(values).contains("export $url="))
    for (local in listOf(false, true)) for (remote in listOf(false, true)) {
        for (client in listOf(false, true)) for (edt in listOf(false, true)) {
            val eligible = TerminalIconEnvironment.eligible(local, remote, client, edt)
            check(eligible == (local && !remote && !client && !edt))
            if (!eligible) {
                val skipped = initial()
                TerminalIconEnvironment.customize(skipped, false) { error("Must not fetch credentials") }
                check(url !in skipped && token !in skipped && skipped["PATH"] == "/bin")
            }
        }
    }
    val failed = initial()
    TerminalIconEnvironment.customize(failed, true) { it[url] = "partial"; throw IllegalStateException("failure") }
    check(url !in failed && token !in failed)
    val disabled = initial()
    TerminalIconEnvironment.customize(disabled, true) { throw TerminalIconException("disabled", "disabled") }
    check(url !in disabled && token !in disabled)

    // Exercise actual adapter methods, not just the map policy. Project is not consulted.
    val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
        error("Unexpected project access: ${method.name}")
    } as Project
    var observed: EelDescriptor? = null
    val adapter = TerminalIconEnvironmentCustomizer.forTests({ observed = it; it === LocalEelDescriptor }, { it.putAll(values) })
    val command = listOf("/bin/zsh", "-l")
    val adapted = initial()
    check(adapter.customizeCommandAndEnvironment(project, null, command, adapted, LocalEelDescriptor) === command)
    check(observed === LocalEelDescriptor && adapted[token] == values[token])
    val legacy = arrayOf("/bin/zsh")
    val legacyEnv = initial()
    check(adapter.customizeCommandAndEnvironment(project, null, legacy, legacyEnv) === legacy)
    check(url !in legacyEnv && token !in legacyEnv)

    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val starts = AtomicInteger()
    val readiness = TerminalIconServerReadiness(start = {
        starts.incrementAndGet(); entered.countDown()
        check(resume.await(5, TimeUnit.SECONDS)); 63342
    })
    try {
        check(runCatching { readiness.awaitPort(20) }.exceptionOrNull() is TimeoutException)
        check(entered.await(1, TimeUnit.SECONDS))
        val cancelled = AtomicReference<Throwable?>()
        val cancelWaiter = Thread {
            try { readiness.awaitPort(2000) { throw java.util.concurrent.CancellationException() } }
            catch (e: Throwable) { cancelled.set(e) }
        }
        val port = AtomicInteger()
        val other = Thread { port.set(readiness.awaitPort(2000)) }
        cancelWaiter.start(); other.start(); cancelWaiter.join(1000)
        check(cancelled.get() is java.util.concurrent.CancellationException)
        resume.countDown(); other.join(3000)
        check(port.get() == 63342 && starts.get() == 1)
        check(readiness.awaitPort(100) == 63342)
    } finally { resume.countDown(); readiness.close() }

    val attempts = AtomicInteger()
    val retries = TerminalIconServerReadiness(start = { if (attempts.incrementAndGet() == 1) error("transient") else 63343 })
    try {
        check(runCatching { retries.awaitPort(1000) }.exceptionOrNull() is TerminalIconException)
        check(retries.awaitPort(1000) == 63343 && attempts.get() == 2)
    } finally { retries.close() }
    check(runCatching { retries.awaitPort(100) }.isFailure)

    // Time spent queued counts against this waiter's deadline.
    val executor = Executors.newSingleThreadExecutor()
    val unblock = CountDownLatch(1)
    executor.execute { unblock.await() }
    val queued = TerminalIconServerReadiness(start = { 63344 }, executor = executor)
    try {
        check(runCatching { queued.awaitPort(20) }.exceptionOrNull() is TimeoutException)
        unblock.countDown()
        check(queued.awaitPort(1000) == 63344)
    } finally { unblock.countDown(); queued.close() }
    testActualConnectionGeneration()
}

private fun testActualConnectionGeneration() {
    for (change in listOf("reset", "disable", "dispose")) {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val enabled = java.util.concurrent.atomic.AtomicBoolean(true)
        val readiness = TerminalIconServerReadiness(start = {
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)); 63342
        })
        val api = TerminalIconApi.forTests(enabled::get, enabled::set, readiness)
        val env = mutableMapOf<String, String>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { api.injectConnectionEnvironment(env) } catch (e: Throwable) { failure.set(e) }
        }
        try {
            val old = requireNotNull(api.current())
            worker.start()
            check(entered.await(2, TimeUnit.SECONDS))
            when (change) {
                "reset" -> api.reset()
                "disable" -> api.setEnabled(false)
                else -> api.dispose()
            }
            resume.countDown(); worker.join(4000)
            check(!worker.isAlive && failure.get() != null && env.isEmpty())
            check(!api.isActive(old))
            if (change != "dispose") {
                api.setEnabled(true)
                val fresh = requireNotNull(api.current())
                check(fresh !== old && fresh.token != old.token && fresh.path != old.path)
                api.injectConnectionEnvironment(env)
                check(env[TerminalIconEnvironment.TOKEN] == fresh.token)
                check(api.connectionEnvironment() == TerminalIconEnvironment.exports(env))
            }
        } finally { resume.countDown(); api.dispose(); worker.join(4000) }
    }
    val first = TerminalIconApi.forTests({ true }, {}, TerminalIconServerReadiness(start = { 63342 }))
    val second = TerminalIconApi.forTests({ true }, {}, TerminalIconServerReadiness(start = { 63343 }))
    try {
        val a = mutableMapOf<String, String>(); val b = mutableMapOf<String, String>()
        first.injectConnectionEnvironment(a); second.injectConnectionEnvironment(b)
        check(a[TerminalIconEnvironment.URL] != b[TerminalIconEnvironment.URL])
        check(a[TerminalIconEnvironment.TOKEN] != b[TerminalIconEnvironment.TOKEN])
    } finally { first.dispose(); second.dispose() }
}
