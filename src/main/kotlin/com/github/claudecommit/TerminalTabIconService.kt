package com.github.claudecommit

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import java.beans.PropertyChangeListener
import java.util.IdentityHashMap
import javax.swing.Icon
import javax.swing.Timer

/** All ownership and presentation mutations are serialized on EDT. Rendering never is. */
internal class TerminalTabIconService : TerminalIconBackend, Disposable {
    private class Entry(
        val target: TerminalIconTarget,
        val lease: TerminalIconLease,
        var icon: TerminalIconAnimation,
        var fallback: Icon?,
        val showBefore: Boolean?,
        val disposable: Disposable,
    ) {
        var changing = false
        lateinit var listener: PropertyChangeListener
    }

    private val entries = IdentityHashMap<Content, Entry>()
    private var serial = 0L
    private var disposed = false
    private val timer = Timer(1000) { sweep() }

    override fun execute(request: TerminalIconRequest, operation: TerminalIconOperation): JsonObject {
        val initial = terminalIconOnEdt {
            checkActive(operation::active)
            sweep()
            val target = TerminalSessionResolver.resolve(request.sessionId)
            val entry = entries[target.content]
            checkLease(request, entry)
            if (entry == null && entries.size >= 32) throw TerminalIconException("resource_limit", "At most 32 tab icons can be active")
            target to entry?.lease?.revision
        }
        // Normalize and render before acquiring a lease; invalid assets never reserve a tab.
        val rendered = request.asset?.let {
            TerminalIconAssets.render(it, request.staticPlayback)
        }
        var installed = false
        try {
            return terminalIconOnEdt { operation.commit {
                checkActive(operation::active)
                sweep()
                val target = TerminalSessionResolver.resolve(request.sessionId)
                if (target.content !== initial.first.content || target.project !== initial.first.project) {
                    throw TerminalIconException("session_not_found", "Terminal ownership changed while processing")
                }
                var entry = entries[target.content]
                checkLease(request, entry)
                if (entry?.lease?.revision != initial.second) throw TerminalIconException("revision_conflict", "Tab changed while rendering")
                if (request.clear) {
                    release(entry!!, true)
                    return@commit JsonObject().apply { addProperty("cleared", true) }
                }
                if (entry == null) {
                    if (entries.size >= 32) throw TerminalIconException("resource_limit", "At most 32 tab icons can be active")
                    val child = Disposer.newDisposable("Terminal tab icon")
                    entry = Entry(target, TerminalIconLease(request.sessionId, request.owner, ++serial,
                        request.ttlSeconds, System.nanoTime()), rendered!!,
                        target.content.icon, target.content.getUserData(ToolWindow.SHOW_CONTENT_ICON), child)
                    val created = entry
                    created.listener = PropertyChangeListener { event ->
                        if (event.propertyName == Content.PROP_ICON && !created.changing && entries[target.content] === created) {
                            created.fallback = target.content.icon
                            setIcon(created)
                        }
                    }
                    entries[target.content] = created
                    target.content.addPropertyChangeListener(created.listener)
                    Disposer.register(child, Disposable {
                        if (entries[target.content] === created) {
                            entries.remove(target.content)
                            target.content.removePropertyChangeListener(created.listener)
                            created.icon.close()
                            if (entries.isEmpty()) timer.stop()
                        }
                    })
                    Disposer.register(target.content, child)
                } else {
                    if (rendered != null) {
                        entry.icon.close()
                        entry.icon = rendered
                    }
                    entry.lease.renew(++serial, request.ttlSeconds, System.nanoTime())
                }
                setIcon(entry)
                installed = rendered != null
                if (!timer.isRunning) timer.start()
                JsonObject().apply {
                    addProperty("lease_token", entry.lease.token)
                    addProperty("revision", entry.lease.revision)
                    addProperty("persistent", entry.lease.ttl == null)
                    entry.lease.ttl?.let { addProperty("ttl_seconds", it) }
                }
            } }
        } finally {
            operation.releaseIfUnclaimed { if (!installed) rendered?.close() }
        }
    }

    private fun checkLease(request: TerminalIconRequest, entry: Entry?) {
        if (entry == null) {
            if (request.token != null || request.clear || request.asset == null) throw TerminalIconException("lease_conflict", "No matching active lease")
        } else {
            entry.lease.check(request.sessionId, request.owner, request.token, request.revision)
        }
    }

    private fun setIcon(entry: Entry) {
        entry.changing = true
        try {
            entry.target.content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            entry.target.content.icon = entry.icon
        } finally { entry.changing = false }
    }

    private fun checkActive(active: () -> Boolean) {
        if (disposed || !active()) throw TerminalIconException("disabled", "Request is no longer active")
    }

    private fun sweep() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val now = System.nanoTime()
        for (entry in entries.values.toList()) {
            val target = entry.target
            if (target.project.isDisposed || !target.content.isValid) {
                release(entry, false)
                continue
            }
            val stillAttached = try { TerminalSessionResolver.resolve(target.sessionId).content === target.content }
                catch (_: TerminalIconException) { false }
                catch (_: LinkageError) { false }
            if (!stillAttached || entry.lease.expired(now)) release(entry, true)
        }
    }

    private fun release(entry: Entry, restore: Boolean) {
        val content = entry.target.content
        if (entries.remove(content) !== entry) return
        content.removePropertyChangeListener(entry.listener)
        if (restore && content.isValid && content.icon === entry.icon) {
            if (content.getUserData(ToolWindow.SHOW_CONTENT_ICON) == true) {
                content.putUserData(ToolWindow.SHOW_CONTENT_ICON, entry.showBefore)
            }
            content.icon = entry.fallback
        }
        entry.icon.close()
        Disposer.dispose(entry.disposable)
        if (entries.isEmpty()) timer.stop()
    }

    override fun reset() = terminalIconOnEdt {
        entries.values.toList().forEach { release(it, true) }
    }

    override fun dispose() {
        terminalIconOnEdt {
            disposed = true
            entries.values.toList().forEach { release(it, true) }
            timer.stop()
        }
    }
}
