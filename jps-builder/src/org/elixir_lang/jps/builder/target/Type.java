package org.elixir_lang.jps.builder.target;

import com.intellij.openapi.diagnostic.Logger;
import org.elixir_lang.jps.builder.Target;
import org.elixir_lang.jps.builder.ModuleUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.builders.BuildTargetLoader;
import org.jetbrains.jps.builders.ModuleBasedBuildTargetType;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.module.JpsModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by zyuyou on 15/7/10.
 */
public class Type extends ModuleBasedBuildTargetType<Target>{
  private static final Logger LOG = Logger.getInstance(Type.class);
  public static final Type PRODUCTION = new Type("elixir-production", false);
  public static final Type TEST = new Type("elixir-test", true);

  private final boolean myTests;

  private Type(String elixir, boolean tests){
    super(elixir);
    myTests = tests;
  }

  @NotNull
  @Override
  public List<Target> computeAllTargets(@NotNull JpsModel model) {
    List<Target> targets = new ArrayList<>();
    LOG.info("Computing Elixir JPS " + (myTests ? "test" : "production") + " targets for " + model.getProject().getModules().size() + " module(s)");
    for (JpsModule module : model.getProject().getModules()){
      ModuleUtil.logDiagnostic(module, "computeAllTargets/" + (myTests ? "test" : "production"));
      if (ModuleUtil.isCompilable(module)) {
        targets.add(new Target(this, module));
      }
    }
    LOG.info("Computed Elixir JPS " + (myTests ? "test" : "production") + " target(s): " + targets.size());
    return targets;
  }

  @NotNull
  @Override
  public BuildTargetLoader<Target> createLoader(@NotNull final JpsModel model) {
    return new BuildTargetLoader<>() {
      @Nullable
      @Override
      public Target createTarget(@NotNull String targetId) {
        LOG.info("Loading Elixir JPS target id=" + targetId + ", tests=" + myTests);
        for (JpsModule module : model.getProject().getModules()){
          if(module.getName().equals(targetId)){
            ModuleUtil.logDiagnostic(module, "createTarget/" + (myTests ? "test" : "production"));
          }
          if(module.getName().equals(targetId) && ModuleUtil.isCompilable(module)){
            LOG.info("Loaded Elixir JPS target id=" + targetId + ", tests=" + myTests);
            return new Target(Type.this, module);
          }
        }
        LOG.info("No Elixir JPS target loaded for id=" + targetId + ", tests=" + myTests);
        return null;
      }
    };
  }

  public boolean isTests(){
    return myTests;
  }
}
