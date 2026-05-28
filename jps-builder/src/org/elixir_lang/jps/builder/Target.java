package org.elixir_lang.jps.builder;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.containers.ContainerUtil;
import org.elixir_lang.jps.builder.execution.SourceRootDescriptor;
import org.elixir_lang.jps.builder.target.Type;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.builders.*;
import org.jetbrains.jps.builders.storage.BuildDataPaths;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.indices.IgnoredFileIndex;
import org.jetbrains.jps.indices.ModuleExcludeIndex;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.java.JavaSourceRootProperties;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaClasspathKind;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsTypedModuleSourceRoot;

import java.io.File;
import java.util.*;

import static com.intellij.util.io.URLUtil.extractPath;

/**
 * Created by zyuyou on 15/7/10.
 */
public class Target extends ModuleBasedTarget<SourceRootDescriptor> {
  private static final Logger LOG = Logger.getInstance(Target.class);
  private static final List<String> PRODUCTION_MIX_SOURCE_ROOT_NAMES = Arrays.asList("c_src", "include", "lib", "src");
  private static final List<String> TEST_MIX_SOURCE_ROOT_NAMES = Arrays.asList("spec", "test");

  public Target(Type targetType, @NotNull JpsModule module) {
    super(targetType, module);
  }

  @NotNull
  @Override
  public String getId() {
    return myModule.getName();
  }

  @Override
  @NotNull
  public Collection<BuildTarget<?>> computeDependencies(@NotNull BuildTargetRegistry targetRegistry,
                                                        @NotNull TargetOutputIndex outputIndex) {
    return computeDependencies();
  }

  @NotNull
  public Collection<BuildTarget<?>> computeDependencies(){
    List<BuildTarget<?>> dependencies = new ArrayList<>();

    Set<JpsModule> modules = JpsJavaExtensionService.dependencies(myModule).includedIn(JpsJavaClasspathKind.compile(isTests())).getModules();
    LOG.info("Computing Elixir JPS dependencies for " + getPresentableName() + ", candidate modules=" + modules.size());
    for (JpsModule module : modules){
      ModuleUtil.logDiagnostic(module, "dependency-of-" + myModule.getName());
      if(ModuleUtil.isCompilable(module)){
        dependencies.add(new Target(getElixirTargetType(), module));
      }
    }

    if(isTests()){
      dependencies.add(new Target(Type.PRODUCTION, myModule));
    }

    return dependencies;
  }

  @NotNull
  @Override
  public List<SourceRootDescriptor> computeRootDescriptors(@NotNull JpsModel model,
                                                           @NotNull ModuleExcludeIndex index,
                                                           @NotNull IgnoredFileIndex ignoredFileIndex,
                                                           @NotNull BuildDataPaths dataPaths) {

    List<SourceRootDescriptor> result = new ArrayList<>();
    Set<String> rootPaths = new HashSet<>();
    JavaSourceRootType type = isTests() ? JavaSourceRootType.TEST_SOURCE : JavaSourceRootType.SOURCE;
    LOG.info("Computing Elixir JPS root descriptors for " + getPresentableName() + ", requested root type=" + type);
    ModuleUtil.logDiagnostic(myModule, "computeRootDescriptors/" + (isTests() ? "test" : "production"));
    for(JpsTypedModuleSourceRoot<JavaSourceRootProperties> root : myModule.getSourceRoots(type)){
      addRootDescriptor(result, rootPaths, root.getFile());
    }

    if (ModuleUtil.isCompilable(myModule)) {
      for (String contentRootUrl : myModule.getContentRootsList().getUrls()) {
        File contentRoot = new File(extractPath(contentRootUrl));
        for (String sourceRootName : mixSourceRootNames()) {
          File sourceRoot = new File(contentRoot, sourceRootName);
          addRootDescriptor(result, rootPaths, sourceRoot);
        }
      }

      if (result.isEmpty()) {
        for (String contentRootUrl : myModule.getContentRootsList().getUrls()) {
          addRootDescriptor(result, rootPaths, new File(extractPath(contentRootUrl)));
        }
      }
    }

    LOG.info("Computed Elixir JPS root descriptors for " + getPresentableName() + ": " + rootPaths);
    return result;
  }

  private List<String> mixSourceRootNames() {
    return isTests() ? TEST_MIX_SOURCE_ROOT_NAMES : PRODUCTION_MIX_SOURCE_ROOT_NAMES;
  }

  private void addRootDescriptor(@NotNull List<SourceRootDescriptor> result,
                                 @NotNull Set<String> rootPaths,
                                 @NotNull File root) {
    if (rootPaths.add(root.getPath())) {
      result.add(new SourceRootDescriptor(root, this));
    }
  }

  @Nullable
  @Override
  public SourceRootDescriptor findRootDescriptor(@NotNull String rootId, @NotNull BuildRootIndex rootIndex) {
    return ContainerUtil.getFirstItem(
        rootIndex.getRootDescriptors(new File(rootId), Collections.singletonList(getElixirTargetType()), null)
    );
  }

  @NotNull
  @Override
  public String getPresentableName() {
    return "Elixir '" + myModule.getName() + "' " + (isTests() ? "test" : "production");
  }

  @NotNull
  @Override
  public Collection<File> getOutputRoots(@NotNull CompileContext context) {
    return ContainerUtil.createMaybeSingletonList(JpsJavaExtensionService.getInstance().getOutputDirectory(myModule, isTests()));
  }

  @Override
  public boolean isTests() {
    return getElixirTargetType().isTests();
  }

  @NotNull
  public Type getElixirTargetType(){
    return (Type)getTargetType();
  }
}
