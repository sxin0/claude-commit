package com.github.claudecommit

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.io.FileUtil

/**
 * Builds Claude Code file references from an editor action context.
 *
 * Format: `@routes/web.php#L12` (selection on one line),
 * `@routes/web.php#L12-15` (multi-line selection), `@routes/web.php` (no selection).
 * The path is relative to the project root — the directory Claude Code resolves
 * `@` references against — falling back to the absolute path for files outside it.
 */
internal object CcReference {

    /** Reference for the event's editor and file, or null outside an editor context. */
    fun from(e: AnActionEvent): String? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null

        val path = e.project?.basePath
            ?.let { FileUtil.getRelativePath(it, file.path, '/') }
            ?.takeUnless { it.startsWith("..") }
            ?: file.path

        return "@$path" + lineSuffix(editor)
    }

    /** `#L12` for a single-line selection, `#L12-15` for multi-line, empty when nothing is selected. */
    private fun lineSuffix(editor: Editor): String {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return ""
        val document = editor.document
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        var endLine = document.getLineNumber(selection.selectionEnd) + 1
        // Whole-line selections (triple-click, Shift+Down) end at the start of the
        // next line; that line holds none of the selection and must not be counted.
        if (endLine > startLine && selection.selectionEnd == document.getLineStartOffset(endLine - 1)) {
            endLine--
        }
        return if (endLine > startLine) "#L$startLine-$endLine" else "#L$startLine"
    }
}
