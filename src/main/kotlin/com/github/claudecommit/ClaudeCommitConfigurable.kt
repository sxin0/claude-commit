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
 * Settings page under Settings > Tools > Claude Code [Kit]. Labels and comments
 * come from [ClaudeCommitBundle] so the page follows the IDE display language.
 */
class ClaudeCommitConfigurable : BoundConfigurable("Claude Code [Kit]") {

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
                checkBox(ClaudeCommitBundle.message("settings.closeTerminalOnProjectClose.label"))
                    .bindSelected(state::closeTerminalOnProjectClose)
                    .comment(ClaudeCommitBundle.message("settings.closeTerminalOnProjectClose.comment"))
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.autoStartClaude.label"))
                    .bindSelected(state::autoStartClaudeWhenNoTerminal)
                    .comment(ClaudeCommitBundle.message("settings.autoStartClaude.comment"))
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.switchTerminalTab.label"))
                    .bindSelected(state::switchTerminalTabWithNumberKeys)
                    .comment(ClaudeCommitBundle.message("settings.switchTerminalTab.comment"))
            }
        }

        group(ClaudeCommitBundle.message("settings.terminalIcon.group")) {
            row {
                checkBox(ClaudeCommitBundle.message("settings.terminalIcon.enabled"))
                    .bindSelected({ state.terminalIconApiEnabled }, { enabled ->
                        TerminalIconApi.getInstance().setEnabled(enabled)
                    })
                    .comment(ClaudeCommitBundle.message("settings.terminalIcon.comment"))
            }
            row {
                button(ClaudeCommitBundle.message("settings.terminalIcon.copy")) { copyTerminalIconConnection() }
                button(ClaudeCommitBundle.message("settings.terminalIcon.reset")) { TerminalIconApi.getInstance().reset() }
            }
        }

        // Switching project windows is not a terminal feature (it works from the
        // editor too), so it gets its own group rather than joining the one above.
        group(ClaudeCommitBundle.message("settings.window.group")) {
            row {
                checkBox(ClaudeCommitBundle.message("settings.switchProjectWindow.label"))
                    .bindSelected(state::switchProjectWindowWithNumberKeys)
                    .comment(ClaudeCommitBundle.message("settings.switchProjectWindow.comment"))
            }
        }
    }

    private fun copyTerminalIconConnection() {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val snippet = try { TerminalIconApi.getInstance().connectionEnvironment() }
            catch (_: Exception) { null }
            app.invokeLater {
                if (snippet != null) {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance()
                        .setContents(java.awt.datatransfer.StringSelection(snippet))
                } else {
                    com.intellij.openapi.ui.Messages.showWarningDialog(
                        ClaudeCommitBundle.message("settings.terminalIcon.unavailable"),
                        ClaudeCommitBundle.message("settings.terminalIcon.group"),
                    )
                }
            }
        }
    }

    private fun languageLabel(language: CommitLanguage): String = when (language) {
        CommitLanguage.AUTO -> ClaudeCommitBundle.message("language.auto")
        CommitLanguage.CHINESE -> ClaudeCommitBundle.message("language.chinese")
        CommitLanguage.ENGLISH -> ClaudeCommitBundle.message("language.english")
    }
}
