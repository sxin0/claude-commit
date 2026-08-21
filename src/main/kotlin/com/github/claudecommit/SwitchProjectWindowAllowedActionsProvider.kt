package com.github.claudecommit

import com.intellij.terminal.frontend.view.TerminalAllowedActionsProvider

/**
 * Lets Option+1…9 reach the action system while the Terminal tool window has the
 * focus, so [SwitchProjectWindowAction] works from inside a Claude Code session.
 *
 * The Reworked Terminal swallows keystrokes and forwards them to the shell; only the
 * shortcuts of an allow-list of IDE actions are let through (that allow-list is what
 * puts Cmd/Alt+N in front of [SwitchTerminalTabAction], which piggybacks on the
 * "Activate <X> Tool Window" family that is already on it). Option+N is on no such
 * action, so without this provider it would be typed into the shell instead.
 *
 * The list is re-read every time the terminal takes the focus, so returning nothing
 * while the feature cannot do anything is enough to hand Option+N back to the shell
 * — no restart. That second condition matters: a keystroke on this list is offered
 * to the action system whether or not the action turns out to be enabled, and one
 * that no action handles is simply eaten. With a single project window open there is
 * nothing to switch to, so the whole range is left to the shell instead of turning
 * Option+1…9 into nine dead keys.
 *
 * Registered only from claude-commit-terminal.xml, and only useful with the Reworked
 * terminal engine: the older block terminal has the same allow-list hard-coded with
 * no extension point, so there Option+N keeps going to the shell. The extension point
 * itself is @ApiStatus.Experimental — if a future IDE renames it, this file and its
 * registration are the only things to touch.
 */
class SwitchProjectWindowAllowedActionsProvider : TerminalAllowedActionsProvider {

    override fun getActionIds(): List<String> {
        if (!ClaudeCommitSettings.getInstance().state.switchProjectWindowWithNumberKeys) return emptyList()
        if (openProjectWindows().size < 2) return emptyList()
        return ACTION_IDS
    }

    private companion object {
        // Must match the ids the actions are registered under in plugin.xml.
        val ACTION_IDS: List<String> = (1..9).map { "ClaudeCommit.SwitchProjectWindow$it" }
    }
}
