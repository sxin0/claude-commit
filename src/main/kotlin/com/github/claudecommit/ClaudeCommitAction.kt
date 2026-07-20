package com.github.claudecommit

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import java.util.concurrent.ConcurrentHashMap

/**
 * Toolbar button in the commit panel (Vcs.MessageActionGroup): sends the list of
 * files checked for this commit to the Claude CLI (which reads the diffs itself)
 * and fills the generated message into the commit message field.
 *
 * While a generation is running the button turns into a stop button; clicking it
 * cancels the generation.
 */
class ClaudeCommitAction : AnAction(), DumbAware {

    private companion object {
        const val NOTIFICATION_GROUP_ID = "Claude Commit"

        /** Indicator of the generation currently running in each project — used to stop it. */
        val running = ConcurrentHashMap<Project, ProgressIndicator>()
    }

    // EDT: includedFilePaths() reads commit-panel inclusion state, which is UI state.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasTarget = project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        e.presentation.isVisible = hasTarget
        if (project == null || !hasTarget) {
            e.presentation.isEnabled = false
            return
        }
        if (running.containsKey(project)) {
            e.presentation.icon = AllIcons.Actions.Suspend
            e.presentation.text = "停止生成"
            e.presentation.isEnabled = true
        } else {
            e.presentation.icon = AllIcons.Actions.Lightning
            e.presentation.text = "使用 Claude 生成提交消息"
            e.presentation.isEnabled = includedFilePaths(e).isNotEmpty()
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // A generation is running — this click is the stop button.
        running[project]?.let {
            it.cancel()
            return
        }
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val files = includedFilePaths(e)

        object : Task.Backgroundable(project, "使用 Claude 生成提交消息", true) {
            override fun run(indicator: ProgressIndicator) {
                running[project] = indicator
                ActivityTracker.getInstance().inc() // repaint the toolbar → stop icon
                val result = try {
                    ClaudeCommitGenerator.generate(project, files, indicator)
                } finally {
                    running.remove(project)
                    ActivityTracker.getInstance().inc()
                }
                // ModalityState.any(): with the modal commit dialog open, the default
                // (non-modal) modality would defer this until the dialog closes.
                ApplicationManager.getApplication().invokeLater({
                    when (result) {
                        is GenerationResult.Success -> commitMessage.setCommitMessage(result.message)
                        is GenerationResult.Failure -> notifyError(project, result.reason)
                        GenerationResult.Cancelled -> Unit
                    }
                }, ModalityState.any())
            }
        }.queue()
    }

    /**
     * Paths of the changes currently checked (included) for this commit. Falls back
     * to the selected changes when no workflow UI is in the context. Unversioned
     * (never `git add`ed) files are excluded — git has no diff for them.
     */
    private fun includedFilePaths(e: AnActionEvent): List<String> {
        val changes = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)?.getIncludedChanges()
            ?: e.getData(VcsDataKeys.CHANGES)?.toList()
            ?: emptyList()
        return changes
            .mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.path }
            .distinct()
    }

    private fun notifyError(project: Project, reason: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification("生成提交消息失败", reason, NotificationType.ERROR)
            .notify(project)
    }
}
