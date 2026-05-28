package org.elixir_lang.jps.builder;

import com.intellij.openapi.diagnostic.Logger;
import org.elixir_lang.jps.builder.model.ModuleType;
import org.elixir_lang.jps.builder.sdk_type.Elixir;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.util.io.URLUtil.extractPath;

public final class ModuleUtil {
    private static final Logger LOG = Logger.getInstance(ModuleUtil.class);
    private static final String MIX_CONFIG_FILE_NAME = "mix.exs";
    private static final Set<String> SOURCE_ROOT_NAMES = new HashSet<>(Arrays.asList(
            "c_src",
            "include",
            "lib",
            "spec",
            "src",
            "test"
    ));

    private ModuleUtil() {
    }

    public static boolean isCompilable(@NotNull JpsModule module) {
        if (module.getModuleType().equals(ModuleType.INSTANCE)) {
            return true;
        }

        if (module.getSdk(Elixir.INSTANCE) == null) {
            return false;
        }

        return hasMixConfigInContentRoots(module) || hasElixirLikeSourceRoots(module);
    }

    @NotNull
    public static String diagnosticSummary(@NotNull JpsModule module) {
        JpsSdk<?> elixirSdk = module.getSdk(Elixir.INSTANCE);
        boolean isElixirModuleType = module.getModuleType().equals(ModuleType.INSTANCE);
        boolean hasElixirSdk = elixirSdk != null;
        boolean hasMixConfig = hasMixConfigInContentRoots(module);
        boolean hasElixirLikeRoots = hasElixirLikeSourceRoots(module);
        boolean compilable = isCompilable(module);

        return "module=" + module.getName() +
                ", moduleType=" + module.getModuleType().getClass().getName() +
                ", isElixirModuleType=" + isElixirModuleType +
                ", hasElixirSdk=" + hasElixirSdk +
                ", elixirSdk=" + sdkSummary(elixirSdk) +
                ", hasMixConfigInContentRootsOrAncestors=" + hasMixConfig +
                ", hasElixirLikeSourceRoots=" + hasElixirLikeRoots +
                ", compilable=" + compilable +
                ", contentRoots=" + module.getContentRootsList().getUrls() +
                ", sourceRoots=" + sourceRootSummaries(module);
    }

    public static void logDiagnostic(@NotNull JpsModule module, @NotNull String phase) {
        LOG.info("Elixir JPS module diagnostic [" + phase + "]: " + diagnosticSummary(module));
    }

    @NotNull
    private static String sdkSummary(JpsSdk<?> sdk) {
        if (sdk == null) {
            return "<none>";
        }

        return "{name=" + sdk.getParent().getName() +
                ", type=" + sdk.getSdkType() +
                ", home=" + sdk.getHomePath() +
                ", version=" + sdk.getVersionString() +
                "}";
    }

    @NotNull
    private static List<String> sourceRootSummaries(@NotNull JpsModule module) {
        List<String> summaries = new ArrayList<>();

        addSourceRootSummaries(summaries, module, JavaSourceRootType.SOURCE, "SOURCE");
        addSourceRootSummaries(summaries, module, JavaSourceRootType.TEST_SOURCE, "TEST_SOURCE");

        return summaries;
    }

    private static void addSourceRootSummaries(
            @NotNull List<String> summaries,
            @NotNull JpsModule module,
            @NotNull JavaSourceRootType rootType,
            @NotNull String label
    ) {
        for (JpsModuleSourceRoot sourceRoot : module.getSourceRoots(rootType)) {
            File file = sourceRoot.getFile();
            summaries.add(label + ":" + (file != null ? file.getPath() : "<null>"));
        }
    }

    private static boolean hasMixConfigInContentRoots(@NotNull JpsModule module) {
        for (String contentRootUrl : module.getContentRootsList().getUrls()) {
            File directory = new File(extractPath(contentRootUrl));

            while (directory != null) {
                if (new File(directory, MIX_CONFIG_FILE_NAME).exists()) {
                    return true;
                }

                directory = directory.getParentFile();
            }
        }

        return false;
    }

    private static boolean hasElixirLikeSourceRoots(@NotNull JpsModule module) {
        return hasElixirLikeSourceRoots(module, JavaSourceRootType.SOURCE) ||
                hasElixirLikeSourceRoots(module, JavaSourceRootType.TEST_SOURCE);
    }

    private static boolean hasElixirLikeSourceRoots(@NotNull JpsModule module, @NotNull JavaSourceRootType rootType) {
        for (JpsModuleSourceRoot sourceRoot : module.getSourceRoots(rootType)) {
            File file = sourceRoot.getFile();

            if (file != null && SOURCE_ROOT_NAMES.contains(file.getName())) {
                return true;
            }
        }

        return false;
    }
}
