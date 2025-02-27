// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl.upgradeToUltimate.installation.windows

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.SuggestedIde
import com.intellij.openapi.updateSettings.impl.upgradeToUltimate.installation.install.*
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.util.SystemProperties
import com.intellij.util.applyIf
import com.intellij.util.io.delete
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

internal class WindowsInstaller(scope: CoroutineScope, project: Project) : UltimateInstaller(scope, project) {
  override val postfix = ".exe"

  override fun installUltimate(downloadResult: DownloadResult): InstallationResult? {
    val downloadPath = downloadResult.downloadPath
    val installationPath = provideInstallationPath(downloadResult) ?: return null

    val suggestedIde = downloadResult.suggestedIde
    val pathInfo = providePath(installationPath, suggestedIde) ?: return null
    if (pathInfo.alreadyInstalled) {
      return InstallationResult(pathInfo.path, suggestedIde)
    }
    
    return installSilently(downloadPath, installationPath, downloadResult)
  }
  
  private fun providePath(installationPath: Path, suggestedIde: SuggestedIde) : PathInfo? {
    var path = installationPath
    var counter = 0
    
    while (installationPath.exists() && counter++ <= 10) {
      if (findExePath(path, suggestedIde) != null) return PathInfo(installationPath, true)
      
      val fileName = path.fileName
      path = installationPath.parent.resolve("$fileName $counter")
    }
    
    return if (counter > 10) null else PathInfo(path, false)
  }
  
  private fun findExePath(path: Path, suggestedIde: SuggestedIde) : String?  {
    val exeName = if (suggestedIde.isPycharmProfessional()) "pycharm64" else "idea64"
    val exePath = path.resolve("bin").resolve("$exeName.exe")
    
    return if (exePath.exists()) exePath.pathString else null
  }

  private fun installSilently(
    path: Path,
    installationPath: Path,
    downloadResult: DownloadResult
  ): InstallationResult? {
    val command = GeneralCommandLine("cmd.exe", "/c").withParameters("$path /S /D=$installationPath")

    val result = runCommand(command)
    if (!result) {
      downloadResult.downloadPath.delete(true)
      return null
    }

    return InstallationResult(installationPath, downloadResult.suggestedIde)
  }

  private fun provideInstallationPath(downloadResult: DownloadResult): Path? {
    val version = downloadResult.buildVersion
    val name = "${downloadResult.suggestedIde.name} $version"
    val path = getUltimateInstallationDirectory()

    return "$path${File.separator}$name".toNioPathOrNull()
  }

  override fun startUltimate(installationResult: InstallationResult): Boolean {
    val appPath = installationResult.appPath
    val exePath = findExePath(appPath, installationResult.suggestedIde) ?: return false

    val basePath = project.basePath
    val parameters = mutableListOf("", exePath).applyIf(basePath != null) {
      add(basePath!!)
      this
    }
    parameters.add(trialParameter)

    val command = GeneralCommandLine("cmd", "/c", "start").withParameters(parameters)

    return runCommand(command)
  }
  
  override fun getUltimateInstallationDirectory(): Path? {
    return try {
      Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_UserProgramFiles)?.toNioPathOrNull()
    }
    catch (e: Exception) {
      val localAppData = Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_LocalAppData).toNioPathOrNull()
                         ?: SystemProperties.getUserHome().toNioPathOrNull()?.resolve("AppData/Local")
      localAppData?.resolve("Programs")
    }
  }
}

private data class PathInfo(
  val path: Path,
  val alreadyInstalled: Boolean,
)