package com.github.claudecommit

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/**
 * Settings page under Settings > Tools > Claude Code Commit. Labels and comments
 * come from [ClaudeCommitBundle] so the page follows the IDE display language.
 */
class ClaudeCommitConfigurable : BoundConfigurable("Claude Code Commit") {

    private val state get() = ClaudeCommitSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row(ClaudeCommitBundle.message("settings.claudePath.label")) {
            textField()
                .bindText(state::claudePath)
                .columns(30)
                .comment(ClaudeCommitBundle.message("settings.claudePath.comment"))
        }

        row(ClaudeCommitBundle.message("settings.baseUrl.label")) {
            textField()
                .bindText(state::baseUrl)
                .columns(30)
                .comment(ClaudeCommitBundle.message("settings.baseUrl.comment"))
        }

        row(ClaudeCommitBundle.message("settings.apiKey.label")) {
            cell(JBPasswordField())
                .columns(30)
                .bindText(state::apiKey)
                .comment(ClaudeCommitBundle.message("settings.apiKey.comment"))
        }

        row(ClaudeCommitBundle.message("settings.model.label")) {
            textField()
                .bindText(state::model)
                .columns(30)
                .comment(ClaudeCommitBundle.message("settings.model.comment"))
        }

        row(ClaudeCommitBundle.message("settings.language.label")) {
            comboBox(
                CommitLanguage.entries,
                textListCellRenderer { languageLabel(it ?: CommitLanguage.AUTO) },
            )
                .bindItem({ state.commitLanguage }, { state.commitLanguage = it ?: CommitLanguage.AUTO })
                .comment(ClaudeCommitBundle.message("settings.language.comment"))
        }

        row(ClaudeCommitBundle.message("settings.timeout.label")) {
            intTextField(range = 10..600)
                .bindIntText(state::timeoutSeconds)
        }

        row {
            label(ClaudeCommitBundle.message("settings.userPrompt.label"))
        }
        row {
            textArea()
                .bindText(state::userPrompt)
                .rows(8)
                .align(AlignX.FILL)
                .comment(ClaudeCommitBundle.message("settings.userPrompt.comment"))
        }

        // Independent convenience option, unrelated to commit-message generation.
        // Kept in its own titled group (whose title says so) so users don't mistake
        // it for a Claude/commit setting.
        group(ClaudeCommitBundle.message("settings.terminal.group")) {
            row {
                checkBox(ClaudeCommitBundle.message("settings.closeTerminalOnStartup.label"))
                    .bindSelected(state::closeTerminalOnStartup)
                    .comment(ClaudeCommitBundle.message("settings.closeTerminalOnStartup.comment"))
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.autoStartClaude.label"))
                    .bindSelected(state::autoStartClaudeWhenNoTerminal)
                    .comment(ClaudeCommitBundle.message("settings.autoStartClaude.comment"))
            }
        }
    }

    private fun languageLabel(language: CommitLanguage): String = when (language) {
        CommitLanguage.AUTO -> ClaudeCommitBundle.message("language.auto")
        CommitLanguage.CHINESE -> ClaudeCommitBundle.message("language.chinese")
        CommitLanguage.ENGLISH -> ClaudeCommitBundle.message("language.english")
    }
}
