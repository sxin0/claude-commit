package com.github.claudecommit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.terminal.JBTerminalWidget
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

internal data class TerminalIconTarget(val project: Project, val content: Content, val sessionId: String)

/** Every call runs on EDT, including the final post-render recheck. No tool window is initialized. */
internal object TerminalSessionResolver {
    fun resolve(sessionId: String): TerminalIconTarget {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (com.intellij.idea.AppMode.isRemoteDevHost() || com.intellij.util.PlatformUtils.isJetBrainsClient()) {
            throw TerminalIconException("unsupported_terminal", "Remote Development is not supported")
        }
        val matches = mutableListOf<TerminalIconTarget>()
        var unsupported = false
        var notReady = false
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val window = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: continue
            val manager = window.contentManagerIfCreated ?: continue
            if (manager.isDisposed) continue
            val attached = manager.contentsRecursively.toSet()
            try {
                for (widget in TerminalToolWindowManager.getInstance(project).terminalWidgets) {
                    val shell = JBTerminalWidget.asJediTermWidget(widget) as? ShellTerminalWidget ?: continue
                    val options = shell.startupOptions
                    if (options == null) { notReady = true; continue }
                    if (options.envVariables["TERM_SESSION_ID"] != sessionId) continue
                    val content = TerminalToolWindowManager.getInstance(project).getContainer(widget)?.content ?: continue
                    if (content !in attached) continue
                    if (!localDescriptor(options, "getEelDescriptorNotNull")) throw TerminalIconException("unsupported_terminal", "Only local terminal processes are supported")
                    matches.add(TerminalIconTarget(project, content, sessionId))
                }
            } catch (_: LinkageError) {
                unsupported = true
            }
            try {
                val result = ReworkedIconResolver.find(project, sessionId, attached)
                matches.addAll(result.first)
                notReady = notReady || result.second
            } catch (_: LinkageError) {
                unsupported = true
            }
        }
        val distinct = matches.distinctBy { it.content }
        if (distinct.size > 1) throw TerminalIconException("ambiguous_session", "Session UUID identifies multiple tabs")
        val target = distinct.singleOrNull()
        if (target != null && target.content.isValid) return target
        val code = when { unsupported -> "unsupported_terminal"; notReady -> "session_not_ready"; else -> "session_not_found" }
        throw TerminalIconException(code, "No started, supported tool-window terminal matches this session")
    }

    /** Use the execution descriptor, never project location or PID heuristics. Missing API fails closed. */
    fun localDescriptor(value: Any, method: String): Boolean {
        try {
            val descriptor = value.javaClass.getMethod(method).invoke(value) ?: return false
            return descriptor.javaClass.name == "com.intellij.platform.eel.provider.LocalEelDescriptor"
        } catch (_: ReflectiveOperationException) {
            throw TerminalIconException("unsupported_terminal", "This SDK does not expose the terminal execution environment")
        }
    }
}

/** Isolate evolving Reworked APIs so older SDKs can retain Classic support. */
private object ReworkedIconResolver {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun find(project: Project, sessionId: String, attached: Set<Content>): Pair<List<TerminalIconTarget>, Boolean> {
        val result = mutableListOf<TerminalIconTarget>()
        var pending = false
        for (tab in com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager.getInstance(project).tabs) {
            if (tab.content !in attached || !tab.content.isValid) continue
            val startup = tab.view.startupOptionsDeferred
            if (!startup.isCompleted || startup.isCancelled) { pending = true; continue }
            val options = startup.getCompleted()
            if (options.envVariables["TERM_SESSION_ID"] != sessionId) continue
            try {
                // sessionDeferred is newer than the baseline; use a capability boundary, not direct linkage.
                val deferred = tab.view.javaClass.getMethod("getSessionDeferred").invoke(tab.view) as? Deferred<*>
                    ?: throw TerminalIconException("unsupported_terminal", "Terminal session descriptor is unavailable")
                if (!deferred.isCompleted || deferred.isCancelled) { pending = true; continue }
                val session = deferred.getCompleted() ?: continue
                if (!TerminalSessionResolver.localDescriptor(session, "getEelDescriptor")) {
                    throw TerminalIconException("unsupported_terminal", "Only local terminal processes are supported")
                }
            } catch (_: ReflectiveOperationException) {
                throw TerminalIconException("unsupported_terminal", "This SDK does not expose the terminal execution environment")
            }
            result.add(TerminalIconTarget(project, tab.content, sessionId))
        }
        return result to pending
    }
}
