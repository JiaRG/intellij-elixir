package org.elixir_lang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.espec.Modules
import org.elixir_lang.mix.withPackageManagerBootstrap

object ESpec {
    fun commandLine(environment: Map<String, String>,
                    workingDirectory: String?,
                    elixirSdk: Sdk,
                    erlArgumentList: kotlin.collections.List<String> = emptyList(),
                    elixirArgumentList: kotlin.collections.List<String> = emptyList(),
                    mixArgumentList: kotlin.collections.List<String> = emptyList(),
                    project: Project? = null
    ): GeneralCommandLine {
        val commandLine = org.elixir_lang.Mix.commandLine(
                environment,
                workingDirectory,
                elixirSdk,
                Modules.erlParametersList() + erlArgumentList,
                Modules.elixirParametersList() + elixirArgumentList,
                project = project
        )
        commandLine.addParameters(withPackageManagerBootstrap(mixArgumentList + especArguments()))

        return commandLine
    }

    private fun especArguments(): kotlin.collections.List<String> = listOf("espec")
}
