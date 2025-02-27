// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.gradle.toolingExtension.impl.modelAction;

import com.intellij.gradle.toolingExtension.impl.telemetry.GradleOpenTelemetry;
import com.intellij.gradle.toolingExtension.impl.telemetry.GradleTracingContext;
import com.intellij.gradle.toolingExtension.modelAction.GradleModelFetchPhase;
import com.intellij.gradle.toolingExtension.util.GradleVersionUtil;
import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import com.intellij.util.ReflectionUtilRt;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.adapter.TargetTypeProvider;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.GradleBuild;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.*;
import com.intellij.gradle.toolingExtension.impl.model.utilTurnOffDefaultTasksModel.TurnOffDefaultTasks;
import com.intellij.gradle.toolingExtension.impl.modelSerialization.ModelConverter;
import org.jetbrains.plugins.gradle.model.ProjectImportModelProvider.GradleModelConsumer;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Vladislav.Soroka
 */
@ApiStatus.Internal
public class GradleModelFetchAction implements BuildAction<GradleModelHolderState>, Serializable {

  private final Set<ProjectImportModelProvider> myModelProviders = new LinkedHashSet<>();
  private final Set<Class<?>> myTargetTypes = new LinkedHashSet<>();

  private boolean myUseProjectsLoadedPhase = false;

  private @Nullable GradleTracingContext myTracingContext = null;

  private transient @Nullable GradleDaemonModelHolder myModels = null;

  public GradleModelFetchAction addProjectImportModelProviders(
    @NotNull Collection<? extends ProjectImportModelProvider> providers
  ) {
    myModelProviders.addAll(providers);
    return this;
  }

  public Set<Class<?>> getModelProvidersClasses() {
    Set<Class<?>> result = new LinkedHashSet<>();
    for (ProjectImportModelProvider provider : myModelProviders) {
      result.add(provider.getClass());
    }
    return result;
  }

  public void addTargetTypes(@NotNull Set<Class<?>> targetTypes) {
    myTargetTypes.addAll(targetTypes);
  }

  public void setUseProjectsLoadedPhase(boolean useProjectsLoadedPhase) {
    myUseProjectsLoadedPhase = useProjectsLoadedPhase;
  }

  public void setTracingContext(@NotNull GradleTracingContext tracingContext) {
    myTracingContext = tracingContext;
  }

  @NotNull
  protected ModelConverter getToolingModelConverter(@NotNull BuildController controller, @NotNull GradleOpenTelemetry telemetry) {
    return ModelConverter.NOP;
  }

  @Override
  public @NotNull GradleModelHolderState execute(@NotNull BuildController controller) {
    configureAdditionalTypes(controller);
    return withConverterExecutor(converterExecutor -> {
      return withOpenTelemetry(telemetry -> {
        return telemetry.callWithSpan("ProjectImportAction", __ -> {
          return doExecute(controller, converterExecutor, telemetry);
        });
      });
    });
  }

