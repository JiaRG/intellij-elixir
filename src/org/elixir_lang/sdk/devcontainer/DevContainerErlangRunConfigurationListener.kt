package org.elixir_lang.sdk.devcontainer

import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.ModuleBasedConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

class DevContainerErlangRunConfigurationListener : ExecutionListener {
    override fun processStartScheduled(executorId: String, environment: ExecutionEnvironment) {
        safePatchErlangApplicationConfiguration(environment)
    }

    override fun processStarting(executorId: String, environment: ExecutionEnvironment) {
        safePatchErlangApplicationConfiguration(environment)
    }

    private fun safePatchErlangApplicationConfiguration(environment: ExecutionEnvironment) {
        runCatching { patchErlangApplicationConfiguration(environment) }
            .onFailure { LOG.info("Unable to patch intellij-erlang run configuration for Dev Container", it) }
    }

    private fun patchErlangApplicationConfiguration(environment: ExecutionEnvironment) {
        val runProfile = environment.runProfile
        if (runProfile.javaClass.name != ERLANG_APPLICATION_CONFIGURATION_CLASS_NAME) {
            return
        }

        val project = environment.project
        val projectPath = project.guessProjectDir()?.toNioPathOrNull()?.toString() ?: project.basePath
        if (!DevContainerPaths.isDevContainerUncPath(projectPath)) {
            return
        }

        val module = (runProfile as? ModuleBasedConfiguration<*, *>)?.configurationModule?.module ?: return
        val useTestCodePath = invokeBoolean(runProfile, "isUseTestCodePath") ?: false
        var patched = false

        patched = patchStringProperty(
            runProfile,
            getterName = "getWorkDirectory",
            setterName = "setWorkDirectory",
            fallbackValue = null
        ) || patched

        patched = patchStringProperty(
            runProfile,
            getterName = "getEntryPointOutputPath",
            setterName = "setEntryPointOutputPath",
            fallbackValue = compilerOutputPath(module, useTestCodePath)
        ) || patched

        val explicitEntryPointPath = invokeString(runProfile, "getEntryPointFilePath")
        val entryPointPathFallback =
            if (explicitEntryPointPath.isNullOrBlank()) {
                entryPointSourcePath(runProfile, module, useTestCodePath)
            } else {
                null
            }

        patched = patchStringProperty(
            runProfile,
            getterName = "getEntryPointFilePath",
            setterName = "setEntryPointFilePath",
            fallbackValue = entryPointPathFallback
        ) || patched

        if (patched) {
            LOG.info("Patched intellij-erlang application run configuration paths for Dev Container project ${project.name}")
        }
    }

    private fun patchStringProperty(
        target: Any,
        getterName: String,
        setterName: String,
        fallbackValue: String?
    ): Boolean {
        val currentValue = invokeString(target, getterName)
        val candidate = currentValue?.takeUnless { it.isBlank() } ?: fallbackValue
        val converted = DevContainerPaths.uncToLinuxPath(candidate) ?: return false

        if (converted == currentValue) {
            return false
        }

        invokeSetter(target, setterName, converted)
        LOG.info("Converted intellij-erlang run configuration path: $getterName=$candidate -> $converted")
        return true
    }

    private fun entryPointSourcePath(target: Any, module: Module, useTestCodePath: Boolean): String? {
        val moduleAndFunction = invokeString(target, "getModuleAndFunction") ?: return null
        val erlangModuleName = moduleAndFunction.substringBefore(':').trim().trim('\'')
        if (erlangModuleName.isBlank() || erlangModuleName == moduleAndFunction) {
            return null
        }

        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, useTestCodePath)
        val candidates = FilenameIndex.getVirtualFilesByName("$erlangModuleName.erl", scope)
        return selectEntryPointSource(candidates, useTestCodePath)?.path
    }

    private fun selectEntryPointSource(candidates: Collection<VirtualFile>, useTestCodePath: Boolean): VirtualFile? =
        if (useTestCodePath) {
            candidates.firstOrNull { it.parent?.name == "test" } ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }

    private fun compilerOutputPath(module: Module, useTestCodePath: Boolean): String? {
        val compilerModuleExtension = CompilerModuleExtension.getInstance(module) ?: return null
        val outputUrl =
            if (useTestCodePath) {
                compilerModuleExtension.compilerOutputUrlForTests
            } else {
                compilerModuleExtension.compilerOutputUrl
            }

        return if (StringUtil.isNotEmpty(outputUrl)) {
            VfsUtilCore.urlToPath(outputUrl)
        } else {
            null
        }
    }

    private fun invokeString(target: Any, methodName: String): String? =
        runCatching { target.javaClass.getMethod(methodName).invoke(target) as? String }
            .getOrNull()

    private fun invokeBoolean(target: Any, methodName: String): Boolean? =
        runCatching { target.javaClass.getMethod(methodName).invoke(target) as? Boolean }
            .getOrNull()

    private fun invokeSetter(target: Any, methodName: String, value: String) {
        runCatching { target.javaClass.getMethod(methodName, String::class.java).invoke(target, value) }
            .onFailure { LOG.info("Unable to patch intellij-erlang run configuration via $methodName", it) }
    }

    companion object {
        private const val ERLANG_APPLICATION_CONFIGURATION_CLASS_NAME =
            "org.intellij.erlang.application.ErlangApplicationConfiguration"
        private val LOG = Logger.getInstance(DevContainerErlangRunConfigurationListener::class.java)
    }
}
