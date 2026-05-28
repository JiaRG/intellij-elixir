package org.elixir_lang.sdk.devcontainer

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

object DevContainerSdkAutoConfigurator {
    private val LOG = Logger.getInstance(DevContainerSdkAutoConfigurator::class.java)

    fun configureProjectSdkIfNeeded(project: Project): Boolean {
        val projectRootManager = ProjectRootManager.getInstance(project)
        val currentProjectSdk = projectRootManager.projectSdk
        if (currentProjectSdk?.sdkType is ElixirSdkType) {
            LOG.info("Dev Container SDK auto-config skipped: project already has Elixir SDK ${currentProjectSdk.name}")
            return false
        }

        val projectPath = project.guessProjectDir()?.toNioPathOrNull()?.toString() ?: project.basePath
        if (!DevContainerPaths.isDevContainerUncPath(projectPath)) {
            LOG.info("Dev Container SDK auto-config skipped: project path is not Dev Container: $projectPath")
            return false
        }

        val erlangHome = ErlangSdkType.instance.suggestHomePath(java.nio.file.Path.of(projectPath))
        val elixirHome = ElixirSdkType.instance.suggestHomePath(java.nio.file.Path.of(projectPath))
        LOG.info("Dev Container SDK auto-config candidates: project=$projectPath, erlangHome=$erlangHome, elixirHome=$elixirHome")

        if (erlangHome == null || elixirHome == null) {
            LOG.info("Dev Container SDK auto-config skipped: missing candidate SDK homes")
            return false
        }

        return runWithModalProgressBlocking(ModalTaskOwner.project(project), "Configuring Dev Container SDKs") {
            configureProjectSdk(project, erlangHome, elixirHome)
        }
    }

    private suspend fun configureProjectSdk(project: Project, erlangHome: String, elixirHome: String): Boolean {
        val erlangSdk = SdkRegistrar.registerOrUpdateErlangSdk(erlangHome)
        if (erlangSdk == null) {
            LOG.info("Dev Container SDK auto-config failed: Erlang SDK registration returned null for $erlangHome")
            return false
        }

        val elixirSdk = SdkRegistrar.registerOrUpdateElixirSdk(elixirHome, erlangSdk, project = project)
        if (elixirSdk == null) {
            LOG.info("Dev Container SDK auto-config failed: Elixir SDK registration returned null for $elixirHome")
            return false
        }

        edtWriteAction {
            ProjectRootManager.getInstance(project).projectSdk = elixirSdk
        }

        LOG.info("Dev Container SDK auto-configured project ${project.name}: Elixir SDK=${elixirSdk.name}, Erlang SDK=${erlangSdk.name}")
        return true
    }
}
