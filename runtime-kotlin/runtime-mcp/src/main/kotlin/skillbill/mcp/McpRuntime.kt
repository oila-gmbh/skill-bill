@file:Suppress("TooManyFunctions")

package skillbill.mcp

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.contracts.mcp.McpLearningsSkippedContract
import skillbill.contracts.mcp.McpOrchestratedPayloadContract
import skillbill.contracts.mcp.McpReviewImportSkippedContract
import skillbill.contracts.mcp.McpTriageSkippedContract
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.model.RuntimeContext
import skillbill.ports.telemetry.HttpRequester
import skillbill.telemetry.model.RemoteStatsRequest
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
    val result = if (orchestrated) {
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
    services.telemetryService.autoSync()
    return result
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
    val payload = if (orchestrated) {
      McpOrchestratedPayloadContract(
        basePayload = result.payload,
        telemetryPayload = result.telemetryPayload,
      ).toPayload()
    } else {
      result.payload
    }
    services.telemetryService.autoSync()
    return payload
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

  fun featureImplementStarted(
    request: FeatureImplementStartedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.featureImplementStarted(request) }

  fun featureImplementFinished(
    request: FeatureImplementFinishedRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = withAutoSync(context) { it.lifecycleTelemetryService.featureImplementFinished(request) }

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

  private fun withAutoSync(
    context: McpRuntimeContext,
    block: (McpRuntimeServices) -> Map<String, Any?>,
  ): Map<String, Any?> {
    val services = services(context)
    val payload = block(services)
    services.telemetryService.autoSync()
    return payload
  }

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

  fun newSkillScaffold(
    payload: Map<String, Any?>,
    dryRun: Boolean = false,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = McpScaffoldRuntime.newSkillScaffold(
    payload = payload,
    dryRun = dryRun,
    orchestrated = orchestrated,
    context = context,
  )
}

object McpWorkflowRuntime {
  fun open(
    kind: WorkflowFamilyKind,
    sessionId: String = "",
    currentStepId: String? = null,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.open(
    kind,
    sessionId = sessionId,
    currentStepId = currentStepId,
    dbOverride = null,
  )

  fun update(
    kind: WorkflowFamilyKind,
    request: WorkflowUpdateRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.update(
    kind,
    request,
    dbOverride = null,
  )

  fun get(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.get(kind, workflowId, dbOverride = null)

  fun list(
    kind: WorkflowFamilyKind,
    limit: Int = 20,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.list(kind, limit, dbOverride = null)

  fun latest(kind: WorkflowFamilyKind, context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).workflowService.latest(kind, dbOverride = null)

  fun resume(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.resume(kind, workflowId, dbOverride = null)

  fun continueWorkflow(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.continueWorkflow(kind, workflowId, dbOverride = null)
}

private fun services(context: McpRuntimeContext, stdinText: String? = null): McpRuntimeServices {
  val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext(stdinText))
  return McpComponent::class.create(runtimeComponent).services
}
