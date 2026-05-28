package org.elixir_lang.jps.builder.execution;

import com.intellij.openapi.diagnostic.Logger;
import org.elixir_lang.jps.builder.Builder;
import org.elixir_lang.jps.builder.target.Type;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildTargetType;
import org.jetbrains.jps.incremental.BuilderService;
import org.jetbrains.jps.incremental.TargetBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by zyuyou on 15/7/10.
 */
public class Service extends BuilderService {
  private static final Logger LOG = Logger.getInstance(Service.class);

  static {
    System.err.println("Elixir JPS BuilderService class loaded: " + Service.class.getName());
  }

  @NotNull
  @Override
  public List<? extends BuildTargetType<?>> getTargetTypes() {
    System.err.println("Elixir JPS BuilderService registering target types");
    LOG.info("Registering Elixir JPS target types");
    return Arrays.asList(Type.PRODUCTION, Type.TEST);
  }

  @NotNull
  @Override
  public List<? extends TargetBuilder<?, ?>> createBuilders() {
    System.err.println("Elixir JPS BuilderService creating builders");
    LOG.info("Creating Elixir JPS builder");
    return Collections.singletonList(new Builder());
  }
}
