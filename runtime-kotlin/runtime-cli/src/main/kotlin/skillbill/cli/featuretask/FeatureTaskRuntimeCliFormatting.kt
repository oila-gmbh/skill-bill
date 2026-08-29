package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.application.featuretask.model.GoalContinuationCandidate
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.cli.core.CliRunState
import skillbill.cli.core.requireInvokingAgentId
import skillbill.config.model.CompactionSettings
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import java.nio.file.Path
import skillbill.application.featuretask.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.featuretask.model.FeatureTaskRuntimeModelAssignment

internal fun WorkflowService.openRuntimeWorkflowId(
  state: CliRunState,
  issueKey: String?,
  specPath: String,
  repoRoot: String,
  routeScope: FeatureTaskRouteScope,
): String = when (
  val opened = openFeatureTask(
    WorkflowFamilyKind.TASK_RUNTIME,
    issueKey = requireNotNull(issueKey),
    repositoryIdentity = repositoryIdentity(Path.of(repoRoot)),
    governedSpecPath = governedSpecPath(Path.of(repoRoot), Path.of(specPath)),
    routeScope = routeScope,
    dbOverride = state.dbOverride,
  )
) {
  is WorkflowOpenResult.Ok -> opened.workflowId
  is WorkflowOpenResult.Error -> throw UsageError(
    "Could not open a feature-task workflow: ${opened.error}",
  )
}

internal fun repositoryIdentity(start: Path): String {
  return "repo-root-realpath-v1:${canonicalGitRoot(start)}"
}

internal fun canonicalGitRoot(start: Path): Path {
  val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
  var candidate = resolvedStart
  while (!candidate.resolve(".git").toFile().exists()) {
    candidate = candidate.parent ?: return resolvedStart
  }
  return candidate.toRealPath()
}

internal fun governedSpecPath(repositoryRoot: Path, specPath: Path): String {
  val root = canonicalGitRoot(repositoryRoot)
  val resolved = (if (specPath.isAbsolute) specPath else root.resolve(specPath)).normalize().toRealPath()
  if (!resolved.startsWith(root)) {
    throw UsageError("Governed spec path must remain inside repository '$root'.")
  }
  val relative = root.relativize(resolved).joinToString("/") { it.toString() }
  if (!relative.startsWith(".feature-specs/") || !relative.endsWith(".md")) {
    throw UsageError("Governed spec path must be Markdown beneath .feature-specs/.")
  }
  return relative
}

@Suppress("LongParameterList", "ThrowsCount")
internal fun verifyRuntimeResume(
  lookupService: FeatureTaskContinuationLookupService,
  state: CliRunState,
  workflowId: String,
  issueKey: String,
  specPath: String,
  repoRoot: String,
  goalChild: Boolean,
) {
  val effectiveRoot = resumeRepositoryRoot(repoRoot, Path.of(specPath))
  val identity = repositoryIdentity(effectiveRoot)
  val result = if (goalChild) {
    lookupService.lookupGoalChild(issueKey, identity, workflowId, state.dbOverride)
  } else {
    lookupService.lookup(issueKey, identity, workflowId, state.dbOverride)
  }
  val candidate = when (result) {
    is FeatureTaskContinuationLookupResult.Resumable -> result.candidate
    is FeatureTaskContinuationLookupResult.AlreadyRunning -> result.candidate
    is FeatureTaskContinuationLookupResult.TerminalOnly ->
      throw UsageError("Workflow '$workflowId' is terminal and cannot be resumed; no phase was launched.")
    FeatureTaskContinuationLookupResult.NoMatch,
    is FeatureTaskContinuationLookupResult.Ambiguous,
    is FeatureTaskContinuationLookupResult.GoalContinuation,
    is FeatureTaskContinuationLookupResult.NeedsIdentityRepair,
    -> throw UsageError("Workflow '$workflowId' is not a resumable runtime workflow.")
  }
  if (candidate.mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw UsageError("Workflow '$workflowId' was persisted in ${candidate.mode.wireValue} mode.")
  }
  if (candidate.governedSpecPath != governedSpecPath(effectiveRoot, Path.of(specPath))) {
    throw UsageError("Workflow '$workflowId' was persisted with a different governed spec path.")
  }
}

