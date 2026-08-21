package com.github.claudecommit

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager

/**
 * Brings the N-th open project window to the front. Bound to Option+1…9 on macOS,
 * where each project lives in its own window and those keystrokes are free — the
 * macOS keymap gives the numbered tool windows Cmd+N and leaves Alt+N unbound.
 *
 * Deliberately *not* bound on the other keymaps: there Alt+1…9 is the IDE's own
 * "Activate <X> Tool Window" family, and taking it over would cost more than it
 * gives. The actions are still registered everywhere, so a Windows/Linux user can
 * pick their own shortcut in Settings > Keymap.
 *
 * "The N-th project" is the N-th entry of [ProjectManager.getOpenProjects], i.e.
 * the order the windows were opened in. Closing a project shifts the ones after it
 * down a number, exactly like closing a browser tab.
 *
 * The action stays disabled when there is no N-th project and when the N-th project
 * is the one the shortcut came from — pressing Option+1 while already in the first
 * project does nothing rather than pointlessly re-focusing the current window.
 */
abstract class SwitchProjectWindowAction(private val projectNumber: Int) : AnAction(), DumbAware {

    // Reads the project frames, which is EDT-only state.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = targetProject(e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = targetProject(e.dataContext) ?: return
        // stealFocus = true: the keystroke is an explicit request to switch, so the
        // window is raised *and* given the focus instead of only being de-iconified.
        ProjectUtil.focusProjectWindow(project, true)
    }

    /**
     * The project this action would switch to, or null when it must not fire at all:
     * the feature is off, there is no N-th project window, or the N-th one is already
     * the current project.
     */
    internal fun targetProject(context: DataContext): Project? {
        if (!ClaudeCommitSettings.getInstance().state.switchProjectWindowWithNumberKeys) return null
        val projects = openProjectWindows()
        if (projectNumber > projects.size) return null
        val target = projects[projectNumber - 1]
        // Already in this project: leave the keystroke alone.
        if (target === CommonDataKeys.PROJECT.getData(context)) return null
        return target
    }
}

/**
 * Open projects that actually have a window, in the order they were opened.
 * The frame check drops projects that are still opening or already tearing down
 * — [ProjectUtil.focusProjectWindow] has nothing to raise for those, and counting
 * them would silently shift every number.
 *
 * Shared with [SwitchProjectWindowAllowedActionsProvider] so the terminal's
 * allow-list and the actions agree on what counts as a switchable window.
 */
internal fun openProjectWindows(): List<Project> {
    val windowManager = WindowManager.getInstance()
    return ProjectManager.getInstance().openProjects
        .filter { !it.isDisposed && windowManager.getFrame(it) != null }
}

// One registered action per digit: the platform instantiates a registered action
// through a no-argument constructor, so the project number has to come from the class.
class SwitchProjectWindow1 : SwitchProjectWindowAction(1)
class SwitchProjectWindow2 : SwitchProjectWindowAction(2)
class SwitchProjectWindow3 : SwitchProjectWindowAction(3)
class SwitchProjectWindow4 : SwitchProjectWindowAction(4)
class SwitchProjectWindow5 : SwitchProjectWindowAction(5)
class SwitchProjectWindow6 : SwitchProjectWindowAction(6)
class SwitchProjectWindow7 : SwitchProjectWindowAction(7)
class SwitchProjectWindow8 : SwitchProjectWindowAction(8)
class SwitchProjectWindow9 : SwitchProjectWindowAction(9)
