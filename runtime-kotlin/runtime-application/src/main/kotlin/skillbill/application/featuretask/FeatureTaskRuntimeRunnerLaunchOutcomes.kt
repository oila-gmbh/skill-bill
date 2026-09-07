package skillbill.application.featuretask

import skillbill.application.agentoutput.agentFailureExcerpt
import skillbill.application.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.FeatureTaskRuntimeProviderLimitDetector
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

private const val PHASE_OUTPUT_STATUS_BLOCKED = "blocked"
private const val PHASE_OUTPUT_STATUS_FAILED = "failed"

fun terminalBlockedReasonFrom(phaseId: String, outputMap: Map<String, Any?>): String? {
  val status = outputMap["status"] as? String
  if (status != PHASE_OUTPUT_STATUS_BLOCKED && status != PHASE_OUTPUT_STATUS_FAILED) {
    return null
  }
  val summary = (outputMap["summary"] as? String).orEmpty().trim()
  val blockingReasons = (outputMap["produced_outputs"] as? Map<*, *>)
    ?.get("blocking_reasons")
    ?.let { value ->
      when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        is String -> listOf(value)
        else -> emptyList()
      }
    }
    .orEmpty()
  val detail = (listOf(summary) + blockingReasons)
    .filter(String::isNotBlank)
    .joinToString("; ")
  val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(phaseId, outputMap)
  val operatorTerminalQualityGate =
    disposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION &&
      (
        phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
          phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
        )
  val prefix = when {
    operatorTerminalQualityGate -> "Phase output reported status '$status'."
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
      "Validation phase reported status '$status'; retrying so the agent can fix failures."
    else -> "Phase output reported status '$status'."
  }
  return prefix + detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
}

fun persistGoalContinuationOutcome(
  goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunRequest,
  report: FeatureTaskRuntimeRunReport,
): FeatureTaskRuntimeRunReport {
  val context = request.goalContinuation ?: return report
  val outcome = goalContinuationOutcomeFor(phaseRecorder, gitOperations, request, context, report)?.let { base ->
    val attribution = agentAttributionFromPhaseState(phaseRecorder, request.workflowId, request.dbPathOverride)
    base.copy(
      finalizingAgentId = attribution.finalizingAgentId,
      participatingAgentIds = attribution.participatingAgentIds,
    )
  }
  outcome?.let { terminal ->
    goalContinuationRecorder.recordGoalContinuationState(
      request = GoalContinuationStateRecordRequest(
        workflowId = request.workflowId,
        outcome = FeatureTaskRuntimeGoalContinuationOutcome(
          issueKey = terminal.issueKey,
          subtaskId = terminal.subtaskId,
          status = terminal.status,
          workflowId = terminal.workflowId,
          commitSha = terminal.commitSha,
          blockedReason = terminal.blockedReason,
          lastResumableStep = terminal.lastResumableStep,
          finalizingAgentId = terminal.finalizingAgentId,
          participatingAgentIds = terminal.participatingAgentIds,
        ),
        workflowStatus = when (terminal.status) {
          "complete" -> "completed"
          FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
          else -> "blocked"
        },
      ),
      dbOverride = request.dbPathOverride,
    )
  }
  return when {
    report is FeatureTaskRuntimeRunReport.Completed && outcome != null -> report.copy(subtaskOutcome = outcome)
    report is FeatureTaskRuntimeRunReport.Blocked && outcome != null -> report.copy(subtaskOutcome = outcome)
    report is FeatureTaskRuntimeRunReport.Paused && outcome != null -> report.copy(subtaskOutcome = outcome)
    else -> report
  }
}

