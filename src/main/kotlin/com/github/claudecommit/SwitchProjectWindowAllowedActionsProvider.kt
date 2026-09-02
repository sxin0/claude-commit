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
 * The list is read once each time the terminal takes the focus and cached until the
 * next time, so the only condition that belongs here is one that cannot change
 * underneath that cache: the feature switch. In particular the number of open
 * project windows must *not* be consulted — it changes without the terminal losing
 * the focus (a second project finishing its restore after a restart, a window closed
 * from its own title bar), and a stale answer would either type Option+N into the
 * shell or leave it dead until the user clicks out of the terminal and back in.
 * Nothing is lost by always listing the ids: when the N-th window does not exist the
 * action is disabled, and the terminal's own fallback for a listed-but-disabled
 * shortcut is to send the keystroke to the shell, as if it had never been listed.
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
        return ACTION_IDS
    }

    private companion object {
        // Must match the ids the actions are registered under in plugin.xml.
        val ACTION_IDS: List<String> = (1..9).map { "ClaudeCommit.SwitchProjectWindow$it" }
    }
}
