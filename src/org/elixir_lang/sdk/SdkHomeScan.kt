package org.elixir_lang.sdk

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import org.elixir_lang.sdk.devcontainer.DevContainerPaths
import org.elixir_lang.sdk.wsl.wslCompat
import java.io.File
import java.nio.file.Path
import java.util.*


/**
 * Consolidated SDK home path scanning for Elixir and Erlang SDKs.
 *
 * Scans multiple platforms (Mac, Windows, Linux, WSL) and version managers
 * (ASDF, mise, Homebrew, kerl, Nix) to discover SDK installations.
 */
object SdkHomeScan {
    private val LOG = Logger.getInstance(SdkHomeScan::class.java)

    /**
     * Configuration for SDK-specific scanning behavior.
     *
     * @property toolName The tool name used by version managers (e.g., "elixir", "erlang")
     * @property nixPattern Regex pattern for matching Nix store packages
     * @property linuxDefaultPath System install path on Linux (e.g., "/usr/local/lib/elixir")
     * @property linuxMintPath Linux Mint install path (e.g., "/usr/lib/elixir")
     * @property windowsDefaultPath Primary Windows install path (null if no default)
     * @property windows32BitPath 32-bit Windows install path (null if same as default)
     * @property homebrewTransform Path transform for Homebrew (null = identity)
     * @property nixTransform Path transform for Nix Store (null = identity)
     * @property kerlTransform Path transform for kerl (null = skip kerl scanning)
     * @property travisCIKerlTransform Path transform for Travis CI kerl (null = skip)
     */
    data class Config(
        val toolName: String,
        val nixPattern: java.util.regex.Pattern,
        val linuxDefaultPath: String,
        val linuxMintPath: String,
        val windowsDefaultPath: String?,
        val windows32BitPath: String? = null,
        val elixirInstallScriptDirName: String,

        // Path transformations (null = identity for homebrew/nix, skip for kerl)
        val homebrewTransform: ((File) -> File)? = null,
        val nixTransform: ((File) -> File)? = null,
        val kerlTransform: ((File) -> File)? = null,
        val travisCIKerlTransform: ((File) -> File)? = null
    )

    /**
     * Scans for SDK installations across all platforms.
     *
     * @param path Project directory for WSL distribution filtering (null = scan all)
     * @param config SDK-specific configuration
     * @return Map of versions to SDK home paths, sorted by version (descending)
     */
    fun homePathByVersion(path: Path?, config: Config): Map<SdkHomeKey, String> {
        LOG.debug("Scanning for ${config.toolName} SDKs (path: $path, platform: ${OS.CURRENT})")
        val homePathByVersion: MutableMap<SdkHomeKey, String> = TreeMap()

        val result = when (OS.CURRENT) {
            OS.macOS ->
                homePathByVersionMac(homePathByVersion, config)

            OS.Windows ->
                homePathByVersionWindows(path, homePathByVersion, config)

            OS.Linux ->
                homePathByVersionLinux(homePathByVersion, config)

            else -> {
                LOG.debug("Unsupported platform: ${OS.CURRENT}")
                homePathByVersion
            }
        }

        LOG.debug("Found ${result.size} ${config.toolName} SDK(s): ${result.values.take(3).joinToString(", ")}${if (result.size > 3) "..." else ""}")
        return result
    }

    /**
     * Scans macOS for SDK installations via ASDF, Mise, kerl, Homebrew, and Nix.
     * Note: path parameter not used on macOS (no distribution filtering needed).
     */
    private fun homePathByVersionMac(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        config: Config
    ): Map<SdkHomeKey, String> {
        SdkHomePaths.mergeASDF(homePathByVersion, config.toolName)
        SdkHomePaths.mergeMise(homePathByVersion, config.toolName)

        if (config.kerlTransform != null) {
            SdkHomePaths.mergeKerl(homePathByVersion, config.kerlTransform)
        }

        val homebrewTransform = config.homebrewTransform ?: { it }
        SdkHomePaths.mergeHomebrew(homePathByVersion, config.toolName, homebrewTransform)

        val nixTransform = config.nixTransform ?: { it }
        SdkHomePaths.mergeNixStore(homePathByVersion, config.nixPattern, nixTransform)

        return homePathByVersion
    }

