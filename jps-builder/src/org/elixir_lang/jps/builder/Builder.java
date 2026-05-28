package org.elixir_lang.jps.builder;

import com.intellij.execution.process.BaseOSProcessHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.CommonProcessors;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.system.OS;
import org.elixir_lang.jps.builder.compiler_options.Extension;
import org.elixir_lang.jps.builder.execution.ExecutionException;
import org.elixir_lang.jps.builder.execution.GeneralCommandLine;
import org.elixir_lang.jps.builder.execution.ProcessAdapter;
import org.elixir_lang.jps.builder.execution.SourceRootDescriptor;
import org.elixir_lang.jps.builder.model.ErlangSdkNameMissing;
import org.elixir_lang.jps.builder.model.SdkProperties;
import org.elixir_lang.jps.builder.sdk_type.MissingHomePath;
import org.elixir_lang.jps.builder.target.BuilderUtil;
import org.elixir_lang.jps.builder.target.Type;
import org.elixir_lang.jps.shared.CompilerOptions;
import org.elixir_lang.jps.shared.cli.CliArgs;
import org.elixir_lang.jps.shared.cli.CliArguments;
import org.elixir_lang.jps.shared.cli.CliTool;
import org.elixir_lang.jps.shared.sdk.SdkPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.logging.ProjectBuilderLogger;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.TargetBuilder;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.resources.ResourcesBuilder;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.library.JpsLibrary;
import org.jetbrains.jps.model.library.JpsLibraryCollection;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.util.*;

import static com.intellij.util.io.URLUtil.extractPath;

/**
 * <a href="https://github.com/ignatov/intellij-erlang/blob/master/jps-plugin/src/org/intellij/erlang/jps/builder/ErlangBuilder.java">...</a>
 * <a href="https://github.com/ignatov/intellij-erlang/tree/master/jps-plugin/src/org/intellij/erlang/jps/rebar">...</a>
 */
public class Builder extends TargetBuilder<SourceRootDescriptor, Target> {
    public static final String BUILDER_NAME = "Elixir Builder";
    public static final String ELIXIR_SOURCE_EXTENSION = "ex";
    public static final String ELIXIR_SCRIPT_EXTENSION = "exs";
    private static final String ERLANG_SOURCE_EXTENSION = "erl";
    private static final String ERLANG_HEADER_EXTENSION = "hrl";
    private static final String ERLANG_LEEX_EXTENSION = "xrl";
    private static final String ERLANG_YEEC_EXTENSION = "yrl";
    private static final String ERLANG_APP_SOURCE_EXTENSION = "app.src";
    private static final String ELIXIR_TEST_SOURCE_EXTENSION = ELIXIR_SCRIPT_EXTENSION;

    private static final String ElIXIRC_NAME = "elixirc";
    private static final String MIX_NAME = "mix";
    private static final String MIX_DO_TASK = "do";
    private static final String MIX_LOCAL_HEX_TASK = "local.hex";
    private static final String MIX_LOCAL_REBAR_TASK = "local.rebar";
    private static final String MIX_FORCE_OPTION = "--force";
    private static final String MIX_IF_MISSING_OPTION = "--if-missing";
    private static final String MIX_DO_SEPARATOR = ",";
    private static final long PROCESS_CANCEL_POLL_INTERVAL_MS = 200L;
    private static final long PROCESS_TERMINATION_TIMEOUT_MS = 5000L;
    private static final FileFilter ELIXIR_SOURCE_FILTER = file -> FileUtilRt.extensionEquals(file.getName(), ELIXIR_SOURCE_EXTENSION);
    private static final FileFilter ELIXIR_TEST_SOURCE_FILTER = file -> FileUtilRt.extensionEquals(file.getName(), ELIXIR_TEST_SOURCE_EXTENSION);
    private static final FileFilter MIX_SOURCE_FILTER = file -> !file.isDirectory() && (
            FileUtilRt.extensionEquals(file.getName(), ELIXIR_SOURCE_EXTENSION) ||
                    FileUtilRt.extensionEquals(file.getName(), ELIXIR_SCRIPT_EXTENSION) ||
                    FileUtilRt.extensionEquals(file.getName(), ERLANG_SOURCE_EXTENSION) ||
                    FileUtilRt.extensionEquals(file.getName(), ERLANG_HEADER_EXTENSION) ||
                    FileUtilRt.extensionEquals(file.getName(), ERLANG_LEEX_EXTENSION) ||
                    FileUtilRt.extensionEquals(file.getName(), ERLANG_YEEC_EXTENSION) ||
                    file.getName().endsWith("." + ERLANG_APP_SOURCE_EXTENSION)
    );
    private static final String MIX_CONFIG_FILE_NAME = "mix." + ELIXIR_SCRIPT_EXTENSION;
    private final static Logger logger = Logger.getInstance(Builder.class);

