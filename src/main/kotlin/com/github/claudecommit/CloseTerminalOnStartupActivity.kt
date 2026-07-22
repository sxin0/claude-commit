package com.github.claudecommit

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Hides the Terminal tool window when a project opens, if the user enabled
 * [ClaudeCommitSettings.State.closeTerminalOnStartup].
 *
 * The IDE restores tool windows that were open in the previous session, so the
 * Terminal reappears — but its old session is gone, leaving an empty panel. When
 * the setting is on we simply hide it again after the layout has been restored.
 *
 * Registered only from claude-commit-terminal.xml (the optional Terminal-plugin
 * descriptor), so this class — and its reference to [TerminalToolWindowFactory] —
 * is loaded only when the Terminal plugin is enabled.
 */
class CloseTerminalOnStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!ClaudeCommitSettings.getInstance().state.closeTerminalOnStartup) return

        val manager = ToolWindowManager.getInstance(project)
        // invokeLater runs on the EDT after pending tool-window operations, i.e.
        // after the restored layout has been applied — so the hide sticks.
        manager.invokeLater {
            val terminal = manager.getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
            if (terminal != null && terminal.isVisible) {
                terminal.hide()
            }
        }
    }
}