internal fun resumeRepositoryRoot(repoRoot: String, specPath: Path): Path {
  if (repoRoot != "." || !specPath.isAbsolute) return Path.of(repoRoot)
  var candidate: Path? = specPath.parent
  while (candidate != null && candidate.fileName?.toString() != ".feature-specs") candidate = candidate.parent
  return candidate?.parent ?: Path.of(repoRoot)
}

internal fun runtimeRunEventSink(state: CliRunState, monitor: Boolean): FeatureTaskRuntimeRunEventSink = if (!monitor) {
  FeatureTaskRuntimeRunEventSink.NONE
} else {
  FeatureTaskRuntimeRunEventSink { event ->
    state.liveStdout(event.runtimeProgressLine())
  }
}

internal fun FeatureTaskRuntimeRunEvent.runtimeProgressLine(): String = when (this) {
  is FeatureTaskRuntimeRunEvent.RunStarted ->
    "feature-task-runtime $workflowId: run started feature_size=$featureSize\n"
  is FeatureTaskRuntimeRunEvent.BranchResolved ->
    "feature-task-runtime $workflowId: branch ${if (reused) "reused" else "created"} $branch\n"
  is FeatureTaskRuntimeRunEvent.BranchSetupBlocked ->
    "feature-task-runtime $workflowId: branch setup blocked at phase $phaseId: $blockedReason\n"
  is FeatureTaskRuntimeRunEvent.PhaseStarted -> progressLine()
  is FeatureTaskRuntimeRunEvent.PhaseLoopEdge ->
    "feature-task-runtime $workflowId: phase $phaseId $continuationKind loop=$loopId " +
      "edge_iteration=$edgeIteration driving_verdict=$drivingVerdict\n"
  is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration ->
    "feature-task-runtime $workflowId: phase $phaseId " +
      "${continuationKind ?: "fix_loop"} attempt=$attemptCount iteration=$fixLoopIteration\n"
  is FeatureTaskRuntimeRunEvent.ValidationGateProgress ->
    "feature-task-runtime $workflowId: phase $phaseId gate_run_count=$gateRunCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseCompleted ->
    "feature-task-runtime $workflowId: phase $phaseId completed agent=$resolvedAgentId attempt=$attemptCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseBlocked ->
    "feature-task-runtime $workflowId: phase $phaseId blocked attempt=$attemptCount: $blockedReason\n"
  is FeatureTaskRuntimeRunEvent.PhasePaused ->
    "feature-task-runtime $workflowId: phase $phaseId paused attempt=$attemptCount: $pauseReason\n"
  is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning ->
    "feature-task-runtime $workflowId: decomposed at planning into $subtaskCount subtasks: $reason. " +
      "Work the first subtask first.\n"
}

internal fun FeatureTaskRuntimeRunEvent.PhaseStarted.progressLine(): String =
  "feature-task-runtime $workflowId: phase $phaseId ${if (resumed) "resumed" else "started"} " +
    "agent=$resolvedAgentId attempt=$attemptCount" +
    model?.let { " model=$it" }.orEmpty() +
    effort?.let { " effort=$it" }.orEmpty() +
    continuationKind?.let { " continuation=$it" }.orEmpty() +
    "\n"

internal fun parsePhaseAgents(rawAssignments: List<String>): Map<String, String> {
  val parsed = LinkedHashMap<String, String>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      throw UsageError("--phase-agent must be phase=agent, e.g. --phase-agent plan=claude (got '$assignment').")
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val agentId = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      throw UsageError(
        "--phase-agent phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    parsed[phaseId] = agentId
  }
  return parsed
}

internal fun parsePhaseModels(rawAssignments: List<String>): Map<String, PhaseModelDirective> {
  val parsed = LinkedHashMap<String, PhaseModelDirective>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      invalidPhaseModel(
        "--phase-model must be phase=model[@effort], e.g. --phase-model plan=claude-opus-4-8@high " +
          "(got '$assignment').",
      )
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val modelAndEffort = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      invalidPhaseModel(
        "--phase-model phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    if (modelAndEffort.count { it == '@' } > 1) {
      invalidPhaseModel("--phase-model allows at most one @ separating model and effort (got '$assignment').")
    }
    val effortSeparator = modelAndEffort.indexOf('@')
    val model = modelAndEffort.substringBefore('@').trim()
    val effort = if (effortSeparator == -1) null else modelAndEffort.substring(effortSeparator + 1).trim()
    if (model.isBlank() || effort?.isBlank() == true) {
      invalidPhaseModel("--phase-model requires non-blank model and effort segments (got '$assignment').")
    }
    parsed[phaseId] = PhaseModelDirective(model = model, effort = effort)
  }
  return parsed
}