    public Builder() {
        super(Arrays.asList(Type.PRODUCTION, Type.TEST));
        logger.info("Initialized Elixir JPS Builder");

        // disables java resource builder for elixir modules
        ResourcesBuilder.registerEnabler(module -> !ModuleUtil.isCompilable(module));
    }

    /**
     * Build With elixirc.
     * if "isMake": compile all files of the module and dependent module.
     * else: just for compile the target affected file.
     */
    private static void doBuildWithElixirc(Target target, CompileContext context, JpsModule module, CompilerOptions compilerOptions, Set<String> absolutePathsToCompile) throws ProjectBuildException, MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {

        // ensure compile output directory
        File outputDirectory = getBuildOutputDirectory(module, target.isTests(), context);
        runElixirc(target, context, compilerOptions, absolutePathsToCompile, outputDirectory);
    }

    private static void doBuildWithMix(Target target, CompileContext context, JpsModule module, CompilerOptions compilerOptions) throws ProjectBuildException {
        JpsSdk<SdkProperties> sdk = BuilderUtil.getSdk(context, module);
        Set<String> mixWorkingDirectories = new LinkedHashSet<>();

        info(context, "Resolving Mix working directories for " + target.getPresentableName() + ", contentRoots=" + module.getContentRootsList().getUrls());
        for (String contentRootUrl : module.getContentRootsList().getUrls()) {
            String contentRootPath = extractPath(contentRootUrl);
            File contentRootDir = new File(contentRootPath);
            File mixConfigDirectory = findMixConfigDirectory(contentRootDir);

            if (mixConfigDirectory == null) {
                info(context, "No mix.exs found for module '" + module.getName() + "' content root " + contentRootPath);
                continue;
            }

            info(context, "Selected Mix working directory for module '" + module.getName() + "': " + mixConfigDirectory.getPath());
            mixWorkingDirectories.add(mixConfigDirectory.getPath());
        }

        if (mixWorkingDirectories.isEmpty()) {
            context.processMessage(new CompilerMessage(
                    BUILDER_NAME,
                    BuildMessage.Kind.INFO,
                    "Skipping Mix compile for module '" + module.getName() + "': no mix.exs found in module content roots or their ancestors."
            ));
            return;
        }

        String task = target.isTests() ? "test" : "compile";
        for (String workingDirectory : mixWorkingDirectories) {
            context.checkCanceled();
            context.processMessage(new CompilerMessage(
                    BUILDER_NAME,
                    BuildMessage.Kind.INFO,
                    "Running mix " + task + " for module '" + module.getName() + "' in " + workingDirectory
            ));
            runMix(sdk, module, workingDirectory, task, compilerOptions, context);
        }
    }

    @Nullable
    private static File findMixConfigDirectory(@NotNull File startDirectory) {
        File directory = startDirectory;
        File nearestMixConfigDirectory = null;

        while (directory != null) {
            File mixConfigFile = new File(directory, MIX_CONFIG_FILE_NAME);

            if (mixConfigFile.exists()) {
                if (isUmbrellaMixRoot(directory, startDirectory)) {
                    return directory;
                }

                if (nearestMixConfigDirectory == null) {
                    nearestMixConfigDirectory = directory;
                }
            }

            directory = directory.getParentFile();
        }

        return nearestMixConfigDirectory;
    }

