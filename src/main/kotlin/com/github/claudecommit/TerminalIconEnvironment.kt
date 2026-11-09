package com.github.claudecommit

/** Reserved names are removed even when injection is ineligible, preventing inherited cross-IDE routing. */
internal object TerminalIconEnvironment {
    const val URL = "CC_KIT_TERMINAL_ICON_URL"
    const val TOKEN = "CC_KIT_TERMINAL_ICON_TOKEN"

    fun eligible(local: Boolean, remoteDev: Boolean, client: Boolean, onEdt: Boolean): Boolean =
        local && !remoteDev && !client && !onEdt

    fun clear(env: MutableMap<String, String>) {
        env.remove(URL)
        env.remove(TOKEN)
    }

    fun customize(env: MutableMap<String, String>, eligible: Boolean, inject: (MutableMap<String, String>) -> Unit) {
        clear(env)
        if (!eligible) return
        try {
            inject(env)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            clear(env)
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            clear(env)
            throw e
        } catch (e: InterruptedException) {
            clear(env)
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("Terminal initialization interrupted")
        } catch (_: Exception) {
            clear(env)
        } catch (_: LinkageError) {
            clear(env)
        }
    }

    fun exports(env: Map<String, String>): String = listOf(URL, TOKEN).joinToString("\n") {
        val value = requireNotNull(env[it]).replace("'", "'\\''")
        "export $it='$value'"
    }
}