  private static <T> T withConverterExecutor(@NotNull Function<ExecutorService, T> action) {
    ExecutorService converterExecutor = Executors.newSingleThreadExecutor(new SimpleThreadFactory());
    try {
      return action.apply(converterExecutor);
    }
    finally {
      converterExecutor.shutdown();
      try {
        converterExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private GradleModelHolderState withOpenTelemetry(@NotNull Function<GradleOpenTelemetry, GradleModelHolderState> action) {
    GradleTracingContext tracingContext = myTracingContext;
    if (tracingContext == null) {
      GradleOpenTelemetry noopTelemetry = new GradleOpenTelemetry();
      return action.apply(noopTelemetry);
    }

    GradleOpenTelemetry telemetry = new GradleOpenTelemetry();
    telemetry.start(tracingContext);
    GradleModelHolderState state;
    try {
      state = action.apply(telemetry);
    }
    catch (Throwable exception) {
      telemetry.shutdown();
      throw exception;
    }
    byte[] traces = telemetry.shutdown();
    return state.withOpenTelemetryTraces(traces);
  }

  private @NotNull GradleModelHolderState doExecute(
    @NotNull BuildController controller,
    @NotNull ExecutorService converterExecutor,
    @NotNull GradleOpenTelemetry telemetry
  ) {
    boolean isProjectsLoadedAction = myModels == null && myUseProjectsLoadedPhase;

    if (isProjectsLoadedAction || !myUseProjectsLoadedPhase) {
      myModels = initAction(controller, converterExecutor, telemetry);
    }

    assert myModels != null;

    executeAction(controller, converterExecutor, telemetry, myModels, isProjectsLoadedAction);

    if (isProjectsLoadedAction) {
      telemetry.runWithSpan("TurnOffDefaultTasks", __ ->
        controller.getModel(TurnOffDefaultTasks.class)
      );
    }

    return myModels.pollPendingState();
  }

  private void configureAdditionalTypes(BuildController controller) {
    if (myTargetTypes.isEmpty()) return;
    try {
      ProtocolToModelAdapter modelAdapter =
        ReflectionUtilRt.getField(controller.getClass(), controller, ProtocolToModelAdapter.class, "adapter");
      if (modelAdapter == null) return;
      TargetTypeProvider typeProvider =
        ReflectionUtilRt.getField(ProtocolToModelAdapter.class, modelAdapter, TargetTypeProvider.class, "targetTypeProvider");
      if (typeProvider == null) return;
      //noinspection unchecked
      Map<String, Class<?>> targetTypes =
        ReflectionUtilRt.getField(typeProvider.getClass(), typeProvider, Map.class, "configuredTargetTypes");
      if (targetTypes == null) return;
      for (Class<?> targetType : myTargetTypes) {
        targetTypes.put(targetType.getCanonicalName(), targetType);
      }
    }
    catch (Exception ignore) {
    }
  }

  private @NotNull GradleDaemonModelHolder initAction(
    @NotNull BuildController controller,
    @NotNull ExecutorService converterExecutor,
    @NotNull GradleOpenTelemetry telemetry
  ) {
    GradleBuild mainGradleBuild = telemetry.callWithSpan("GetMainGradleBuild", __ ->
      controller.getBuildModel()
    );
    BuildEnvironment buildEnvironment = telemetry.callWithSpan("GetBuildEnvironment", __ ->
      controller.getModel(BuildEnvironment.class)
    );
    Collection<? extends GradleBuild> nestedGradleBuilds = telemetry.callWithSpan("GetNestedGradleBuilds", __ ->
      getNestedBuilds(buildEnvironment, mainGradleBuild)
    );
    ModelConverter modelConverter = telemetry.callWithSpan("GetToolingModelConverter", __ ->
      getToolingModelConverter(controller, telemetry)
    );
    return telemetry.callWithSpan("InitModelConsumer", __ ->
      new GradleDaemonModelHolder(converterExecutor, modelConverter, mainGradleBuild, nestedGradleBuilds, buildEnvironment)
    );
  }

  private static Collection<? extends GradleBuild> getNestedBuilds(@NotNull BuildEnvironment buildEnvironment, @NotNull GradleBuild build) {
    GradleVersion gradleVersion = GradleVersion.version(buildEnvironment.getGradle().getGradleVersion());
    Set<String> processedBuildsPaths = new HashSet<>();
    Set<GradleBuild> nestedBuilds = new LinkedHashSet<>();
    String rootBuildPath = build.getBuildIdentifier().getRootDir().getPath();
    processedBuildsPaths.add(rootBuildPath);
    Queue<GradleBuild> queue = new ArrayDeque<>(getEditableBuilds(build, gradleVersion));
    while (!queue.isEmpty()) {
      GradleBuild includedBuild = queue.remove();
      String includedBuildPath = includedBuild.getBuildIdentifier().getRootDir().getPath();
      if (processedBuildsPaths.add(includedBuildPath)) {
        nestedBuilds.add(includedBuild);
        queue.addAll(getEditableBuilds(includedBuild, gradleVersion));
      }
    }
    return nestedBuilds;
  }

  /**
   * Get nested builds to be imported by IDEA
   *
   * @param build parent build
   * @return builds to be imported by IDEA. Before Gradle 8.0 - included builds, 8.0 and later - included and buildSrc builds
   */
  private static DomainObjectSet<? extends GradleBuild> getEditableBuilds(@NotNull GradleBuild build, @NotNull GradleVersion version) {
    if (GradleVersionUtil.isGradleOlderThan(version, "8.0")) {
      return build.getIncludedBuilds();
    }
    DomainObjectSet<? extends GradleBuild> builds = build.getEditableBuilds();
    if (builds.isEmpty()) {
      return build.getIncludedBuilds();
    }
    else {
      return builds;
    }
  }

  private void executeAction(
    @NotNull BuildController controller,
    @NotNull ExecutorService converterExecutor,
    @NotNull GradleOpenTelemetry telemetry,
    @NotNull GradleDaemonModelHolder models,
    boolean isProjectsLoadedAction
  ) {
    BuildController buildController = models.createBuildController(controller);
    GradleModelConsumer modelConsumer = models.createModelConsumer(converterExecutor);
    Collection<? extends GradleBuild> gradleBuilds = models.getGradleBuilds();

    try {
      getModelProviders(isProjectsLoadedAction).forEach((phase, modelProviders) -> {
        telemetry.runWithSpan(phase.name(), __ -> {
          populateModels(buildController, telemetry, modelConsumer, gradleBuilds, modelProviders);
        });
      });
    }
    catch (Exception e) {
      throw new ExternalSystemException(e);
    }
  }

  private static void populateModels(
    @NotNull BuildController controller,
    @NotNull GradleOpenTelemetry telemetry,
    @NotNull GradleModelConsumer modelConsumer,
    @NotNull Collection<? extends GradleBuild> gradleBuilds,
    @NotNull List<ProjectImportModelProvider> modelProviders
  ) {
    telemetry.runWithSpan("PopulateModels", __ -> {
      for (GradleBuild gradleBuild : gradleBuilds) {
        for (BasicGradleProject gradleProject : gradleBuild.getProjects()) {
          for (ProjectImportModelProvider modelProvider : modelProviders) {
            telemetry.runWithSpan(modelProvider.getName(), span -> {
              span.setAttribute("project-name", gradleProject.getName());
              span.setAttribute("build-name", gradleBuild.getBuildIdentifier().getRootDir().getName());
              span.setAttribute("model-type", "ProjectModel");
              modelProvider.populateProjectModels(controller, gradleProject, modelConsumer);
            });
          }
        }
        for (ProjectImportModelProvider modelProvider : modelProviders) {
          telemetry.runWithSpan(modelProvider.getName(), span -> {
            span.setAttribute("build-name", gradleBuild.getBuildIdentifier().getRootDir().getName());
            span.setAttribute("model-type", "BuildModel");
            modelProvider.populateBuildModels(controller, gradleBuild, modelConsumer);
          });
        }
      }
      for (ProjectImportModelProvider modelProvider : modelProviders) {
        telemetry.runWithSpan(modelProvider.getName(), span -> {
          span.setAttribute("model-type", "GradleModel");
          modelProvider.populateModels(controller, gradleBuilds, modelConsumer);
        });
      }
    });
  }

  public @NotNull SortedMap<GradleModelFetchPhase, List<ProjectImportModelProvider>> getModelProviders(boolean isProjectsLoadedAction) {
    NavigableMap<GradleModelFetchPhase, List<ProjectImportModelProvider>> modelProviders = myModelProviders.stream()
      .collect(Collectors.groupingBy(ProjectImportModelProvider::getPhase, TreeMap::new, Collectors.toList()));
    if (!myUseProjectsLoadedPhase) {
      return modelProviders;
    }
    if (isProjectsLoadedAction) {
      return modelProviders.headMap(GradleModelFetchPhase.PROJECT_LOADED_PHASE, true);
    }
    return modelProviders.tailMap(GradleModelFetchPhase.PROJECT_LOADED_PHASE, false);
  }

  // Use this static class as a simple ThreadFactory to prevent a memory leak when passing an anonymous ThreadFactory object to
  // Executors.newSingleThreadExecutor. Memory leak will occur on the Gradle Daemon otherwise.
  private static final class SimpleThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(@NotNull Runnable runnable) {
      return new Thread(runnable, "idea-tooling-model-converter");
    }
  }
}