internal fun invalidPhaseModel(message: String): Nothing = throw UsageError(message)

internal data class PreparedRuntimeRun(
  val repoRoot: Path,
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  val modelAssignment: FeatureTaskRuntimeModelAssignment,
  val compactionSettings: CompactionSettings,
  val agentAddonSelection: HydratedAgentAddonSelection,
)

internal fun parseAgentAddonSelection(raw: String?): AgentAddonSelection {
  if (raw == null) return AgentAddonSelection()
  val root = JsonSupport.parseObjectOrNull(raw)
    ?: invalidAgentAddonSelection("--agent-addon-selection-json must be a JSON object.")
  val map = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(root))
    ?: invalidAgentAddonSelection("--agent-addon-selection-json must decode to an object.")
  if (map.keys != setOf("contract_version", "entries") || map["contract_version"] != "0.1") {
    invalidAgentAddonSelection("Agent add-on selection must contain only contract_version=0.1 and entries.")
  }
  val entries = map["entries"] as? List<*>
    ?: invalidAgentAddonSelection("Agent add-on selection entries must be an ordered array.")
  return try {
    AgentAddonSelection(
      entries.mapIndexed { index, valueEntry ->
        val entry = JsonSupport.anyToStringAnyMap(valueEntry)
          ?: invalidAgentAddonSelection("Agent add-on selection entry $index must be an object.")
        val persistedKeys = setOf("slug", "source_identity", "content_sha256")
        if (!entry.keys.containsAll(persistedKeys) || entry.keys.any { it !in persistedKeys + "description" }) {
          invalidAgentAddonSelection("Agent add-on selection entry $index has unsupported or missing fields.")
        }
        PersistedAgentAddonSelectionEntry(
          slug = entry["slug"] as? String
            ?: invalidAgentAddonSelection("Entry $index slug is required."),
          sourceIdentity = entry["source_identity"] as? String
            ?: invalidAgentAddonSelection("Entry $index source_identity is required."),
          contentSha256 = entry["content_sha256"] as? String
            ?: invalidAgentAddonSelection("Entry $index content_sha256 is required."),
        )
      },
    )
  } catch (error: IllegalArgumentException) {
    invalidAgentAddonSelection("Invalid agent add-on selection: ${error.message}", error)
  }
}

internal fun invalidAgentAddonSelection(message: String, cause: Throwable? = null): Nothing {
  throw UsageError(message).apply { cause?.let(::initCause) }
}

internal fun resolveInvokedRuntimeAgentId(explicitAgent: String?, environment: Map<String, String>): String =
  requireInvokingAgentId(explicitAgent, environment, "--agent")

