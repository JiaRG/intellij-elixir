package org.elixir_lang.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.cli.CliArgs
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.sdk.devcontainer.DevContainerPaths
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk
import org.elixir_lang.sdk.erlang_dependent.requireErlangSdkOrNotifyAndThrow
import org.elixir_lang.jps.shared.cli.CliArguments as SharedCliArguments

object CliArguments {
    private const val DEV_CONTAINER_FALLBACK_ELIXIR_VERSION = "1.17.0"
    private val VERSION_REGEX = Regex("""\d+\.\d+\.\d+(?:-[A-Za-z0-9]+)*""")

    fun args(
        elixirSdk: Sdk,
        tool: CliTool,
        extraElixirArguments: List<String> = emptyList(),
        extraErlangArguments: List<String> = emptyList(),
        os: OS = effectiveOS(elixirSdk),
        ansiEnabled: Boolean = true
    ): CliArgs? {
        val elixirHomePath = elixirSdk.homePath
        val erlangHomePath = elixirSdk.getErlangSdk()?.homePath
        return SharedCliArguments.args(
            DevContainerPaths.uncToLinuxPath(elixirHomePath) ?: elixirHomePath,
            effectiveElixirVersionString(elixirSdk, elixirHomePath),
            DevContainerPaths.uncToLinuxPath(erlangHomePath) ?: erlangHomePath,
            tool,
            extraElixirArguments,
            extraErlangArguments,
            os,
            ansiEnabled
        )
    }

    fun argsOrThrow(
        elixirSdk: Sdk,
        tool: CliTool,
        extraElixirArguments: List<String> = emptyList(),
        extraErlangArguments: List<String> = emptyList(),
        os: OS = effectiveOS(elixirSdk),
        project: Project? = null,
        ansiEnabled: Boolean = true,
    ): CliArgs {
        val elixirHomePath =
            elixirSdk.homePath
                ?: throw ExecutionException("Elixir SDK home path is not configured")
        val erlangSdk = elixirSdk.requireErlangSdkOrNotifyAndThrow(project = project)
        val erlangHomePath =
            erlangSdk.homePath
                ?: throw ExecutionException("Erlang SDK home path is not configured")
        val effectiveElixirHomePath = DevContainerPaths.uncToLinuxPath(elixirHomePath) ?: elixirHomePath
        val effectiveErlangHomePath = DevContainerPaths.uncToLinuxPath(erlangHomePath) ?: erlangHomePath

        return SharedCliArguments.args(
            effectiveElixirHomePath,
            effectiveElixirVersionString(elixirSdk, elixirHomePath),
            effectiveErlangHomePath,
            tool,
            extraElixirArguments,
            extraErlangArguments,
            os,
            ansiEnabled
        ) ?: throw ExecutionException("Unable to compute CLI arguments for SDK ${elixirSdk.name}")
    }

    private fun effectiveOS(elixirSdk: Sdk): OS {
        if (OS.CURRENT == OS.Windows) {
            elixirSdk.homePath?.let {
                if (WslPath.isWslUncPath(it) || DevContainerPaths.isDevContainerUncPath(it)) {
                    return OS.Linux
                }
            }
        }
        return OS.CURRENT
    }

    private fun effectiveElixirVersionString(elixirSdk: Sdk, elixirHomePath: String?): String? {
        val versionString = elixirSdk.versionString
        if (extractVersion(versionString) != null) {
            return versionString
        }

        if (!DevContainerPaths.isDevContainerUncPath(elixirHomePath)) {
            return versionString
        }

        return extractVersion(elixirSdk.name)
            ?: extractVersion(elixirHomePath)
            ?: DEV_CONTAINER_FALLBACK_ELIXIR_VERSION
    }

    private fun extractVersion(value: String?): String? =
        value?.let { VERSION_REGEX.find(it)?.value }
}
