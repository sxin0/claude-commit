package com.github.claudecommit

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware

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
 * "The N-th project" is the N-th entry of the IDE's own Window menu, and the switch
 * itself is that menu entry being invoked — see [projectWindowActions]. Delegating
 * rather than re-implementing keeps the numbering identical to what the user can
 * read off the menu, and inherits the platform's activation quirks for free (it
 * knows, for instance, that a minimized window must not be restored from a keystroke
 * on macOS, and it re-focuses the component that last had the focus in the target
 * window).
 *
 * The action is disabled only when there is no N-th window. Pressing the number of
 * the window you are already in just re-focuses it: on the macOS keymap Alt+N has
 * no other meaning, so letting the keystroke fall through would only type a stray
 * character (Option+1 is "¡") into the editor.
 */
abstract class SwitchProjectWindowAction(private val projectNumber: Int) : AnAction(), DumbAware {

    // The Window menu group reads the project frames, which is EDT-only state.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = targetAction(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val action = targetAction(e) ?: return
        // A fresh event with the entry's own presentation, updated first, as clicking
        // the menu would do — not our event, whose presentation belongs to this action.
        val event = AnActionEvent.createEvent(
            action, e.dataContext, action.templatePresentation.clone(), e.place, e.uiKind, e.inputEvent
        )
        if (ActionUtil.updateAction(action, event).isPerformed && event.presentation.isEnabled) {
            ActionUtil.performAction(action, event)
        }
    }

    /**
     * The Window menu entry this action would invoke, or null when it must not fire
     * at all: the feature is off, or there is no N-th project window.
     */
    private fun targetAction(e: AnActionEvent): AnAction? {
        if (!ClaudeCommitSettings.getInstance().state.switchProjectWindowWithNumberKeys) return null
        return projectWindowActions(e).getOrNull(projectNumber - 1)
    }
}

/**
 * The per-project entries of the IDE's Window menu, in menu order (the platform sorts
 * them by project name).
 *
 * "OpenProjectWindows" is the platform's `ProjectWindowActionGroup`. Its children are
 * one toggle action per open project window plus the fixed "Next/Previous Project
 * Window" and "Merge All Windows" commands; the `ToggleAction` filter keeps only the
 * former without naming the group's `@ApiStatus.Internal` classes. Everything used
 * here is public API plus one stable action id.
 *
 * The group is asked rather than [com.intellij.openapi.project.ProjectManager] because
 * the latter lists projects in the order they *finished* opening — which is not the
 * order they were opened in after a restart (they reopen concurrently) and which
 * shifts under every number as soon as one project is closed. The numbers would then
 * move around between sessions and Option+N would land on a different project than
 * it did yesterday. The menu order is stable and the user can read it off the menu.
 */
internal fun projectWindowActions(e: AnActionEvent): List<AnAction> {
    val group = ActionManager.getInstance().getAction("OpenProjectWindows") as? ActionGroup ?: return emptyList()
    return group.getChildren(e).filterIsInstance<ToggleAction>()
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
