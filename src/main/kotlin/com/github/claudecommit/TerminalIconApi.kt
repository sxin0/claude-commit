package com.github.claudecommit

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import org.jetbrains.ide.BuiltInServerManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
internal class TerminalIconApi private constructor(
    private val readEnabled: () -> Boolean,
    private val writeEnabled: (Boolean) -> Unit,
    private val serverReadiness: TerminalIconServerReadiness,
    private val backendAvailable: () -> Boolean,
    private val clearIcons: () -> Unit,
    private val checkPreparationThread: () -> Unit,
    private val checkCancelled: () -> Unit,
) : Disposable {
    constructor() : this(
        { ClaudeCommitSettings.getInstance().state.terminalIconApiEnabled },
        { ClaudeCommitSettings.getInstance().state.terminalIconApiEnabled = it },
        TerminalIconServerReadiness(start = {
            val manager = BuiltInServerManager.getInstance().waitForStart()
            check(manager.serverDisposable != null) { "Built-in server is unavailable" }
            manager.port
        }),
        { TerminalIconBackend.find() != null },
        { TerminalIconBackend.find()?.reset(); Unit },
        { check(!ApplicationManager.getApplication().isDispatchThread) { "Connection preparation must run off EDT" } },
        { com.intellij.openapi.progress.ProgressManager.checkCanceled() },
    )
    internal class Connection(val instance: String, val token: String, val generation: Long) {
        val path: String get() = "/api/claude-commit/v1/$instance/terminal-icon"
    }
    @Volatile private var connection: Connection? = null
    @Volatile private var disposed = false
    private var generation = 0L
    private val pending = ConcurrentHashMap.newKeySet<() -> Unit>()
    private val executor = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "CC Kit terminal icon").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    @Synchronized fun current(): Connection? {
        if (disposed || !readEnabled()) return null
        return connection ?: Connection(UUID.randomUUID().toString(), randomToken(), ++generation).also { connection = it }
    }

    fun authorize(c: Connection, supplied: String?): Boolean = supplied != null && MessageDigest.isEqual(
        ("Bearer " + c.token).toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))

    fun isActive(c: Connection): Boolean = !disposed && connection === c && readEnabled()

    /** Credentials are prepared off EDT, but published under the same lock as enable/reset. */
    private fun prepareEnvironment(): Pair<Connection, Map<String, String>> {
        checkPreparationThread()
        val c = current() ?: throw TerminalIconException("disabled", "API is disabled")
        if (!backendAvailable()) throw TerminalIconException("unsupported_terminal", "Terminal plugin is unavailable")
        val port = serverReadiness.awaitPort(checkCancelled = {
            checkCancelled()
            if (!isActive(c)) throw TerminalIconException("disabled", "Connection was reset")
        })
        return c to mapOf(
            TerminalIconEnvironment.URL to "http://127.0.0.1:$port${c.path}",
            TerminalIconEnvironment.TOKEN to c.token,
        )
    }

    fun injectConnectionEnvironment(env: MutableMap<String, String>) {
        val (c, values) = prepareEnvironment()
        synchronized(this) {
            if (!isActive(c)) throw TerminalIconException("disabled", "Connection was reset")
            env.putAll(values)
        }
    }

    fun connectionEnvironment(): String {
        val (c, values) = prepareEnvironment()
        return synchronized(this) {
            if (!isActive(c)) throw TerminalIconException("disabled", "Connection was reset")
            TerminalIconEnvironment.exports(values)
        }
    }

    /** Returns cancellation. Each accepted request receives exactly one completion, including reset/unload. */
    fun submit(c: Connection, body: ByteArray, alive: () -> Boolean, complete: (Int, String) -> Unit): () -> Unit {
        val done = AtomicBoolean(false)
        val operation = TerminalIconOperation { alive() && isActive(c) }
        lateinit var cancel: () -> Unit
        fun finish(status: Int, response: String) {
            if (done.compareAndSet(false, true)) {
                pending.remove(cancel)
                complete(status, response)
            }
        }
        val task = FutureTask {
            var id: JsonElement = JsonNull.INSTANCE
            try {
                val request = TerminalIconProtocol.parse(body.toString(Charsets.UTF_8))
                id = request.id
                if (!operation.active()) throw TerminalIconException("disabled", "Request is no longer active")
                val backend = TerminalIconBackend.find() ?: throw TerminalIconException("unsupported_terminal", "Terminal plugin is unavailable")
                val result = backend.execute(request, operation)
                finish(200, JsonObject().apply { add("id", id); add("result", result) }.toString())
            } catch (e: TerminalIconException) {
                finish(status(e.code), error(id, e.code, e.message ?: e.code))
            } catch (_: LinkageError) {
                finish(503, error(id, "unsupported_terminal", "This IDE does not expose a required capability"))
            } catch (_: Throwable) {
                // Do not log exception/request data: parser/renderer messages may contain caller content.
                finish(500, error(id, "internal_error", "Unable to complete icon request"))
            }
        }
        cancel = {
            operation.cancel()
            finish(503, error(JsonNull.INSTANCE, "cancelled", "Request was cancelled or the connection reset"))
            task.cancel(true)
        }
        pending.add(cancel)
        if (!isActive(c)) { cancel(); return cancel }
        try { executor.execute(task) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            finish(429, error(JsonNull.INSTANCE, "busy", "Icon request queue is full"))
        }
        return cancel
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(this) {
            if (readEnabled() == enabled) return
            writeEnabled(enabled)
            connection = null
            generation++
        }
        clearPendingAndIcons()
    }

    fun reset() {
        synchronized(this) { connection = null; generation++ }
        clearPendingAndIcons()
    }

    private fun clearPendingAndIcons() {
        pending.toList().forEach { it() }
        clearIcons()
    }

    override fun dispose() {
        disposed = true
        synchronized(this) { connection = null; generation++ }
        pending.toList().forEach { it() }
        serverReadiness.close()
        executor.shutdownNow()
    }

    companion object {
        internal fun forTests(
            readEnabled: () -> Boolean, writeEnabled: (Boolean) -> Unit, readiness: TerminalIconServerReadiness,
        ): TerminalIconApi = TerminalIconApi(readEnabled, writeEnabled, readiness, { true }, {}, {}, {})

        fun getInstance(): TerminalIconApi = ApplicationManager.getApplication().getService(TerminalIconApi::class.java)
        fun error(id: JsonElement = JsonNull.INSTANCE, code: String, message: String): String = JsonObject().apply {
            add("id", id)
            add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
        }.toString()
        private fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        private fun status(code: String): Int = when (code) {
            "lease_conflict", "revision_conflict", "ambiguous_session" -> 409
            "session_not_found" -> 404
            "unsupported_terminal", "disabled", "session_not_ready" -> 503
            "resource_limit" -> 429
            else -> 400
        }
    }
}