internal fun FeatureTaskRuntimeRunReport.toRuntimeRunCliMap(): Map<String, Any?> = when (this) {
  is FeatureTaskRuntimeRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Blocked -> linkedMapOf(
    "status" to "blocked",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "last_incomplete_phase" to lastIncompletePhase,
    "blocked_reason" to blockedReason,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Paused -> linkedMapOf(
    "status" to "paused",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "paused_phase" to pausedPhase,
    "pause_reason" to pauseReason,
    "resumable_step" to resumableStep,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Decomposed -> linkedMapOf(
    "status" to "decomposed",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "reason" to reason,
    "completed_phases" to completedPhaseIds,
    "parent_spec_path" to parentSpecPath,
    "decomposition_manifest_path" to decompositionManifestPath,
    "subtask_spec_paths" to subtaskSpecPaths,
    "subtask_count" to subtaskSpecPaths.size,
    "guidance" to DECOMPOSE_GUIDANCE,
  )
}

internal fun Map<String, Any?>.withSubtaskOutcome(
  outcome: FeatureTaskRuntimeSubtaskOutcome?,
): Map<String, Any?> = if (outcome == null) {
  this
} else {
  LinkedHashMap(this).apply {
    put(
      "subtask_outcome",
      linkedMapOf(
        "issue_key" to outcome.issueKey,
        "subtask_id" to outcome.subtaskId,
        "status" to outcome.status,
        "commit_sha" to outcome.commitSha,
        "workflow_id" to outcome.workflowId,
        "blocked_reason" to outcome.blockedReason,
        "last_resumable_step" to outcome.lastResumableStep,
        "finalizing_agent_id" to outcome.finalizingAgentId,
        "participating_agent_ids" to outcome.participatingAgentIds,
      ),
    )
  }
}

internal fun Map<String, Any?>.runtimeRunExitCode(): Int = if (isTerminalSuccessStatus()) 0 else 1

internal fun Map<String, Any?>.isTerminalSuccessStatus(): Boolean = this["status"] in setOf("complete", "decomposed")

internal fun runtimeRunText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["issue_key"]}")
  appendLine("workflow_id: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"]}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("completed_phases: ${(payload["completed_phases"] as? List<*>).orEmpty().joinToString()}")
  payload["last_incomplete_phase"]?.let { appendLine("last_incomplete_phase: $it") }
  payload["blocked_reason"]?.let { appendLine("blocked_reason: $it") }
  (payload["subtask_outcome"] as? Map<*, *>)?.let { outcome -> appendSubtaskOutcome(outcome) }
  payload["reason"]?.let { appendLine("decomposition_reason: $it") }
  payload["subtask_count"]?.let { appendLine("subtask_count: $it") }
  payload["parent_spec_path"]?.let { appendLine("parent_spec_path: $it") }
  payload["decomposition_manifest_path"]?.let { appendLine("decomposition_manifest_path: $it") }
  (payload["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
  payload["guidance"]?.let { appendLine("guidance: $it") }
}

internal fun StringBuilder.appendSubtaskOutcome(outcome: Map<*, *>) {
  appendLine("subtask_outcome:")
  appendLine("  issue_key: ${outcome["issue_key"]}")
  appendLine("  subtask_id: ${outcome["subtask_id"]}")
  appendLine("  status: ${outcome["status"]}")
  appendLine("  commit_sha: ${outcome["commit_sha"] ?: "none"}")
  appendLine("  workflow_id: ${outcome["workflow_id"]}")
  appendLine("  last_resumable_step: ${outcome["last_resumable_step"]}")
  outcome["finalizing_agent_id"]?.let { appendLine("  finalizing_agent_id: $it") }
  (outcome["participating_agent_ids"] as? List<*>)?.takeIf { it.isNotEmpty() }
    ?.let { appendLine("  participating_agent_ids: ${it.joinToString()}") }
  outcome["blocked_reason"]?.let { appendLine("  blocked_reason: $it") }
}

internal fun FeatureTaskRuntimeStatusProjection?.toRuntimeStatusCliMap(workflowId: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      "status" to "ok",
      "workflow_id" to it.workflowId,
      "feature_size" to it.featureSize,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_phase" to it.currentPhaseId,
      "resolved_branch" to it.resolvedBranch,
      "finalizing_agent_id" to it.finalizingAgentId,
      "gate_run_count" to it.gateRunCount,
      "audit_repair" to it.auditRepair?.let { progress ->
        linkedMapOf(
          "first_pass_convergence" to progress.firstPassConvergence,
          "audit_gap_iteration_count" to progress.auditGapIterationCount,
        )
      },
      "degraded_diagnostic" to it.degradedDiagnostic?.let { degraded ->
        linkedMapOf(
          "count" to degraded.count,
          "failure_class" to degraded.failureClass,
          "phase_id" to degraded.phaseId,
          "attempt" to degraded.attempt,
        )
      },
      "decompose_terminal" to it.decomposeTerminal?.let { terminal ->
        linkedMapOf(
          "reason" to terminal.reason,
          "parent_spec_path" to terminal.parentSpecPath,
          "decomposition_manifest_path" to terminal.decompositionManifestPath,
          "subtask_spec_paths" to terminal.subtaskSpecPaths,
          "subtask_count" to terminal.subtaskCount,
          "guidance" to DECOMPOSE_GUIDANCE,
        )
      },
      "phases" to it.phases.map(FeatureTaskRuntimePhaseStatus::toRuntimePhaseStatusCliMap),
    )
  } ?: linkedMapOf(
    "status" to "not_found",
    "workflow_id" to workflowId,
    "feature_size" to null,
    "complete_count" to 0,
    "pending_count" to 0,
    "blocked_count" to 0,
    "current_phase" to null,
    "resolved_branch" to null,
    "finalizing_agent_id" to null,
    "audit_repair" to null,
    "degraded_diagnostic" to null,
    "decompose_terminal" to null,
    "phases" to emptyList<Map<String, Any?>>(),
  )

