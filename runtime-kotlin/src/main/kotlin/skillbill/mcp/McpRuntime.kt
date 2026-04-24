package skillbill.mcp

import skillbill.RuntimeContext
import skillbill.contracts.mcp.McpLearningsSkippedContract
import skillbill.contracts.mcp.McpOrchestratedPayloadContract
import skillbill.contracts.mcp.McpReviewImportSkippedContract
import skillbill.contracts.mcp.McpTriageSkippedContract
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.ports.telemetry.HttpRequester
import skillbill.telemetry.RemoteStatsRequest
import java.nio.file.Path

data class McpRuntimeContext(
  val requester: HttpRequester = JdkHttpRequester,
  val environment: Map<String, String> = System.getenv(),
  val userHome: Path = Path.of(System.getProperty("user.home")),
) {
  fun toRuntimeContext(stdinText: String? = null): RuntimeContext = RuntimeContext(
    stdinText = stdinText,
    environment = environment,
    userHome = userHome,
    requester = requester,
  )
}

object McpRuntime {
  fun importReview(
    reviewText: String,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val services = services(context, stdinText = reviewText)
    if (!services.telemetryService.isEnabled()) {
      val preview = services.reviewService.previewImport("-")
      return McpReviewImportSkippedContract(
        reason = "telemetry is disabled",
        reviewRunId = preview["review_run_id"] as? String,
        findingCount = preview["finding_count"],
      ).toPayload()
    }
    val payload =
      services.reviewService
        .importReview("-", dbOverride = null, finishZeroFindingTelemetry = !orchestrated)
        .toMutableMap()
    return if (orchestrated) {
      val reviewRunId = payload["review_run_id"] as String
      services.reviewService.markOrchestrated(reviewRunId, dbOverride = null)
      val telemetryPayload =
        if (payload["finding_count"] == 0) {
          services.reviewService.reviewFinishedTelemetryPayload(reviewRunId, dbOverride = null)
        } else {
          null
        }
      McpOrchestratedPayloadContract(basePayload = payload, telemetryPayload = telemetryPayload).toPayload()
    } else {
      payload
    }
  }

  fun triageFindings(
    reviewRunId: String,
    decisions: List<String>,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val services = services(context)
    if (!services.telemetryService.isEnabled()) {
      return McpTriageSkippedContract(reason = "telemetry is disabled", reviewRunId = reviewRunId).toPayload()
    }
    if (orchestrated) {
      services.reviewService.markOrchestrated(reviewRunId, dbOverride = null)
    }
    val result =
      services.reviewService.triage(
        reviewRunId,
        decisions,
        listOnly = false,
        dbOverride = null,
        listWhenNoDecisions = false,
      )
    return if (orchestrated) {
      McpOrchestratedPayloadContract(
        basePayload = result.payload,
        telemetryPayload = result.telemetryPayload,
      ).toPayload()
    } else {
      result.payload
    }
  }

  fun resolveLearnings(
    repo: String? = null,
    skill: String? = null,
    reviewSessionId: String? = null,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val services = services(context)
    if (!services.telemetryService.isEnabled()) {
      return McpLearningsSkippedContract(reason = "telemetry is disabled").toPayload()
    }
    return services.learningService.resolve(repo, skill, reviewSessionId, dbOverride = null).toMcpPayload()
  }

  fun reviewStats(reviewRunId: String? = null, context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.reviewStats(reviewRunId, dbOverride = null)

  fun featureImplementStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.featureImplementStats(dbOverride = null)

  fun featureVerifyStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.featureVerifyStats(dbOverride = null)

  fun telemetryRemoteStats(
    request: RemoteStatsRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).telemetryService.remoteStats(request)

  fun telemetryProxyCapabilities(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).telemetryService.capabilities()

  fun version(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).systemService.version()

  fun doctor(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).systemService.doctor(dbOverride = null)
}

private fun services(context: McpRuntimeContext, stdinText: String? = null): McpRuntimeServices {
  val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext(stdinText))
  return McpComponent::class.create(runtimeComponent).services
}
