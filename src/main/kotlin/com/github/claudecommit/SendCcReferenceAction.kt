package com.github.claudecommit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.toolwindow.findTabByContent
import com.intellij.ui.content.Content
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Editor popup menu action: types the [CcReference] of the current selection
 * into the active tab of the Terminal tool window (e.g. a running Claude Code
 * session), then focuses the terminal so the user can keep typing.
 *
 * The text is written as shell input — a trailing space is appended but no
 * Enter, so nothing is executed. The Reworked Terminal's `TerminalView.sendText`
 * is tried first (its widgets expose no TtyConnector); the classic terminal
 * falls back to writing the pty directly.
 *
 * When the Terminal tool window has no tab at all and
 * [ClaudeCommitSettings.State.autoStartClaudeWhenNoTerminal] is on (the default),
 * a fresh terminal is opened, the Claude CLI is launched in it, and the reference
 * is typed once Claude is ready — instead of warning that no terminal is open.
 */
class SendCcReferenceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && CcReference.isAvailable(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val reference = CcReference.from(e) ?: return
        // Trailing space, no Enter: typed as terminal input without executing.
        val input = "$reference "

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        val content = toolWindow?.contentManager?.selectedContent
        if (content == null) {
            // No terminal tab open: open one and start Claude Code in it (default),
            // rather than telling the user to do it by hand.
            if (ClaudeCommitSettings.getInstance().state.autoStartClaudeWhenNoTerminal) {
                openClaudeInNewTerminal(project, input)
            } else {
                notifyNoTerminal(project)
            }
            return
        }
        try {
            if (!sendToReworkedTerminal(project, content, input) &&
                !sendToClassicTerminal(content, input)
            ) {
                notifyNoTerminal(project)
                return
            }
        } catch (ex: Exception) {
            notifyError(project, ex.message ?: ex.javaClass.simpleName)
            return
        }
        toolWindow.activate(null)
    }

    private fun sendToReworkedTerminal(project: Project, content: Content, text: String): Boolean =
        try {
            ReworkedTerminalSender.send(project, content, text)
        } catch (e: LinkageError) {
            // The frontend TerminalView API is newer than since-build 242; on IDEs
            // without it the classes fail to link and the classic path takes over.
            false
        }

    private fun sendToClassicTerminal(content: Content, text: String): Boolean {
        val connector = TerminalToolWindowManager.findWidgetByContent(content)?.ttyConnector ?: return false
        connector.write(text)
        return true
    }

    /**
     * Opens a new terminal, launches the Claude CLI, and types [reference] into it.
     * Mirrors the send path's Reworked-first / classic-fallback structure; the
     * Reworked launcher is isolated so it only fails to link on older IDEs.
     */
    private fun openClaudeInNewTerminal(project: Project, reference: String) {
        val claudeCommand = ClaudeCommitSettings.getInstance().state.claudePath
        try {
            if (!launchInReworkedTerminal(project, claudeCommand, reference) &&
                !ClassicTerminalLauncher.launch(project, claudeCommand, reference)
            ) {
                notifyNoTerminal(project)
                return
            }
        } catch (ex: Exception) {
            notifyError(project, ex.message ?: ex.javaClass.simpleName)
            return
        }
        // createTab/createLocalShellWidget already add the tab; activate so it shows.
        ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)?.activate(null)
    }

    private fun launchInReworkedTerminal(project: Project, claudeCommand: String, reference: String): Boolean =
        try {
            ReworkedTerminalLauncher.launch(project, claudeCommand, reference)
        } catch (e: LinkageError) {
            false
        }

    private fun notifyNoTerminal(project: Project) = notify(
        project,
        ClaudeCommitBundle.message("notify.noTerminal.title"),
        ClaudeCommitBundle.message("notify.noTerminal.content"),
        NotificationType.WARNING
    )

    private fun notifyError(project: Project, reason: String) = notify(
        project, ClaudeCommitBundle.message("notify.send.failed.title"), reason, NotificationType.ERROR
    )

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code Commit")
            .createNotification(title, content, type)
            .notify(project)
    }
}

/** How long to wait for the freshly launched Claude CLI to start accepting input. */
private const val READY_POLL_MS = 150L
private const val READY_TIMEOUT_MS = 10_000L
private const val READY_SETTLE_MS = 300L

/**
 * Isolates the Reworked Terminal frontend classes so that on IDEs predating
 * them only this object fails to load, caught as [LinkageError] at the call site.
 */
private object ReworkedTerminalSender {
    /** Sends [text] as input (without executing) to the tab owning [content]; false if the tab is not a Reworked Terminal. */
    fun send(project: Project, content: Content, text: String): Boolean {
        val tab = TerminalToolWindowTabsManager.getInstance(project).findTabByContent(content) ?: return false
        tab.view.sendText(text)
        return true
    }
}

/**
 * Reworked-terminal counterpart of [ReworkedTerminalSender]: opens a new tab,
 * launches Claude, and types the reference once it is up. Isolated for the same
 * [LinkageError] reason.
 */
private object ReworkedTerminalLauncher {
    /** Opens a new tab running a shell, launches [claudeCommand], then types [reference] into Claude once it starts. */
    fun launch(project: Project, claudeCommand: String, reference: String): Boolean {
        val builder = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
        project.basePath?.let { builder.workingDirectory(it) }
        val view = builder.requestFocus(true).createTab().view
        // The Enter (\r) runs claude; it becomes the shell's foreground child, so
        // hasChildProcesses() flips true once it is up. Only then type the reference
        // so it lands in Claude's input box rather than the still-idle shell.
        view.coroutineScope.launch {
            view.sendText("$claudeCommand\r")
            var waited = 0L
            while (waited < READY_TIMEOUT_MS && !view.hasChildProcesses()) {
                delay(READY_POLL_MS)
                waited += READY_POLL_MS
            }
            delay(READY_SETTLE_MS)
            view.sendText(reference)
        }
        return true
    }
}

/** Classic-terminal fallback launcher — needs no [LinkageError] isolation (the API is old and stable). */
private object ClassicTerminalLauncher {
    // createLocalShellWidget is deprecated alongside the classic terminal itself; it
    // is the fallback for IDEs/engines without the Reworked Terminal, so we keep it.
    @Suppress("DEPRECATION")
    fun launch(project: Project, claudeCommand: String, reference: String): Boolean {
        val widget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(project.basePath, null)
        // executeCommand waits for the shell prompt before running claude (adds the Enter).
        widget.executeCommand(claudeCommand)
        // Poll off the EDT for claude to start, then write the reference (no Enter).
        ApplicationManager.getApplication().executeOnPooledThread {
            var waited = 0L
            while (waited < READY_TIMEOUT_MS && !claudeRunning(widget)) {
                Thread.sleep(READY_POLL_MS)
                waited += READY_POLL_MS
            }
            Thread.sleep(READY_SETTLE_MS)
            widget.ttyConnector?.write(reference)
        }
        return true
    }

    /** hasRunningCommands throws until the pty is connected; treat that as "not ready yet". */
    private fun claudeRunning(widget: ShellTerminalWidget): Boolean =
        try {
            widget.hasRunningCommands()
        } catch (_: IllegalStateException) {
            false
        }
}
