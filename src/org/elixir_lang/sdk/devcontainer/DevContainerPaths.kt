package org.elixir_lang.sdk.devcontainer

import com.intellij.openapi.diagnostic.Logger
import java.io.File

object DevContainerPaths {
    private const val UNC_PREFIX = "\\\\devcontainer.ij\\"
    private val UNC_PATH_PATTERN = Regex("""([\\/]{2}devcontainer\.ij[\\/][^\s<>"|?*]+)""", RegexOption.IGNORE_CASE)
    private val LOG = Logger.getInstance(DevContainerPaths::class.java)

    fun isDevContainerUncPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) {
            return false
        }

        return path.replace('/', '\\').startsWith(UNC_PREFIX, ignoreCase = true)
    }

    fun uncRoot(path: String?): String? {
        if (path.isNullOrEmpty()) {
            return null
        }

        val normalizedPath = path.replace('/', '\\')
        if (!normalizedPath.startsWith(UNC_PREFIX, ignoreCase = true)) {
            return null
        }

        val containerRootEnd = normalizedPath.indexOf('\\', UNC_PREFIX.length)
        return if (containerRootEnd == -1) {
            normalizedPath
        } else {
            normalizedPath.substring(0, containerRootEnd)
        }
    }

    fun linuxPathToUnc(devContainerRoot: String, linuxPath: String?): String? {
        if (linuxPath.isNullOrEmpty() || !linuxPath.startsWith("/")) {
            LOG.info("Cannot convert Linux path to Dev Container UNC: root=$devContainerRoot, linuxPath=$linuxPath")
            return null
        }

        val uncPath = devContainerRoot + linuxPath.replace('/', '\\')
        LOG.info("Converted Linux path to Dev Container UNC: root=$devContainerRoot, linuxPath=$linuxPath, uncPath=$uncPath")
        return uncPath
    }

    fun uncToLinuxPath(path: String?): String? {
        val root = uncRoot(path) ?: return null
        val normalizedPath = path?.replace('/', '\\') ?: return null
        val relativePath = normalizedPath.removePrefix(root).replace('\\', '/')
        val linuxPath = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        LOG.info("Converted Dev Container UNC to Linux path: uncPath=$path, linuxPath=$linuxPath")
        return linuxPath
    }

    fun convertDevContainerPathsInString(input: String): String {
        var result = input
        val matches = UNC_PATH_PATTERN.findAll(result).toList()

        for (match in matches.asReversed()) {
            val uncPath = match.groupValues[1]
            val linuxPath = uncToLinuxPath(uncPath) ?: continue
            result = result.substring(0, match.range.first) + linuxPath + result.substring(match.range.last + 1)
        }

        return result
    }

    fun convertProcessBuilderPaths(processBuilder: ProcessBuilder): Boolean {
        var converted = false
        val commands = processBuilder.command()
        val convertedCommands = commands.map { command ->
            convertDevContainerPathsInString(command).also {
                if (it != command) {
                    converted = true
                }
            }
        }

        if (converted) {
            processBuilder.command(convertedCommands)
        }

        val environment = processBuilder.environment()
        environment.replaceAll { _, value ->
            convertDevContainerPathsInString(value).also {
                if (it != value) {
                    converted = true
                }
            }
        }

        return converted
    }

    fun roots(path: String?): List<String> {
        uncRoot(path)?.let {
            LOG.info("Using Dev Container UNC root from path: path=$path, root=$it")
            return listOf(it)
        }

        val serverRoot = File(UNC_PREFIX.removeSuffix("\\"))
        val roots = serverRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.absolutePath }
            ?: emptyList()
        LOG.info("Enumerated Dev Container UNC roots from $serverRoot: $roots")
        return roots
    }
}
