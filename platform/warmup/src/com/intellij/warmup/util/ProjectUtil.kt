// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.warmup.util

import com.intellij.conversion.ConversionListener
import com.intellij.conversion.ConversionService
import com.intellij.ide.CommandLineInspectionProjectConfigurator
import com.intellij.ide.CommandLineProgressReporterElement
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.PatchProjectUtil
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.impl.runUnderModalProgressIfIsEdt
import com.intellij.ide.warmup.WarmupConfigurator
import com.intellij.ide.warmup.WarmupStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.blockingContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.observation.Observation
import com.intellij.platform.util.progress.reportProgress
import com.intellij.util.asSafely
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.warmup.impl.WarmupConfiguratorOfCLIConfigurator
import com.intellij.warmup.impl.getCommandLineReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.*

fun importOrOpenProject(args: OpenProjectArgs): Project {
  WarmupLogger.logInfo("Opening project from ${args.projectDir}...")
  // most of the sensible operations would run in the same thread
  return runUnderModalProgressIfIsEdt {
    runTaskAndLogTime("open project") {
      importOrOpenProjectImpl0(args)
    }
  }
}

suspend fun importOrOpenProjectAsync(args: OpenProjectArgs): Project {
  WarmupLogger.logInfo("Opening project from ${args.projectDir}...")
  // most of the sensible operations would run in the same thread
  return runTaskAndLogTime("open project") {
    importOrOpenProjectImpl0(args)
  }
}

private suspend fun importOrOpenProjectImpl0(args: OpenProjectArgs): Project {
  val currentStatus = WarmupStatus.currentStatus(ApplicationManager.getApplication())
  WarmupStatus.statusChanged(ApplicationManager.getApplication(), WarmupStatus.InProgress)
  try {
    return importOrOpenProjectImpl(args)
  } finally {
    WarmupStatus.statusChanged(ApplicationManager.getApplication(), currentStatus)
  }
}

val abortFlow : MutableStateFlow<String?> = MutableStateFlow(null)

fun CoroutineScope.getFailureDeferred() : Deferred<String> {
  return async<String> {
    while (coroutineContext.job.isActive) {
      val message = abortFlow.value
      if (message != null) {
        return@async message
      }
      delay(500)
    }
    error("unreachable")
  }
}

fun CoroutineScope.getConfigurationDeferred(project : Project) : Deferred<Unit> {
  return async(start = CoroutineStart.UNDISPATCHED) {
    withLoggingProgressReporter {
      Observation.awaitConfiguration(project, WarmupLogger::logInfo)
    }
  }
}

fun CoroutineScope.awaitProjectConfigurationOrFail(project : Project) : Deferred<String?> {
  val abortDeferred = getFailureDeferred()
  val deferredConfiguration = getConfigurationDeferred(project)

  return async {
    select<String?> {
      deferredConfiguration.onAwait {
        abortDeferred.cancel()
        null
      }
      abortDeferred.onAwait { it ->
        deferredConfiguration.cancel()
        it
      }
    }
  }
}

private suspend fun importOrOpenProjectImpl(args: OpenProjectArgs): Project {
  val vfsProject = blockingContext {
    VirtualFileManager.getInstance().refreshAndFindFileByNioPath(args.projectDir)
    ?: throw RuntimeException("Project path ${args.projectDir} is not found")
  }

  runTaskAndLogTime("refresh VFS") {
    WarmupLogger.logInfo("Refreshing VFS ${args.projectDir}...")
    blockingContext {
      VfsUtil.markDirtyAndRefresh(false, true, true, args.projectDir.toFile())
    }
  }
  yieldThroughInvokeLater()

  callProjectConversion(args)

  if (!isPredicateBasedWarmup()) {
    callProjectConfigurators(args) {
      this.prepareEnvironment(args.projectDir)
    }
  }

  val project = runTaskAndLogTime("open project") {
    ProjectUtil.openOrImportAsync(vfsProject.toNioPath(), OpenProjectTask())
  } ?: throw RuntimeException("Failed to open project, null is returned")
  yieldThroughInvokeLater()

  runTaskAndLogTime("patching project") {
    withContext(Dispatchers.EDT) {
      //TODO[jo]: allow to configure that from commandline parameters
      PatchProjectUtil.patchProject(project)
    }
  }

  if (isPredicateBasedWarmup()) {
    val configurationError = runTaskAndLogTime("awaiting completion predicates") {
      val configurationError = awaitProjectConfigurationOrFail(project).await()
      dumpThreadsAfterConfiguration()
      configurationError
    }
    if (configurationError != null) {
      WarmupLogger.logError("Project configuration has failed: $configurationError")
      throw RuntimeException(configurationError)
    }
  }


  if (!isPredicateBasedWarmup()) {
    callProjectConfigurators(args) {
      this.runWarmup(project)

      FileBasedIndex.getInstance().asSafely<FileBasedIndexImpl>()?.changedFilesCollector?.ensureUpToDate()
      //the configuration may add more dumb tasks to complete
      //we flush the queue to avoid a deadlock between a modal progress & invokeLater
      yieldAndWaitForDumbModeEnd(project)
    }
  }

  runTaskAndLogTime("check project roots") {
    val errors = TreeSet<String>()
    val missingSDKs = TreeSet<String>()
    readAction {
      ProjectRootManager.getInstance(project).contentRoots.forEach { file ->
        if (!file.exists()) {
          errors += "Missing root: $file"
        }
      }

      ProjectRootManager.getInstance(project).orderEntries().forEach { root ->
        OrderRootType.getAllTypes().flatMap { root.getFiles(it).toList() }.forEach { file ->
          if (!file.exists()) {
            errors += "Missing root: $file for ${root.ownerModule.name} for ${root.presentableName}"
          }
        }

        if (root is JdkOrderEntry && root.jdk == null) {
          root.jdkName?.let { missingSDKs += it }
        }

        true
      }
    }

    errors += missingSDKs.map { "Missing JDK entry: ${it}" }
    errors.forEach { WarmupLogger.logInfo(it) }
  }

  WarmupLogger.logInfo("Project is ready for the import")
  return project
}

