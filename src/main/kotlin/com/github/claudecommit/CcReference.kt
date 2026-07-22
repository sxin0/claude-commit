package com.github.claudecommit

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Builds Claude Code file references from an action context — either an editor
 * or the project view (file tree).
 *
 * Format: `@routes/web.php#L12` (selection on one line),
 * `@routes/web.php#L12-15` (multi-line selection), `@routes/web.php` (no selection).
 * In the project view each selected file becomes a bare `@path`, joined by spaces.
 * The path is relative to the project root — the directory Claude Code resolves
 * `@` references against — falling back to the absolute path for files outside it.
 */
internal object CcReference {

    /**
     * Reference(s) for the event context, or null when no file is available.
     * An editor contributes the selection's line suffix; without an editor
     * (project view) every selected file becomes a bare `@path`.
     */
    /**
     * Whether [from] would yield a reference here — a cheap presence check safe to
     * call from [com.intellij.openapi.actionSystem.ActionUpdateThread.BGT], as it
     * never reads the editor's selection or document.
     */
    fun isAvailable(e: AnActionEvent): Boolean =
        if (e.getData(CommonDataKeys.EDITOR) != null)
            e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        else
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.isNotEmpty() == true ||
                e.getData(CommonDataKeys.VIRTUAL_FILE) != null

    fun from(e: AnActionEvent): String? {
        e.getData(CommonDataKeys.EDITOR)?.let { editor ->
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
            return "@" + relativePath(e, file) + lineSuffix(editor)
        }

        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
            ?: return null
        return files.joinToString(" ") { "@" + relativePath(e, it) }
    }

    /** [file]'s path relative to the project root, or its absolute path when outside it. */
    private fun relativePath(e: AnActionEvent, file: VirtualFile): String =
        e.project?.basePath
            ?.let { FileUtil.getRelativePath(it, file.path, '/') }
            ?.takeUnless { it.startsWith("..") }
            ?: file.path

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
