package skillbill.mcp.core

import skillbill.application.review.toReviewFinishedTelemetryPayload
import skillbill.contracts.mcp.McpLearningsSkippedContract
import skillbill.contracts.mcp.McpOrchestratedPayloadContract
import skillbill.contracts.mcp.McpReviewImportSkippedContract
import skillbill.contracts.mcp.McpTriageSkippedContract
import skillbill.mcp.learning.toMcpPayload
import skillbill.mcp.review.toMcpMap
import skillbill.mcp.scaffold.McpScaffoldRuntime
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.services

object McpRuntime {
  fun importReview(
    reviewText: String,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context, stdinText = reviewText)
    if (!runtimeServices.telemetryService.isEnabled()) {
      val preview = runtimeServices.reviewService.previewImport("-")
      return McpReviewImportSkippedContract(
        reason = "telemetry is disabled",
        reviewRunId = preview.reviewRunId,
        findingCount = preview.findingCount,
      ).toPayload()
    }
    val importResult =
      runtimeServices.reviewService
        .importReview("-", finishZeroFindingTelemetry = !orchestrated)
    val payload = importResult.toMcpMap().toMutableMap()
    val result = if (orchestrated) {
      val reviewRunId = importResult.preview.reviewRunId
      runtimeServices.reviewService.markOrchestrated(reviewRunId)
      val telemetryPayload =
        if (importResult.preview.findingCount == 0) {
          runtimeServices.reviewService.reviewFinishedTelemetryPayload(reviewRunId)
            ?.toReviewFinishedTelemetryPayload()
            ?.toPayload()
        } else {
          null
        }
      McpOrchestratedPayloadContract(basePayload = payload, telemetryPayload = telemetryPayload).toPayload()
    } else {
      payload
    }
    runtimeServices.telemetryService.autoSync()
    return result
  }

  fun triageFindings(
    reviewRunId: String,
    decisions: List<String>,
    orchestrated: Boolean = false,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context)
    if (!runtimeServices.telemetryService.isEnabled()) {
      return McpTriageSkippedContract(reason = "telemetry is disabled", reviewRunId = reviewRunId).toPayload()
    }
    if (orchestrated) {
      runtimeServices.reviewService.markOrchestrated(reviewRunId)
    }
    val result =
      runtimeServices.reviewService.triage(
        reviewRunId,
        decisions,
        listOnly = false,
        listWhenNoDecisions = false,
      )
    val payload = if (orchestrated) {
      McpOrchestratedPayloadContract(
        basePayload = result.toMcpMap(),
        telemetryPayload = result.telemetry?.toReviewFinishedTelemetryPayload()?.toPayload(),
      ).toPayload()
    } else {
      result.toMcpMap()
    }
    runtimeServices.telemetryService.autoSync()
    return payload
  }

  fun resolveLearnings(
    repo: String? = null,
    skill: String? = null,
    reviewSessionId: String? = null,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context)
    if (!runtimeServices.telemetryService.isEnabled()) {
      return McpLearningsSkippedContract(reason = "telemetry is disabled").toPayload()
    }
    return runtimeServices.learningService.resolve(repo, skill, reviewSessionId).toMcpPayload()
  }

  fun reviewStats(reviewRunId: String? = null, context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.reviewStats(reviewRunId).toMcpMap()

  fun featureVerifyStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.featureVerifyStats().toMcpMap()

  fun goalStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.goalStats().toMcpMap()

  fun version(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).systemService.version().toPayload()

  fun doctor(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).systemService.doctor().toPayload()

  fun updateCheck(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> {
    val result = services(context).updateCheckService.check(includePrereleases = false)
    return mapOf(
      "status" to result.status.wireName,
      "installed_version" to result.installedVersion,
      "latest_version" to result.latestVersion,
      "recommended_install_command" to result.recommendedInstallCommand,
      "reason" to result.reason,
      "release_notes" to result.releaseNotes,
    )
  }

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