    /**
     * Scans Windows for SDK installations via native paths and WSL distributions.
     */
    private fun homePathByVersionWindows(
        path: Path?,
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        config: Config
    ): Map<SdkHomeKey, String> {
        val pathString = path?.toString()

        if (wslCompat.isWslUncPath(pathString)) {
            // WSL distributions
            homePathByVersionWSLs(path, homePathByVersion, config)
            return homePathByVersion
        }

        if (DevContainerPaths.isDevContainerUncPath(pathString)) {
            homePathByVersionDevContainer(pathString, homePathByVersion, config)
            return homePathByVersion
        }

        // Native Windows paths - select based on CPU architecture
        val windowsPath = if (CpuArch.CURRENT.width == 32) {
            config.windows32BitPath ?: config.windowsDefaultPath
        } else {
            config.windowsDefaultPath
        }

        windowsPath?.let {
            putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(it), it)
        }

        SdkHomePaths.mergeElixirInstallScript(homePathByVersion, config.elixirInstallScriptDirName)
        SdkHomePaths.mergeMise(homePathByVersion, config.toolName)
        homePathByVersionDevContainer(null, homePathByVersion, config)
        val openDevContainerProjectPaths = openDevContainerProjectPaths()
        LOG.info("Open Dev Container project paths for ${config.toolName} SDK scan: $openDevContainerProjectPaths")
        openDevContainerProjectPaths.forEach { devContainerProjectPath ->
            homePathByVersionDevContainer(devContainerProjectPath, homePathByVersion, config)
        }

