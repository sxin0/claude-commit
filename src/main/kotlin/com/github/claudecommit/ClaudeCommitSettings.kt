package com.github.claudecommit

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

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

        /**
         * Part 2 of the prompt: user-customizable content appended after the
         * built-in task/format prompt.
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
        val DEFAULT_USER_PROMPT = """
            请用中文回答。回答时请遵循以下原则：
            - 简洁明了，避免冗长描述
            - 只提供必要信息，省略无关细节
            - 直击要点，不重复啰嗦
        """.trimIndent()

        fun getInstance(): ClaudeCommitSettings = service()
    }
}