    private static boolean isUmbrellaMixRoot(@NotNull File mixConfigDirectory, @NotNull File startDirectory) {
        String mixConfigPath = normalizePath(mixConfigDirectory.getPath());
        String startPath = normalizePath(startDirectory.getPath());

        if (!startPath.startsWith(mixConfigPath + "/")) {
            return false;
        }

        String relativePath = startPath.substring(mixConfigPath.length() + 1);

        return relativePath.equals("apps") || relativePath.startsWith("apps/");
    }

    @NotNull
    private static String normalizePath(@NotNull String path) {
        return path.replace('\\', '/').replaceAll("/+$", "");
    }

    /*** doBuildWithElixirc releated private methods */

    @NotNull
    private static File getBuildOutputDirectory(@NotNull JpsModule module, boolean forTests, @NotNull CompileContext context) throws ProjectBuildException {

        JpsJavaExtensionService instance = JpsJavaExtensionService.getInstance();
        File outputDirectory = instance.getOutputDirectory(module, forTests);
        if (outputDirectory == null) {
            String errorMessage = "No output directory for module " + module.getName();
            context.processMessage(new CompilerMessage(ElIXIRC_NAME, BuildMessage.Kind.ERROR, errorMessage));
            throw new ProjectBuildException(errorMessage);
        }

        if (!outputDirectory.exists()) {
            FileUtil.createDirectory(outputDirectory);
        }
        return outputDirectory;
    }

    private static void runElixirc(Target target, CompileContext context, CompilerOptions compilerOptions, Set<String> absolutePaths, File outputDirectory) throws ProjectBuildException, MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        GeneralCommandLine commandLine = getElixircCommandLine(target, context, compilerOptions, absolutePaths, outputDirectory);