private val listener = object : ConversionListener {

  override fun error(message: String) {
    WarmupLogger.logInfo("PROGRESS: $message")
  }

  override fun conversionNeeded() {
    WarmupLogger.logInfo("PROGRESS: Project conversion is needed")
  }

  override fun successfullyConverted(backupDir: Path) {
    WarmupLogger.logInfo("PROGRESS: Project was successfully converted")
  }

  override fun cannotWriteToFiles(readonlyFiles: List<Path>) {
    WarmupLogger.logInfo("PROGRESS: Project conversion failed for:\n" + readonlyFiles.joinToString("\n"))
  }
}


private suspend fun callProjectConversion(projectArgs: OpenProjectArgs) {
  if (!projectArgs.convertProject) {
    return
  }

  val conversionService = ConversionService.getInstance() ?: return
  runTaskAndLogTime("convert project") {
    WarmupLogger.logInfo("Checking if conversions are needed for the project")
    val conversionResult = withContext(Dispatchers.EDT) {
      conversionService.convertSilently(projectArgs.projectDir, listener)
    }

    if (conversionResult.openingIsCanceled()) {
      throw RuntimeException("Failed to run project conversions before open")
    }

    if (conversionResult.conversionNotNeeded()) {
      WarmupLogger.logInfo("No conversions were needed")
    }
  }

  yieldThroughInvokeLater()
}

private suspend fun callProjectConfigurators(
  projectArgs: OpenProjectArgs,
  action: suspend WarmupConfigurator.() -> Unit
) {

  if (!projectArgs.configureProject) return

  val activeConfigurators = getAllConfigurators().filter {
    if (it.configuratorPresentableName in projectArgs.disabledConfigurators) {
      WarmupLogger.logInfo("Configurator ${it.configuratorPresentableName} is disabled in the settings")
      false
    } else {
      true
    }
  }

  withLoggingProgressReporter {
    reportProgress(activeConfigurators.size) { reporter ->
      for (configuration in activeConfigurators) {
        reporter.itemStep("Configurator ${configuration.configuratorPresentableName} is in action..." /* NON-NLS */) {
          runTaskAndLogTime("Configure " + configuration.configuratorPresentableName) {
            try {
              withContext(CommandLineProgressReporterElement(getCommandLineReporter(configuration.configuratorPresentableName))) {
                action(configuration)
              }
            } catch (e : CancellationException) {
              val message = (e.message ?: e.stackTraceToString()).lines().joinToString("\n") { "[${configuration.configuratorPresentableName}]: $it" }
              WarmupLogger.logInfo("Configurator '${configuration.configuratorPresentableName}' was cancelled with the following outcome:\n$message")
            }
          }
        }
      }
    }
  }

  yieldThroughInvokeLater()
}

private fun getAllConfigurators() : List<WarmupConfigurator> {
  val warmupConfigurators = WarmupConfigurator.EP_NAME.extensionList
  val nameSet = warmupConfigurators.mapTo(HashSet()) { it.configuratorPresentableName }
  return warmupConfigurators +
         CommandLineInspectionProjectConfigurator.EP_NAME.extensionList
           .filter {
             // Avoid qodana-specific configurators, we have our analogues for warmup
             it.name.startsWith("qodana").not() &&
             // New API should be preferable
             nameSet.contains(it.name).not() }
           .map(::WarmupConfiguratorOfCLIConfigurator)
}


private fun isPredicateBasedWarmup() = Registry.`is`("ide.warmup.use.predicates")