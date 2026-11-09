package com.github.claudecommit

import com.intellij.idea.AppMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.util.PlatformUtils
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

/** Descriptor-aware callback only: legacy callbacks cannot prove where a process will execute. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class TerminalIconEnvironmentCustomizer private constructor(
    private val eligible: (EelDescriptor) -> Boolean,
    private val inject: (MutableMap<String, String>) -> Unit,
) : LocalTerminalCustomizer() {
    constructor() : this(
        { descriptor ->
            TerminalIconEnvironment.eligible(
                descriptor === LocalEelDescriptor, AppMode.isRemoteDevHost(),
                PlatformUtils.isJetBrainsClient(), ApplicationManager.getApplication().isDispatchThread,
            )
        },
        { env -> TerminalIconApi.getInstance().injectConnectionEnvironment(env) },
    )
    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: List<String>,
        envs: MutableMap<String, String>,
        eelDescriptor: EelDescriptor,
    ): List<String> {
        // Cleanup also precedes capability checks, which can fail on older SDKs.
        TerminalIconEnvironment.clear(envs)
        try {
            TerminalIconEnvironment.customize(envs, eligible(eelDescriptor), inject)
        } catch (_: LinkageError) {
            TerminalIconEnvironment.clear(envs)
        }
        return command
    }

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        envs: MutableMap<String, String>,
    ): Array<String> {
        TerminalIconEnvironment.clear(envs)
        return command
    }

    companion object {
        internal fun forTests(
            eligible: (EelDescriptor) -> Boolean, inject: (MutableMap<String, String>) -> Unit,
        ): TerminalIconEnvironmentCustomizer = TerminalIconEnvironmentCustomizer(eligible, inject)
    }
}
