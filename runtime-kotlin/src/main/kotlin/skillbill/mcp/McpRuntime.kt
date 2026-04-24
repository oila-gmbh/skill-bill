package skillbill.mcp

import skillbill.RuntimeContext
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.telemetry.HttpRequester
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetryHttpRuntime
import java.nio.file.Path

data class McpRuntimeContext(
  val requester: HttpRequester = TelemetryHttpRuntime.defaultHttpRequester,
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
      return linkedMapOf(
        "status" to "skipped",
        "reason" to "telemetry is disabled",
        "review_run_id" to preview["review_run_id"],
        "finding_count" to preview["finding_count"],
      )
    }
    val payload =
      services.reviewService
        .importReview("-", dbOverride = null, finishZeroFindingTelemetry = !orchestrated)
        .toMutableMap()
    if (orchestrated) {
      val reviewRunId = payload["review_run_id"] as String
      services.reviewService.markOrchestrated(reviewRunId, dbOverride = null)
      val telemetryPayload =
        if (payload["finding_count"] == 0) {
          services.reviewService.reviewFinishedTelemetryPayload(reviewRunId, dbOverride = null)
        } else {
          null
        }
      enrichOrchestratedPayload(payload, telemetryPayload, orchestrated)
    }
    return payload
  }

  fun triageFindings(
    reviewRunId: String,
    decisions: List<String>,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val services = services(context)
    if (!services.telemetryService.isEnabled()) {
      return linkedMapOf(
        "status" to "skipped",
        "reason" to "telemetry is disabled",
        "review_run_id" to reviewRunId,
      )
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
    return result.payload.toMutableMap().also { payload ->
      enrichOrchestratedPayload(payload, result.telemetryPayload, orchestrated)
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
      return linkedMapOf(
        "status" to "skipped",
        "reason" to "telemetry is disabled",
        "applied_learnings" to "none",
        "learnings" to emptyList<Map<String, Any?>>(),
      )
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

private fun enrichOrchestratedPayload(
  payload: MutableMap<String, Any?>,
  telemetryPayload: Map<String, Any?>?,
  orchestrated: Boolean,
) {
  if (!orchestrated) {
    return
  }
  payload["mode"] = "orchestrated"
  if (telemetryPayload != null) {
    payload["telemetry_payload"] = linkedMapOf<String, Any?>().apply {
      putAll(telemetryPayload)
      put("skill", "bill-code-review")
    }
  }
}

private fun services(context: McpRuntimeContext, stdinText: String? = null): McpRuntimeServices {
  val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext(stdinText))
  return McpComponent::class.create(runtimeComponent).services
}
