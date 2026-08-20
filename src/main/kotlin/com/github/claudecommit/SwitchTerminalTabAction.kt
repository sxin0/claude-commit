package com.github.claudecommit

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import javax.swing.SwingUtilities

/**
 * Selects the N-th tab of the Terminal tool window while the terminal has focus.
 * Bound to Cmd+1…9 on macOS and Alt+1…9 elsewhere — the keystrokes that otherwise
 * open the numbered tool windows (Cmd+1 Project, Cmd+2 Bookmarks, …).
 *
 * Those shortcuts keep their IDE meaning everywhere else: this action takes over
 * only when the focus is inside the Terminal tool window *and* the requested tab
 * exists *and* is not already the selected one. Press Cmd+5 with three tabs open
 * and [update] leaves the action disabled, so the IDE's own "Activate <X> Tool
 * Window" runs as before; press Cmd+1 while already on the first tab and the same
 * fall-through happens, so the editor's default Cmd+1 is not swallowed.
 *
 * Two platform details make this work:
 *  - The Reworked Terminal normally swallows keystrokes and forwards them to the
 *    shell, but it lets the shortcuts of a small allow-list of IDE actions through.
 *    The "Activate <X> Tool Window" family is on that list, which is what puts
 *    Cmd/Alt+N in front of the action system at all while the terminal has focus.
 *  - Our action and the tool-window one are then both enabled for the same
 *    keystroke; [SwitchTerminalTabActionPromoter] is what makes ours win.
 */
abstract class SwitchTerminalTabAction(private val tabNumber: Int) : AnAction(), DumbAware {

    // Reads the focused component and walks the tool window's Swing hierarchy.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = targetTab(e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = targetTab(e.dataContext) ?: return
        // requestFocus = true: the terminal already owns the focus, so this moves it
        // onto the tab that just became visible instead of leaving it on the old one.
        tab.manager?.setSelectedContent(tab, true)
    }

    /**
     * The tab this action would switch to, or null when it must not fire at all:
     * the feature is off, the focus is not in the terminal, there is no N-th tab,
     * or the N-th tab is already selected — in that last case we yield the keystroke
     * back to the IDE so its own "Activate <X> Tool Window" default runs (e.g. pressing
     * Cmd+1 again while already on the first terminal tab activates the Project tool
     * window instead of being swallowed). Shared with the promoter so both agree on
     * when the shortcut is ours.
     */
    internal fun targetTab(context: DataContext): Content? {
        if (!ClaudeCommitSettings.getInstance().state.switchTerminalTabWithNumberKeys) return null
        val manager = focusedTerminalContentManager(context) ?: return null
        if (tabNumber > manager.contentCount) return null
        val tab = manager.getContent(tabNumber - 1)
        // Already on this tab: let the keystroke fall through to the IDE default
        // rather than re-selecting the same tab and eating the shortcut.
        if (manager.selectedContent === tab) return null
        return tab
    }

    /** The Terminal tool window's content manager, but only while that window holds the focus. */
    private fun focusedTerminalContentManager(context: DataContext): ContentManager? {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return null
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return null
        // Also guards toolWindow.component below, which is only there once shown.
        if (!toolWindow.isVisible) return null
        // "Focus is in the terminal" is decided from the component the shortcut came
        // from rather than ToolWindowManager.activeToolWindowId — the latter still
        // names the terminal after the focus has moved back to the editor.
        val focused = PlatformCoreDataKeys.CONTEXT_COMPONENT.getData(context) ?: return null
        if (!SwingUtilities.isDescendingFrom(focused, toolWindow.component)) return null
        return toolWindow.contentManager
    }
}

// One registered action per digit: the platform instantiates a registered action
// through a no-argument constructor, so the tab number has to come from the class.
class SwitchTerminalTab1 : SwitchTerminalTabAction(1)
class SwitchTerminalTab2 : SwitchTerminalTabAction(2)
class SwitchTerminalTab3 : SwitchTerminalTabAction(3)
class SwitchTerminalTab4 : SwitchTerminalTabAction(4)
class SwitchTerminalTab5 : SwitchTerminalTabAction(5)
class SwitchTerminalTab6 : SwitchTerminalTabAction(6)
class SwitchTerminalTab7 : SwitchTerminalTabAction(7)
class SwitchTerminalTab8 : SwitchTerminalTabAction(8)
class SwitchTerminalTab9 : SwitchTerminalTabAction(9)

/**
 * Resolves the Cmd/Alt+N conflict in favour of [SwitchTerminalTabAction].
 *
 * Both it and the IDE's "Activate <X> Tool Window" are enabled for the same
 * keystroke while the terminal has focus, and the platform picks the winner from
 * the promoters rather than from registration order. Returning our action here
 * means it runs; returning nothing leaves the keystroke to the IDE.
 *
 * The applicability check is repeated here (instead of trusting the action to be
 * disabled) so that a promoted action is always one that will actually do
 * something — otherwise Cmd+5 with three tabs open could swallow the keystroke.
 */
class SwitchTerminalTabActionPromoter : ActionPromoter {

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> =
        actions.filter { it is SwitchTerminalTabAction && it.targetTab(context) != null }
}
