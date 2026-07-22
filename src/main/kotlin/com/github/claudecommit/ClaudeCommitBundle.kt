package com.github.claudecommit

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.ClaudeCommitBundle"

/**
 * Localized strings for the plugin. Backed by messages/ClaudeCommitBundle.properties
 * (English, the fallback) and ClaudeCommitBundle_zh.properties (Chinese); the IDE
 * display language selects which one is used.
 */
object ClaudeCommitBundle : DynamicBundle(BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    /**
     * True when the IDE display language is Chinese — used to resolve the AUTO
     * commit-message language to the language the user is reading the UI in.
     */
    fun isChineseUi(): Boolean = getLocale().language.equals("zh", ignoreCase = true)
}