        return homePathByVersion
    }

    /**
     * Scans a Dev Container exposed through JetBrains' Windows UNC path.
     * Mirrors the Linux system-path scanning logic, but maps Linux absolute paths to the container UNC root.
     */
    private fun homePathByVersionDevContainer(
        path: String?,
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        config: Config
    ) {
        val devContainerRoots = DevContainerPaths.roots(path)
        LOG.info("Dev Container roots for ${config.toolName} SDK scan (path: $path): $devContainerRoots")

        devContainerRoots.forEach { devContainerRoot ->
            val userHomes = devContainerUserHomes(devContainerRoot)
            LOG.info("Dev Container user homes for ${config.toolName} SDK scan ($devContainerRoot): $userHomes")

            userHomes.forEach { userHome ->
                LOG.info("Scanning Dev Container user home for ${config.toolName} SDKs: $userHome")
                SdkHomePaths.mergeASDF(homePathByVersion, config.toolName, userHome)
                SdkHomePaths.mergeMise(homePathByVersion, config.toolName, userHome)
                SdkHomePaths.mergeElixirInstallScript(homePathByVersion, config.elixirInstallScriptDirName, userHome)

                if (config.travisCIKerlTransform != null) {
                    SdkHomePaths.mergeTravisCIKerl(homePathByVersion, config.travisCIKerlTransform, userHome)
                }
            }

            DevContainerPaths.linuxPathToUnc(devContainerRoot, config.linuxDefaultPath)?.let {
                LOG.info("Checking Dev Container Linux default path for ${config.toolName}: ${config.linuxDefaultPath} -> $it")
                putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(it), it)
            }
            DevContainerPaths.linuxPathToUnc(devContainerRoot, config.linuxMintPath)?.let {
                LOG.info("Checking Dev Container Linux Mint path for ${config.toolName}: ${config.linuxMintPath} -> $it")
                putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(it), it)
            }

            DevContainerPaths.linuxPathToUnc(devContainerRoot, SdkHomePaths.NIX_STORE_PATH)?.let { nixStore ->
                LOG.info("Checking Dev Container Nix store for ${config.toolName}: ${SdkHomePaths.NIX_STORE_PATH} -> $nixStore")
                val nixTransform = config.nixTransform ?: { it }
                SdkHomePaths.mergeNixStore(homePathByVersion, config.nixPattern, nixTransform, nixStore)
            }
        }
    }

    private fun devContainerUserHomes(devContainerRoot: String): List<String> {
        val userHomes = mutableListOf<String>()

        DevContainerPaths.linuxPathToUnc(devContainerRoot, "/root")?.let { rootHome ->
            if (File(rootHome).isDirectory) {
                LOG.info("Dev Container root home exists: $rootHome")
                userHomes.add(rootHome)
            } else {
                LOG.info("Dev Container root home is not a directory: $rootHome")
            }
        }

        DevContainerPaths.linuxPathToUnc(devContainerRoot, "/home")?.let { homeDirectory ->
            val homeDirectoryFile = File(homeDirectory)
            if (!homeDirectoryFile.isDirectory) {
                LOG.info("Dev Container /home is not a directory: $homeDirectory")
            }

            homeDirectoryFile.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    LOG.info("Dev Container user home exists: ${child.absolutePath}")
                    userHomes.add(child.absolutePath)
                }
            }
        }

        return userHomes
    }

    private fun openDevContainerProjectPaths(): List<String> =
        ProjectManager.getInstance().openProjects.mapNotNull { project ->
            val guessProjectDirPath = project.guessProjectDir()?.toNioPathOrNull()?.toString()
            val basePath = project.basePath
            val projectPath = guessProjectDirPath ?: basePath
            LOG.info("Open project path candidate for Dev Container scan (${project.name}): guessProjectDir=$guessProjectDirPath, basePath=$basePath")
            projectPath
        }.filter { DevContainerPaths.isDevContainerUncPath(it) }

    /**
     * Scans Linux for SDK installations via system paths, ASDF, Mise, kerl, Travis CI kerl, and Nix.
     * Note: path parameter not used on Linux (no distribution filtering needed).
     */
    private fun homePathByVersionLinux(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        config: Config
    ): Map<SdkHomeKey, String> {
        putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(config.linuxDefaultPath), config.linuxDefaultPath)
        putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(config.linuxMintPath), config.linuxMintPath)

        SdkHomePaths.mergeASDF(homePathByVersion, config.toolName)
        SdkHomePaths.mergeMise(homePathByVersion, config.toolName)
        SdkHomePaths.mergeElixirInstallScript(homePathByVersion, config.elixirInstallScriptDirName)

        if (config.kerlTransform != null) {
            SdkHomePaths.mergeKerl(homePathByVersion, config.kerlTransform)
        }

        if (config.travisCIKerlTransform != null) {
            SdkHomePaths.mergeTravisCIKerl(homePathByVersion, config.travisCIKerlTransform)
        }

        val nixTransform = config.nixTransform ?: { it }
        SdkHomePaths.mergeNixStore(homePathByVersion, config.nixPattern, nixTransform)

        return homePathByVersion
    }

    /**
     * Determines which WSL distributions to scan based on the project path.
     * - If path is in a WSL distribution, only scan that distribution
     * - If path is Windows or null, scan all distributions
     */
    private fun homePathByVersionWSLs(
        path: Path?,
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        config: Config
    ) {
        val distributionsToScan = when {
            path == null -> {
                LOG.debug("No project path, scanning all WSL distributions")
                wslCompat.getInstalledDistributions()
            }

            wslCompat.isWslUncPath(path.toString()) -> {
                val distribution = wslCompat.getDistributionByWindowsUncPath(path.toString())
                if (distribution != null) {
                    LOG.debug("Project in WSL (${distribution.msId}), scanning only that distribution")
                    listOf(distribution)
                } else {
                    LOG.debug("Couldn't determine WSL distribution, scanning all")
                    wslCompat.getInstalledDistributions()
                }
            }

            else -> {
                LOG.debug("Windows project path, scanning all WSL distributions")
                wslCompat.getInstalledDistributions()
            }
        }

        distributionsToScan.forEach { distribution ->
            homePathByVersionWSL(distribution, homePathByVersion, config)
        }
    }

    /**
     * Scans a single WSL distribution for SDK installations.
     * Mirrors the Linux scanning logic but converts paths to Windows UNC format.
     */
    private fun homePathByVersionWSL(
        distribution: WSLDistribution,
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        config: Config
    ) {
        val wslUserHome = wslCompat.getWslUserHomeUncPath(distribution)

        if (wslUserHome != null) {
            SdkHomePaths.mergeASDF(homePathByVersion, config.toolName, wslUserHome)
            SdkHomePaths.mergeMise(homePathByVersion, config.toolName, wslUserHome)
            SdkHomePaths.mergeElixirInstallScript(homePathByVersion, config.elixirInstallScriptDirName, wslUserHome)

            if (config.travisCIKerlTransform != null) {
                SdkHomePaths.mergeTravisCIKerl(homePathByVersion, config.travisCIKerlTransform, wslUserHome)
            }
        }

        wslCompat.convertLinuxPathToWindowsUnc(distribution, config.linuxDefaultPath)?.let {
            putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(it), it)
        }
        wslCompat.convertLinuxPathToWindowsUnc(distribution, config.linuxMintPath)?.let {
            putIfDirectory(homePathByVersion, SdkHomePaths.unknownVersionKey(it), it)
        }

        wslCompat.convertLinuxPathToWindowsUnc(distribution, SdkHomePaths.NIX_STORE_PATH)?.let { wslNixStore ->
            val nixTransform = config.nixTransform ?: { it }
            SdkHomePaths.mergeNixStore(homePathByVersion, config.nixPattern, nixTransform, wslNixStore)
        }
    }

    /**
     * Adds a home path to the map only if it exists as a directory.
     */
    private fun putIfDirectory(
        homePathByVersion: MutableMap<SdkHomeKey, String>,
        key: SdkHomeKey,
        homePath: String
    ) {
        val homeFile = File(homePath)
        if (homeFile.isDirectory) {
            homePathByVersion[key] = homePath
        }
    }

}
