package com.github.claudecommit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import java.awt.datatransfer.StringSelection

/**
 * Editor popup menu action: copies the [CcReference] of the current selection
 * to the clipboard.
 */
class CopyCcReferenceAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val reference = CcReference.from(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(reference))
    }
}
