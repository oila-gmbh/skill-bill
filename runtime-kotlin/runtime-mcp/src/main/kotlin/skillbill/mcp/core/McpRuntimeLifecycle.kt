package skillbill.mcp.core

import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.mcp.lifecycle.featureVerifyFinished
import skillbill.mcp.lifecycle.featureVerifyStarted
import skillbill.mcp.lifecycle.prDescriptionGenerated
import skillbill.mcp.lifecycle.qualityCheckFinished
import skillbill.mcp.lifecycle.qualityCheckStarted
import skillbill.mcp.telemetry.toMcpMap
import skillbill.telemetry.model.RemoteStatsRequest

object McpRuntimeLifecycle {
  fun qualityCheckStarted(
    request: QualityCheckStartedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.qualityCheckStarted(request) }

  fun qualityCheckFinished(
    request: QualityCheckFinishedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.qualityCheckFinished(request) }

  fun featureVerifyStarted(
    request: FeatureVerifyStartedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.featureVerifyStarted(request) }

  fun featureVerifyFinished(
    request: FeatureVerifyFinishedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.featureVerifyFinished(request) }

  fun prDescriptionGenerated(
    request: PrDescriptionGeneratedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.prDescriptionGenerated(request) }

  fun telemetryRemoteStats(
    request: RemoteStatsRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).telemetryService.remoteStats(request).toMcpMap()

  fun telemetryProxyCapabilities(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).telemetryService.capabilities().toMcpMap()

  fun captureException(workflowPhase: String, error: Exception, context: McpRuntimeContext) {
    runCatching { services(context).telemetryService.captureException(workflowPhase, error) }
  }

  private fun withAutoSync(
    context: McpRuntimeContext,
    block: (McpRuntimeServices) -> Map<String, Any?>,
  ): Map<String, Any?> {
    val services = services(context)
    val payload = block(services)
    services.telemetryService.autoSync()
    return payload
  }
}
