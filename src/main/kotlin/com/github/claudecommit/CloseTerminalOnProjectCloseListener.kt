package com.github.claudecommit

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Hides the Terminal tool window while a project is closing, if the user enabled
 * [ClaudeCommitSettings.State.closeTerminalOnProjectClose].
 *
 * The IDE restores the tool windows that were open in the previous session, so the
 * Terminal reappears on the next open — but its old shell is gone, leaving an empty
 * panel. Hiding it here makes the layout that is about to be saved say "hidden", so
 * the Terminal never comes back in the first place, without the open-then-hide
 * flicker of doing it on startup. (If the IDE is killed instead of closed properly,
 * nothing is saved and the terminal does reappear once.)
 *
 * [projectClosingBeforeSave] is the only close callback that still runs *before* the
 * project is saved — ProjectManagerImpl.closeProject publishes it and then calls
 * SaveAndSyncHandler — which is what makes the hidden layout stick. It runs on the
 * write-intent thread, so the tool window is touched directly: deferring the hide
 * with invokeLater would land after the save and change nothing.
 *
 * Registered only from claude-commit-terminal.xml (the optional Terminal-plugin
 * descriptor), so this class — and its reference to [TerminalToolWindowFactory] —
 * is loaded only when the Terminal plugin is enabled.
 */
class CloseTerminalOnProjectCloseListener : ProjectCloseListener {

    override fun projectClosingBeforeSave(project: Project) {
        if (!ClaudeCommitSettings.getInstance().state.closeTerminalOnProjectClose) return

        val terminal = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        if (terminal != null && terminal.isVisible) {
            terminal.hide()
        }
    }
}
