package com.github.claudecommit

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/** Language of the generated commit message. AUTO follows the IDE display language. */
enum class CommitLanguage { AUTO, CHINESE, ENGLISH }

@Service(Service.Level.APP)
@State(name = "ClaudeCommitSettings", storages = [Storage("claude-commit.xml")])
class ClaudeCommitSettings : PersistentStateComponent<ClaudeCommitSettings.State> {

    class State {
        /** Command name or absolute path of the Claude CLI. */
        var claudePath: String = "claude"

        /**
         * Anthropic-compatible API base URL, passed to the CLI as ANTHROPIC_BASE_URL.
         * Empty = use the CLI's own default (official login).
         */
        var baseUrl: String = ""

        /** API key saved in the plugin settings XML. Empty = use the CLI's own credentials. */
        var apiKey: String = ""

        /** Model name, passed to the CLI as `--model`. Empty = CLI default. */
        var model: String = ""

        /** Language the generated commit message is written in. AUTO = follow the IDE language. */
        var commitLanguage: CommitLanguage = CommitLanguage.AUTO

        /**
         * Part 2 of the prompt: user-customizable content appended after the
         * built-in task/format prompt. The output language is controlled separately
         * by [commitLanguage], not here.
         */
        var userPrompt: String = DEFAULT_USER_PROMPT

        /** Timeout for one Claude invocation, in seconds. */
        var timeoutSeconds: Int = 120
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    companion object {
        // Language is set separately via CommitLanguage, so this default covers style only.
        val DEFAULT_USER_PROMPT = """
            生成提交消息时请遵循以下风格：
            - 简洁明了，避免冗长描述
            - 只提供必要信息，省略无关细节
            - 直击要点，不重复啰嗦
        """.trimIndent()

        fun getInstance(): ClaudeCommitSettings = service()
    }
}