private fun goalContinuationOutcomeFor(
  recorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  request: FeatureTaskRuntimeRunRequest,
  context: FeatureTaskRuntimeGoalContinuationContext,
  report: FeatureTaskRuntimeRunReport,
): FeatureTaskRuntimeSubtaskOutcome? = when (report) {
  is FeatureTaskRuntimeRunReport.Completed ->
    completedGoalContinuationOutcome(recorder, gitOperations, request, context)
  is FeatureTaskRuntimeRunReport.Blocked -> FeatureTaskRuntimeSubtaskOutcome(
    issueKey = context.parentIssueKey,
    subtaskId = context.subtaskId,
    status = "blocked",
    commitSha = null,
    workflowId = request.workflowId,
    blockedReason = report.blockedReason,
    lastResumableStep = report.lastIncompletePhase,
  )
  is FeatureTaskRuntimeRunReport.Paused -> FeatureTaskRuntimeSubtaskOutcome(
    issueKey = context.parentIssueKey,
    subtaskId = context.subtaskId,
    status = "paused",
    commitSha = null,
    workflowId = request.workflowId,
    blockedReason = report.pauseReason,
    lastResumableStep = report.resumableStep,
  )
  is FeatureTaskRuntimeRunReport.Decomposed -> null
}

fun infraFailureReason(phaseId: String, facts: AgentRunLaunchFacts): String? = when {
  facts.spawnFailed -> {
    val base = "Feature-task-runtime phase '$phaseId' failed to launch: the agent process could not be spawned."
    val excerpt = agentFailureExcerpt(facts.stderr, facts.stdout, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
    if (excerpt != null) "$base\n$excerpt" else base
  }
  facts.timedOut -> "Feature-task-runtime phase '$phaseId' launch timed out before the agent produced an output."
  facts.interrupted -> "Feature-task-runtime phase '$phaseId' launch was interrupted before completion."
  facts.exitStatus != null && facts.exitStatus != 0 -> {
    val base = "Feature-task-runtime phase '$phaseId' agent exited with non-zero status ${facts.exitStatus}."
    val excerpt = agentFailureExcerpt(facts.stderr, facts.stdout, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
    if (excerpt != null) "$base\n$excerpt" else base
  }
  else -> null
}

fun providerLimitSignal(facts: AgentRunLaunchFacts): FeatureTaskRuntimeProviderLimitSignal? {
  val carriesProviderVerdict = !facts.spawnFailed && !facts.timedOut && !facts.interrupted
  val failedExit = facts.exitStatus != null && facts.exitStatus != 0
  if (!carriesProviderVerdict || !failedExit) return null
  return FeatureTaskRuntimeProviderLimitDetector.detect(facts.stderr, facts.stdout)
}

fun providerLimitPauseReason(phaseId: String, signal: FeatureTaskRuntimeProviderLimitSignal): String {
  val reset = signal.resetHint?.let { " Access resets $it." }.orEmpty()
  return "Feature-task-runtime phase '$phaseId' stopped because the agent provider refused the request at a " +
    "usage limit.$reset The phase produced no output and consumed no repair attempt; the run is paused and " +
    "resumes at '$phaseId'. Provider said: ${signal.evidence}"
}

fun isProcessFailureBlockReason(phaseId: String, reason: String): Boolean =
  reason.startsWith("Feature-task-runtime phase '$phaseId' ") &&
    PROCESS_FAILURE_REASON_MARKERS.any(reason::contains)

private val PROCESS_FAILURE_REASON_MARKERS: List<String> = listOf(
  "agent exited with non-zero status",
  "failed to launch:",
  "launch timed out",
  "launch was interrupted",
  "could not launch an agent",
)

fun invalidateLegacyPlanWithoutPreplan(completed: MutableSet<String>) {
  val plan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  val preplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  if (plan in completed && preplan !in completed) {
    completed.remove(plan)
  }
}

fun phaseDeclaration(
  phaseId: String,
  featureSize: FeatureTaskRuntimeFeatureSize,
  qualityGateSelection: FeatureTaskRuntimeQualityGateSelection = FeatureTaskRuntimeQualityGateSelection.VALIDATE,
): FeatureTaskRuntimePhaseDeclaration = if (
  phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY ||
  phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
) {
  FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationForQualityGate(
    phaseId,
    featureSize,
    qualityGateSelection,
  )
} else {
  FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(phaseId, featureSize)
}

fun missingUpstream(
  declaration: FeatureTaskRuntimePhaseDeclaration,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
): List<String>? {
  val resolved = FeatureTaskRuntimeHandoffContract
    .resolveUpstreamOutputs(declaration, recordedOutputs)
    .outputsByPhaseId
    .keys
  return declaration.consumedUpstreamPhaseIds.filterNot(resolved::contains).takeIf { it.isNotEmpty() }
}