internal fun FeatureTaskRuntimePhaseStatus.toRuntimePhaseStatusCliMap(): Map<String, Any?> = linkedMapOf(
  "phase_id" to phaseId,
  "status" to status,
  "attempt_count" to attemptCount,
  "resolved_agent_id" to resolvedAgentId,
  "execution_origin" to executionOrigin,
  "continuation_kind" to continuationKind,
  "finished" to finished,
)

internal fun Map<String, Any?>.runtimeStatusExitCode(): Int = if (this["status"] == "ok") 0 else 1

internal fun runtimeStatusText(payload: Map<String, Any?>): String = buildString {
  appendLine("feature-task-runtime: ${payload["workflow_id"]}")
  appendLine("status: ${payload["status"]}")
  appendLine("feature_size: ${payload["feature_size"] ?: "unknown"}")
  appendLine("complete: ${payload["complete_count"]}")
  appendLine("pending: ${payload["pending_count"]}")
  appendLine("blocked: ${payload["blocked_count"]}")
  appendLine("current_phase: ${payload["current_phase"] ?: "none"}")
  appendLine("resolved_branch: ${payload["resolved_branch"] ?: "none"}")
  appendLine("finalizing_agent: ${payload["finalizing_agent_id"] ?: "none"}")
  (payload["audit_repair"] as? Map<*, *>)?.let { progress ->
    appendLine("audit_first_pass_convergence: ${progress["first_pass_convergence"]}")
    appendLine("audit_recurring_gap_count: ${progress["recurring_gap_count"]}")
    appendLine("audit_new_gap_count: ${progress["new_gap_count"]}")
    appendLine("audit_attempted_repair_item_count: ${progress["attempted_repair_item_count"]}")
    appendLine("audit_resolved_repair_item_count: ${progress["resolved_repair_item_count"]}")
    appendLine("audit_gap_iteration_count: ${progress["audit_gap_iteration_count"]}")
  }
  (payload["degraded_diagnostic"] as? Map<*, *>)?.let { degraded ->
    appendLine("degraded_diagnostic_count: ${degraded["count"]}")
    appendLine("degraded_diagnostic_failure_class: ${degraded["failure_class"]}")
    appendLine("degraded_diagnostic_phase: ${degraded["phase_id"]}")
    appendLine("degraded_diagnostic_attempt: ${degraded["attempt"]}")
  }
  (payload["decompose_terminal"] as? Map<*, *>)?.let { terminal ->
    appendLine("decomposition_reason: ${terminal["reason"]}")
    appendLine("subtask_count: ${terminal["subtask_count"]}")
    appendLine("parent_spec_path: ${terminal["parent_spec_path"]}")
    appendLine("decomposition_manifest_path: ${terminal["decomposition_manifest_path"]}")
    (terminal["subtask_spec_paths"] as? List<*>).orEmpty().forEach { appendLine("subtask_spec_path: $it") }
    appendLine("guidance: ${terminal["guidance"]}")
  }
  (payload["phases"] as? List<*>).orEmpty().forEach { rawPhase ->
    val phase = rawPhase as? Map<*, *> ?: return@forEach
    appendLine(
      "phase: id=${phase["phase_id"]} status=${phase["status"]} attempt=${phase["attempt_count"]} " +
        "agent=${phase["resolved_agent_id"] ?: "none"} " +
        "origin=${phase["execution_origin"] ?: "none"} finished=${phase["finished"]}",
    )
  }
}

internal const val DECOMPOSE_GUIDANCE: String =
  "Work the first subtask first, then continue through the ordered spec_subtask_*.md files."
