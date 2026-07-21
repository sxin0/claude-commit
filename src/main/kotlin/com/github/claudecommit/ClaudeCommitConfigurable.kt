package com.github.claudecommit

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/**
 * Settings page under Settings > Tools > Claude Code Commit.
 */
class ClaudeCommitConfigurable : BoundConfigurable("Claude Code Commit") {

    private val state get() = ClaudeCommitSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row("Claude 命令:") {
            textField()
                .bindText(state::claudePath)
                .columns(30)
                .comment("claude CLI 的命令名或绝对路径,例如 <code>claude</code> 或 <code>/usr/local/bin/claude</code>")
        }

        row("API 地址:") {
            textField()
                .bindText(state::baseUrl)
                .columns(30)
                .comment("Anthropic 兼容 API 地址。留空使用 claude CLI 自身配置;使用第三方中转时填其地址")
        }

        row("API Key:") {
            cell(JBPasswordField())
                .columns(30)
                .bindText(state::apiKey)
                .comment("留空使用 claude CLI 已登录的凭据。保存到插件配置文件 claude-commit.xml")
        }

        row("模型:") {
            textField()
                .bindText(state::model)
                .columns(30)
                .comment("例如 <code>claude-sonnet-5</code>。留空使用 claude CLI 默认模型")
        }

        row("超时(秒):") {
            intTextField(range = 10..600)
                .bindIntText(state::timeoutSeconds)
        }

        row {
            label("自定义提示词(追加在内置的提交消息约定之后):")
        }
        row {
            textArea()
                .bindText(state::userPrompt)
                .rows(8)
                .align(AlignX.FILL)
                .comment("内置提示词负责定义提交消息格式和输出约定,不可修改;这里的内容用于补充语言、风格等个性化要求")
        }
    }
}