        run(commandLine, context, ElIXIRC_NAME, compilerOptions);
    }

    private static GeneralCommandLine getElixircCommandLine(Target target, CompileContext context, CompilerOptions compilerOptions, Set<String> absolutePaths, File outputDirectory) throws ProjectBuildException, MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        JpsModule module = target.getModule();
        JpsSdk<SdkProperties> sdk = BuilderUtil.getSdk(context, module);
        CliArgs args = cliArguments(CliTool.ELIXIRC, sdk, module);
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(outputDirectory);
        commandLine.setExePath(args.getExePath());
        commandLine.addParameters(args.getArguments());
        addCompileOptions(commandLine, compilerOptions);
        List<String> compileFilePaths = getCompileFilePaths(module, target, context, absolutePaths);
        commandLine.addParameters(compileFilePaths);
        return commandLine;
    }

    @NotNull
    private static List<String> getCompileFilePaths(@NotNull JpsModule module, @NotNull Target target, @NotNull CompileContext context, Set<String> absolutePaths) {
        // make
        if (context.getScope().isBuildIncrementally(target.getTargetType())) {
            return getCompileFilePathsDefault(module, target);
        }

        // force build files
        return new ArrayList<>(absolutePaths);
    }

    @NotNull
    private static List<String> getCompileFilePathsDefault(@NotNull JpsModule module, @NotNull Target target) {
        CommonProcessors.CollectProcessor<File> exFilesCollector = new CommonProcessors.CollectProcessor<>() {
            @Override
            protected boolean accept(File file) {
                return !file.isDirectory() && FileUtilRt.extensionEquals(file.getName(), ELIXIR_SOURCE_EXTENSION);
            }
        };

        List<JpsModuleSourceRoot> sourceRoots = new ArrayList<>();
        ContainerUtil.addAll(sourceRoots, module.getSourceRoots(JavaSourceRootType.SOURCE));
        if (target.isTests()) {
            ContainerUtil.addAll(sourceRoots, module.getSourceRoots(JavaSourceRootType.TEST_SOURCE));
        }

        for (JpsModuleSourceRoot root : sourceRoots) {
            FileUtil.processFilesRecursively(root.getFile(), exFilesCollector);
        }

        Collection<File> fileCollection = exFilesCollector.getResults();

        return ContainerUtil.map(fileCollection, File::getAbsolutePath);
    }

    private static void addCompileOptions(@NotNull GeneralCommandLine commandLine, CompilerOptions compilerOptions) {
        if (!compilerOptions.attachDocsEnabled) {
            commandLine.addParameter("--no-docs");
        }

        if (!compilerOptions.attachDebugInfoEnabled) {
            commandLine.addParameter("--no-debug-info");
        }

        if (compilerOptions.warningsAsErrorsEnabled) {
            commandLine.addParameter("--warnings-as-errors");
        }

        if (compilerOptions.ignoreModuleConflictEnabled) {
            commandLine.addParameter("--ignore-module-conflict");
        }
    }

    private static CliArgs cliArguments(@NotNull CliTool tool, @NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module) throws MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        String erlangHomePath = sdkToErlHomepath(sdk, module);
        String elixirHomePath = sdk.getHomePath();
        String elixirVersion = sdk.getVersionString();
        OS os = isDevContainerPath(elixirHomePath) || isDevContainerPath(erlangHomePath) ? OS.Linux : OS.CURRENT;
        String effectiveElixirHomePath = devContainerUncToLinuxPath(elixirHomePath);
        String effectiveErlangHomePath = devContainerUncToLinuxPath(erlangHomePath);

        logger.info("Elixir JPS CLI args input: module=" + module.getName() +
                ", tool=" + tool +
                ", os=" + os +
                ", elixirHomePath=" + elixirHomePath +
                ", effectiveElixirHomePath=" + effectiveElixirHomePath +
                ", erlangHomePath=" + erlangHomePath +
                ", effectiveErlangHomePath=" + effectiveErlangHomePath +
                ", elixirVersion=" + elixirVersion);

        return CliArguments.args(
                effectiveElixirHomePath,
                elixirVersion,
                effectiveErlangHomePath,
                tool,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                os,
                true
        );
    }

    private static String sdkToErlHomepath(@NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module) throws MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        SdkProperties sdkProperties = ensureSdkProperties(sdk);

        return sdkPropertiesToErlHomepath(sdkProperties, module);
    }

    @NotNull
    private static String sdkPropertiesToErlHomepath(@NotNull SdkProperties sdkProperties, @NotNull JpsModule module) throws ErlangSdkNameMissing, LibraryNotFound {
        String erlangSdkName = sdkProperties.ensureErlangSdkName();

        return erlangSdkNameToErlHomepath(erlangSdkName, module);
    }

    @NotNull
    private static String erlangSdkNameToErlHomepath(@NotNull String erlangSdkName, @NotNull JpsModule module) throws LibraryNotFound {
        JpsLibraryCollection libraryCollection = module.getProject().getModel().getGlobal().getLibraryCollection();
        JpsLibrary erlangSdkLibrary = libraryCollection.findLibrary(erlangSdkName);
        if (erlangSdkLibrary == null) {
            throw new LibraryNotFound(erlangSdkName);
        }

        JpsSdk<?> erlangSdk = (JpsSdk<?>) erlangSdkLibrary.getProperties();

        return erlangSdk.getHomePath();
    }

    @NotNull
    private static SdkProperties ensureSdkProperties(@NotNull JpsSdk<SdkProperties> sdk) throws MissingSdkProperties {
        SdkProperties sdkProperties = sdk.getSdkProperties();

        if (sdkProperties == null) {
            throw new MissingSdkProperties();
        }

        return sdkProperties;
    }


    private static GeneralCommandLine mixCommandLine(@NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module, @Nullable String workingDirectory, @NotNull String task, @NotNull CompilerOptions compilerOptions) throws FileNotFoundException, ErlangSdkNameMissing, LibraryNotFound, AccessDeniedException, MissingHomePath, MissingSdkProperties {
        CliArgs args = cliArguments(CliTool.MIX, sdk, module);
        GeneralCommandLine commandLine = new GeneralCommandLine();
        SdkPaths.maybeUpdateMixHome(commandLine.getEnvironment(), devContainerUncToLinuxPath(sdk.getHomePath()));
        commandLine.withWorkDirectory(devContainerUncToLinuxPath(workingDirectory));
        commandLine.setExePath(args.getExePath());
        commandLine.addParameters(args.getArguments());
        addMixTaskWithPackageManagerBootstrap(commandLine, task);
        addCompileOptions(commandLine, compilerOptions);
        logger.info("Elixir JPS Mix command line: module=" + module.getName() +
                ", task=" + task +
                ", workDirectory=" + commandLine.getWorkDirectory() +
                ", exePath=" + commandLine.getExePath() +
                ", parameters=" + commandLine.getParametersList().getList() +
                ", MIX_HOME=" + commandLine.getEnvironment().get("MIX_HOME") +
                ", MIX_ARCHIVES=" + commandLine.getEnvironment().get("MIX_ARCHIVES"));

        return commandLine;
    }

    private static void addMixTaskWithPackageManagerBootstrap(@NotNull GeneralCommandLine commandLine, @NotNull String task) {
        commandLine.addParameter(MIX_DO_TASK);
        commandLine.addParameters(MIX_LOCAL_HEX_TASK, MIX_FORCE_OPTION, MIX_IF_MISSING_OPTION);
        commandLine.addParameter(MIX_DO_SEPARATOR);
        commandLine.addParameters(MIX_LOCAL_REBAR_TASK, MIX_FORCE_OPTION, MIX_IF_MISSING_OPTION);
        commandLine.addParameter(MIX_DO_SEPARATOR);
        commandLine.addParameter(task);
    }

    @Nullable
    private static String devContainerUncToLinuxPath(@Nullable String path) {
        if (path == null || !isDevContainerPath(path)) {
            return path;
        }

        String normalized = path.replace('\\', '/');
        String prefix = "//devcontainer.ij/";

        if (!normalized.startsWith(prefix)) {
            return path;
        }

        int pathStart = normalized.indexOf('/', prefix.length());

        if (pathStart < 0) {
            return "/";
        }

        return normalized.substring(pathStart);
    }

    private static boolean isDevContainerPath(@Nullable String path) {
        return path != null && path.replace('\\', '/').startsWith("//devcontainer.ij/");
    }

    private static void runMix(@NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module, @Nullable String contentRootPath, @NotNull String task, @NotNull CompilerOptions compilerOptions, @NotNull CompileContext context) throws ProjectBuildException {
        GeneralCommandLine commandLine;

        try {
            commandLine = mixCommandLine(sdk, module, contentRootPath, task, compilerOptions);
        } catch (AccessDeniedException | ErlangSdkNameMissing | FileNotFoundException | LibraryNotFound |
                 MissingHomePath | MissingSdkProperties exception) {
            throw new ProjectBuildException("Couldn't construct command line for mix: " + exception.getMessage(), exception);
        }

        run(commandLine, context, MIX_NAME, compilerOptions);
    }

    private static void run(@NotNull GeneralCommandLine commandLine, @NotNull CompileContext context, @NotNull String builderName, @NotNull CompilerOptions compilerOptions) throws ProjectBuildException {
        Process process;

        try {
            info(context, "Starting " + builderName + ": workDirectory=" + commandLine.getWorkDirectory() + ", command=" + commandLine.getCommandLineString());
            process = commandLine.createProcess();
        } catch (ExecutionException executionException) {
            throw new ProjectBuildException("Failed to run " + builderName, executionException);
        }

        BaseOSProcessHandler handler = new BaseOSProcessHandler(process, commandLine.getCommandLineString(), Charset.defaultCharset());
        ProcessAdapter adapter = new ProcessAdapter(context, builderName, commandLine.getWorkDirectory().getPath(), compilerOptions);
        handler.addProcessListener(adapter);
        handler.startNotify();
        waitForProcess(handler, process, context, builderName);
        context.checkCanceled();

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ProjectBuildException(builderName + " exited with code " + exitCode);
        }
    }

    private static void waitForProcess(@NotNull BaseOSProcessHandler handler,
                                       @NotNull Process process,
                                       @NotNull CompileContext context,
                                       @NotNull String builderName) throws ProjectBuildException {
        while (!handler.waitFor(PROCESS_CANCEL_POLL_INTERVAL_MS)) {
            if (context.isCanceled()) {
                info(context, "Cancellation requested; terminating " + builderName + " process.");
                handler.destroyProcess();

                if (!handler.waitFor(PROCESS_TERMINATION_TIMEOUT_MS)) {
                    logger.warn(builderName + " process did not terminate after cancellation; forcing termination.");
                    process.destroyForcibly();
                    handler.waitFor(PROCESS_TERMINATION_TIMEOUT_MS);
                }

                context.checkCanceled();
                throw new ProjectBuildException(builderName + " was cancelled");
            }
        }
    }

    @Override
    public void build(@NotNull Target target, @NotNull DirtyFilesHolder<SourceRootDescriptor, Target> holder, @NotNull BuildOutputConsumer outputConsumer, @NotNull CompileContext context) throws ProjectBuildException, IOException {
        context.checkCanceled();
        info(context, "Build entered for " + target.getPresentableName() + "; " + ModuleUtil.diagnosticSummary(target.getModule()));

        final Set<String> absolutePathsToCompile = com.intellij.util.containers.CollectionFactory.createFilePathSet();
        final Set<String> mixDirtyPaths = com.intellij.util.containers.CollectionFactory.createFilePathSet();

        holder.processDirtyFiles((target1, file, root) -> {
            boolean isAcceptFile = target1.isTests() ? ELIXIR_TEST_SOURCE_FILTER.accept(file) : ELIXIR_SOURCE_FILTER.accept(file);
            if (isAcceptFile && ModuleUtil.isCompilable(target1.getModule())) {
                absolutePathsToCompile.add(file.getAbsolutePath());
            }
            if (MIX_SOURCE_FILTER.accept(file) && ModuleUtil.isCompilable(target1.getModule())) {
                mixDirtyPaths.add(file.getAbsolutePath());
            }
            return true;
        });
        context.checkCanceled();

        JpsModule module = target.getModule();
        JpsProject project = module.getProject();
        CompilerOptions compilerOptions = Extension.getOrCreateExtension(project).getOptions();
        info(context, "Elixir JPS dirty scan for " + target.getPresentableName() +
                ": useMixCompiler=" + compilerOptions.useMixCompiler +
                ", hasRemovedFiles=" + holder.hasRemovedFiles() +
                ", elixircDirtyCount=" + absolutePathsToCompile.size() +
                ", mixDirtyCount=" + mixDirtyPaths.size() +
                ", elixircDirtyPaths=" + absolutePathsToCompile +
                ", mixDirtyPaths=" + mixDirtyPaths);

        ProjectBuilderLogger logger = context.getLoggingManager().getProjectBuilderLogger();

        if (compilerOptions.useMixCompiler) {
            if (mixDirtyPaths.isEmpty() && !holder.hasRemovedFiles()) {
                if (target.isTests()) {
                    info(context, "Skipping Mix test for " + target.getPresentableName() + ": no dirty Mix source files and no removed files.");
                    return;
                }

                info(context, "No dirty Mix source files for " + target.getPresentableName() + "; running mix because Build Module can provide an empty dirty set for forced module builds.");
            }

            logger.logCompiledPaths(mixDirtyPaths, Builder.BUILDER_NAME, "Mix source changes:");
            doBuildWithMix(target, context, module, compilerOptions);
        } else {
            if (absolutePathsToCompile.isEmpty() && !holder.hasRemovedFiles()) {
                return;
            }

            logger.logCompiledPaths(absolutePathsToCompile, Builder.BUILDER_NAME, "Compiling files:");
            // elixirc can not compile tests now.
            if (!target.isTests()) {
                try {
                    doBuildWithElixirc(target, context, module, compilerOptions, absolutePathsToCompile);
                } catch (MissingSdkProperties | ErlangSdkNameMissing | LibraryNotFound e) {
                    throw new ProjectBuildException(e);
                }
            }
        }
    }

    private static void info(@NotNull CompileContext context, @NotNull String message) {
        logger.info(message);
        context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.INFO, message));
    }

    @NotNull
    @Override
    public String getPresentableName() {
        return BUILDER_NAME;
    }

}
