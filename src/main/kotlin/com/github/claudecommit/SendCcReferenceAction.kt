package com.github.claudecommit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.toolwindow.findTabByContent
import com.intellij.ui.content.Content
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
 */
class SendCcReferenceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val reference = CcReference.from(e) ?: return

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        val content = toolWindow?.contentManager?.selectedContent
        if (content == null) {
            notifyNoTerminal(project)
            return
        }
        try {
            if (!sendToReworkedTerminal(project, content, "$reference ") &&
                !sendToClassicTerminal(content, "$reference ")
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
