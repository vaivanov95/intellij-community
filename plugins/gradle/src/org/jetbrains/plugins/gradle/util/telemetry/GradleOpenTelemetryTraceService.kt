// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.util.telemetry

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI

@Service(Service.Level.APP)
class GradleOpenTelemetryTraceService(private val coroutineScope: CoroutineScope) {

  private fun exportTraces(binaryTraces: ByteArray) {
    if (binaryTraces.isEmpty()) return
    val telemetryHost = getOpenTelemetryAddress() ?: return
    coroutineScope.launch {
      GradleOpenTelemetryTraceExporter.export(telemetryHost, binaryTraces)
    }
  }

  private fun getOpenTelemetryAddress(): URI? {
    val property = System.getProperty("idea.diagnostic.opentelemetry.otlp")
    if (property == null) {
      return null
    }
    if (property.endsWith("/")) {
      return URI.create(property + "v1/traces")
    }
    return URI.create("$property/v1/traces")
  }

  companion object {

    @JvmStatic
    fun exportOpenTelemetryTraces(binaryTraces: ByteArray) {
      service<GradleOpenTelemetryTraceService>().exportTraces(binaryTraces)
    }
  }
}
