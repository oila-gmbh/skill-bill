package skillbill.mcp.core

import skillbill.application.review.toReviewFinishedTelemetryPayload
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.openFeatureTask
import skillbill.contracts.mcp.McpLearningsSkippedContract
import skillbill.contracts.mcp.McpOrchestratedPayloadContract
import skillbill.contracts.mcp.McpReviewImportSkippedContract
import skillbill.contracts.mcp.McpTriageSkippedContract
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.mcp.learning.toMcpPayload
import skillbill.mcp.review.toMcpMap
import skillbill.mcp.scaffold.McpScaffoldRuntime
import skillbill.mcp.telemetry.toMcpMap
import skillbill.mcp.workflow.toMcpMap
import skillbill.model.EnvironmentContext
import skillbill.model.RuntimeContext
import skillbill.ports.telemetry.RemoteTransportPort
import skillbill.ports.telemetry.UnconfiguredRemoteTransportPort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import java.nio.file.Path

data class McpRuntimeContext(
  val requester: RemoteTransportPort = UnconfiguredRemoteTransportPort,
  val environment: Map<String, String> = EnvironmentContext.UnspecifiedEnvironment,
  val userHome: Path = EnvironmentContext.UnspecifiedUserHome,
  val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val repositoryRoot: Path? = null,
) {
  fun toRuntimeContext(stdinText: String? = null): RuntimeContext = RuntimeContext(
    stdinText = stdinText,
    environment = environment,
    userHome = userHome,
    repositoryRoot = repositoryRoot ?: EnvironmentContext.UnspecifiedRepositoryRoot,
    requester = requester,
    workflowGitOperations = workflowGitOperations,
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
        reviewRunId = preview.reviewRunId,
        findingCount = preview.findingCount,
      ).toPayload()
    }
    val importResult =
      services.reviewService
        .importReview("-", dbOverride = null, finishZeroFindingTelemetry = !orchestrated)
    val payload = importResult.toMcpMap().toMutableMap()
    val result = if (orchestrated) {
      val reviewRunId = importResult.preview.reviewRunId
      services.reviewService.markOrchestrated(reviewRunId, dbOverride = null)
      val telemetryPayload =
        if (importResult.preview.findingCount == 0) {
          services.reviewService.reviewFinishedTelemetryPayload(reviewRunId, dbOverride = null)
            ?.toReviewFinishedTelemetryPayload()
            ?.toPayload()
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
        basePayload = result.toMcpMap(),
        telemetryPayload = result.telemetry?.toReviewFinishedTelemetryPayload()?.toPayload(),
      ).toPayload()
    } else {
      result.toMcpMap()
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
    services(context).reviewService.reviewStats(reviewRunId, dbOverride = null).toMcpMap()

  fun featureVerifyStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.featureVerifyStats(dbOverride = null).toMcpMap()

  fun goalStats(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).reviewService.goalStats(dbOverride = null).toMcpMap()

  fun version(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).systemService.version().toPayload()

  fun doctor(context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).systemService.doctor(dbOverride = null).toPayload()

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

data class McpWorkflowOpenArgs(
  val kind: WorkflowFamilyKind,
  val sessionId: String = "",
  val currentStepId: String? = null,
  val context: McpRuntimeContext = McpRuntimeContext(),
  val issueKey: String? = null,
  val repositoryIdentity: String? = null,
  val governedSpecPath: String? = null,
)

object McpWorkflowRuntime {
  fun open(args: McpWorkflowOpenArgs): Map<String, Any?> {
    val runtimeServices = services(args.context)
    val open = if (args.kind != WorkflowFamilyKind.VERIFY && args.issueKey != null) {
      runtimeServices.workflowService.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = args.kind,
          sessionId = args.sessionId,
          currentStepId = args.currentStepId,
          dbOverride = null,
          issueKey = args.issueKey,
          repositoryIdentity = requireNotNull(args.repositoryIdentity) {
            "Feature-task workflow opens require repository_identity."
          },
          governedSpecPath = requireNotNull(args.governedSpecPath) {
            "Feature-task workflow opens require governed_spec_path."
          },
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    } else {
      runtimeServices.workflowService.open(
        WorkflowServiceOpenArgs(
          kind = args.kind,
          sessionId = args.sessionId,
          currentStepId = args.currentStepId,
          dbOverride = null,
          issueKey = args.issueKey,
          repositoryIdentity = args.repositoryIdentity,
          governedSpecPath = args.governedSpecPath,
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    }
    return open.toMcpMap(runtimeServices.workflowService.goalObservabilityEventValidator)
  }

  fun update(
    kind: WorkflowFamilyKind,
    request: WorkflowUpdateRequest,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context)
    return runtimeServices.workflowService.update(
      kind,
      request,
      dbOverride = null,
    ).toMcpMap()
  }

  fun get(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> {
    val runtimeServices = services(context)
    return runtimeServices.workflowService.get(kind, workflowId, dbOverride = null)
      .toMcpMap(runtimeServices.workflowService.goalObservabilityEventValidator)
  }

  fun list(
    kind: WorkflowFamilyKind,
    limit: Int = 20,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.list(kind, limit, dbOverride = null).toMcpMap()

  fun latest(kind: WorkflowFamilyKind, context: McpRuntimeContext = McpRuntimeContext()): Map<String, Any?> =
    services(context).workflowService.latest(kind, dbOverride = null).toMcpMap()

  fun resume(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
  ): Map<String, Any?> = services(context).workflowService.resume(kind, workflowId, dbOverride = null).toMcpMap()

  fun continueWorkflow(
    kind: WorkflowFamilyKind,
    workflowId: String,
    context: McpRuntimeContext = McpRuntimeContext(),
    subtaskId: Int? = null,
  ): Map<String, Any?> = services(context).workflowService.continueWorkflow(
    kind,
    workflowId,
    subtaskId = subtaskId,
    dbOverride = null,
  ).toMcpMap()
}

internal fun services(context: McpRuntimeContext, stdinText: String? = null): McpRuntimeServices {
  val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext(stdinText))
  return McpComponent::class.create(runtimeComponent).services
}
