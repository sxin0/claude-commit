package com.github.claudecommit

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

/** Result of a generation attempt. */
sealed class GenerationResult {
    class Success(val message: String) : GenerationResult()
    class Failure(val reason: String) : GenerationResult()
    object Cancelled : GenerationResult()
}

/**
 * Builds the prompt (file list only — Claude reads the diffs itself via allowlisted
 * read-only git commands), invokes the `claude` CLI, and extracts the commit message
 * from its output using the <commit-message> tag contract.
 */
object ClaudeCommitGenerator {

    /** The output-format contract: the commit message must be wrapped in these tags. */
    private val COMMIT_MESSAGE_REGEX = Regex("<commit-message>\\s*([\\s\\S]*?)\\s*</commit-message>")

    /**
     * Read-only git commands Claude may run to inspect the changes. Nothing here can
     * modify the working tree, the index, or the repository.
     */
    private val ALLOWED_TOOLS = listOf(
        "git status", "git diff", "git log", "git show", "git branch",
        "git rev-parse", "git ls-files", "git blame", "git shortlog", "git describe",
    ).joinToString(",") { "Bash($it:*)" }

    /**
     * Part 1 of the prompt: the fixed task definition and output-format contract.
     * The extraction logic depends on this, so it is not user-configurable.
     */
    private val BASE_PROMPT = """
        你是一个代码提交消息生成助手。本次提交只包含下面列出的文件。
        请自行执行只读 git 命令查看这些文件的改动——例如 `git diff HEAD -- <文件>`(同时覆盖已暂存
        和未暂存的改动;新仓库没有 HEAD 时改用 `git diff --cached`),必要时可用 git log/show 等
        了解上下文——然后生成一条提交消息。

        提交消息约定:
        - 第一行是简短摘要(不超过 50 个字),概括本次改动的目的
        - 如有多处改动,空一行后用 "- " 列表逐条说明要点
        - 只描述列出文件的实际改动,不要包含其它文件的改动,也不要臆测 diff 之外的内容

        输出格式(必须严格遵守):
        把最终提交消息包裹在 <commit-message> 与 </commit-message> 标签之间,标签之外不要输出任何其他内容。
    """.trimIndent()

    fun generate(project: Project, filePaths: List<String>, indicator: ProgressIndicator): GenerationResult {
        val basePath = project.basePath ?: return GenerationResult.Failure("无法确定项目根目录")
        if (filePaths.isEmpty()) {
            return GenerationResult.Failure("没有勾选任何已加入 Git 的文件")
        }

        indicator.text = "正在调用 Claude 生成提交消息…"
        val settings = ClaudeCommitSettings.getInstance().state
        val prompt = buildPrompt(settings.userPrompt, filePaths.map { relativize(basePath, it) })

        val output = try {
            val cmd = GeneralCommandLine(settings.claudePath, "-p", prompt, "--allowedTools", ALLOWED_TOOLS)
                .withWorkDirectory(basePath)
                .withCharset(Charsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            if (settings.model.isNotBlank()) {
                cmd.addParameters("--model", settings.model.trim())
            }
            // Endpoint/key overrides go through the CLI's own `--settings` argument
            // (inline JSON). The key is passed ONLY as ANTHROPIC_AUTH_TOKEN (sent as
            // `Authorization: Bearer`, which relays check): ANTHROPIC_API_KEY must
            // never be set here — the CLI wants an interactive confirmation for an
            // unknown key, which `-p` mode can never show, so the call hangs forever.
            // Blank fields add nothing, leaving the CLI's default config untouched.
            val overrides = mutableMapOf<String, String>()
            if (settings.baseUrl.isNotBlank()) {
                overrides["ANTHROPIC_BASE_URL"] = settings.baseUrl.trim()
            }
            // Read from the keychain here (background thread), not on the EDT.
            val apiKey = ClaudeCommitSecrets.apiKey
            if (!apiKey.isNullOrBlank()) {
                overrides["ANTHROPIC_AUTH_TOKEN"] = apiKey
            }
            if (overrides.isNotEmpty()) {
                cmd.addParameters("--settings", Gson().toJson(mapOf("env" to overrides)))
            }
            val handler = CapturingProcessHandler(cmd)
            handler.processInput.close()
            handler.runProcessWithProgressIndicator(indicator, settings.timeoutSeconds * 1000, true)
        } catch (e: Exception) {
            return GenerationResult.Failure("无法启动 claude 命令(${settings.claudePath}): ${e.message}")
        }

        return when {
            output.isCancelled -> GenerationResult.Cancelled
            output.isTimeout -> GenerationResult.Failure("Claude 调用超时(${settings.timeoutSeconds} 秒),可在设置中调整")
            output.exitCode != 0 -> GenerationResult.Failure(
                "claude 退出码 ${output.exitCode}: ${output.stderr.take(300).ifBlank { output.stdout.take(300) }}"
            )
            else -> extractMessage(output.stdout)
        }
    }

    private fun buildPrompt(userPrompt: String, files: List<String>): String = buildString {
        append(BASE_PROMPT)
        if (userPrompt.isNotBlank()) {
            append("\n\n")
            append(userPrompt.trim())
        }
        append("\n\n本次提交的文件列表:\n")
        files.forEach { append("- ").append(it).append('\n') }
    }

    private fun relativize(basePath: String, path: String): String =
        FileUtil.getRelativePath(basePath, path, '/') ?: path

    /** Extract the tag-wrapped commit message from Claude's raw output. */
    private fun extractMessage(stdout: String): GenerationResult {
        val match = COMMIT_MESSAGE_REGEX.find(stdout)
            ?: return GenerationResult.Failure(
                "Claude 输出中未找到 <commit-message> 标签。原始输出开头: ${stdout.trim().take(200)}"
            )
        val message = match.groupValues[1].trim()
        return if (message.isEmpty()) {
            GenerationResult.Failure("Claude 返回了空的提交消息")
        } else {
            GenerationResult.Success(message)
        }
    }
}
