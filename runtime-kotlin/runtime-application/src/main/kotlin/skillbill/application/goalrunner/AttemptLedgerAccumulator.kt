package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.featuretask.FeatureTaskRuntimeCrashLiveness
import skillbill.application.featuretask.asPendingForOperatorResume
import skillbill.application.featuretask.phaseLedgerFrom
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.goalrunner.planning.GoalChildPlanningHydrator
import skillbill.application.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.normalizeRequiredIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentOrCorruptFallback
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.generateWorkflowId
import skillbill.application.workflow.repoRoot
import skillbill.application.workflow.requireRuntimeModeForEngineWrite
import skillbill.application.workflow.toRecord
import skillbill.application.workflow.toSnapshot
import kotlin.coroutines.cancellation.CancellationException
import skillbill.contracts.JsonSupport
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalProgressEventSchemaError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.LegacyProseWorkflowError
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.goalrunner.model.GoalChildWorkflowDeletionScope
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifacts
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalObservabilityEvent
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry


internal class AttemptLedgerAccumulator {
  var blockedAttemptCount = 0
  var supervisorKillCount = 0
  val phaseAttemptCounts = mutableMapOf<String, Int>()
  val cumulativeFixIterations = mutableMapOf<String, Int>()
  val reAttemptCauseCounts = mutableMapOf<String, Int>()
  var findingsInScope: Int? = null

  fun accumulate(entry: Map<*, *>) {
    val action = entry["action"]?.toString() ?: return
    if (entry["stop_reason"] != null) {
      if (isBlockStopReason(entry["stop_reason"]?.toString())) blockedAttemptCount++
      entry["re_attempt_cause"]?.toString()?.takeIf(String::isNotBlank)?.let { cause ->
        reAttemptCauseCounts.merge(cause, 1, Int::plus)
      }
      entry["findings_in_scope"].asGoalRunnerIntOrNull()?.let { findingsInScope = it }
    }
    if (entry["diagnostic_class"]?.toString() == "supervisor_killed_confirmed_alive") supervisorKillCount++
    if (action == "child_activation" || action == "resume") {
      val step = entry["current_step"]?.toString()?.takeIf(String::isNotBlank)
        ?: entry["previous_step"]?.toString()?.takeIf(String::isNotBlank)
        ?: "initial_start"
      phaseAttemptCounts.merge(step, 1, Int::plus)
    }
    if (action == "backward_edge_entry") accumulateBackwardEdge(entry)
  }

  private fun accumulateBackwardEdge(entry: Map<*, *>) {
    val subtaskId = entry["subtask_id"].asGoalRunnerIntOrNull() ?: return
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return
    cumulativeFixIterations.merge("$subtaskId:$loopId", count, ::maxOf)
  }

  private fun isBlockStopReason(stopReason: String?): Boolean =
    stopReason != null && stopReason.lowercase() in BLOCK_STOP_REASONS

  fun toSummary() = GoalRunnerAttemptLedgerSummary(
    blockedAttemptCount = blockedAttemptCount,
    supervisorKillCount = supervisorKillCount,
    phaseAttemptCounts = phaseAttemptCounts,
    cumulativeFixIterations = cumulativeFixIterations,
    reAttemptCauseCounts = reAttemptCauseCounts,
    findingsInScope = findingsInScope,
  )
}

internal val BLOCK_STOP_REASONS: Set<String> = setOf(
  "failed",
  "blocked",
  "policy_blocked",
  "dependencies_blocked",
  "pull_request_failed",
)

// Scans the attempt ledger for backward-edge entries and returns the highest cumulative_loop_count
// for each "subtaskId:loopId" pair. Used to seed the recorder's cumulative counters on resume.
internal fun backwardEdgeCountsFromLedger(artifacts: Map<String, Any?>): Map<String, Int> {
  val entries = (artifacts[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>).orEmpty()
  val counts = mutableMapOf<String, Int>()
  entries.forEach { item ->
    val entry = item as? Map<*, *> ?: return@forEach
    if (entry["action"]?.toString() != "backward_edge_entry") return@forEach
    val subtaskId = entry["subtask_id"].asGoalRunnerIntOrNull() ?: return@forEach
    val loopId = entry["loop_id"]?.toString()?.takeIf(String::isNotBlank) ?: return@forEach
    val count = entry["cumulative_loop_count"].asGoalRunnerIntOrNull() ?: return@forEach
    val key = "$subtaskId:$loopId"
    counts.merge(key, count, ::maxOf)
  }
  return counts
}

// SKILL-87: accept BOTH ISO-8601 (declared/observed progress-event timestamps) AND the SQLite
// CURRENT_TIMESTAMP shape "yyyy-MM-dd HH:mm:ss" (space separator, no 'T', no zone) that
// WorkflowStateStore stamps into updated_at. Instant.parse alone always returns null for the latter,
// silently dropping the snapshot-update liveness signal. Best-effort: null only when both fail.
internal fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
  ?: runCatching {
    LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC)
  }.getOrNull()

internal val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal fun progressEventFrom(artifacts: Map<String, Any?>): GoalRunnerProgressEvent? =
  (artifacts["progress_event"] as? Map<*, *>)
    ?.toGoalRunnerProgressEventOrNull()

// SKILL-64 Subtask 3 (AC20-AC23): decode the latest declared progress event for
// the supervisor read seam. Malformed durable records fail loudly at this seam.
internal fun declaredProgressEventFrom(artifacts: Map<String, Any?>): GoalProgressEvent? =
  when (val raw = artifacts[GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY]) {
    null -> null
    is Map<*, *> -> raw.decodeDeclaredGoalProgressEvent(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY)
    else -> throw InvalidGoalProgressEventSchemaError(
      GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY,
      "<root>",
      "must be an object.",
    )
  }

internal fun Map<*, *>.decodeDeclaredGoalProgressEvent(sourceLabel: String): GoalProgressEvent {
  val eventKindWire = this["event_kind"]?.toString()?.takeIf(String::isNotBlank)
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "event_kind", "is required.")
  val eventKind = GoalProgressEventKind.entries.firstOrNull { it.wireValue == eventKindWire }
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "event_kind", "unrecognized value '$eventKindWire'.")
  val workflowId = this["workflow_id"]?.toString()?.takeIf(String::isNotBlank)
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "workflow_id", "is required.")
  val workflowPhase = this["workflow_phase"]?.toString()?.takeIf(String::isNotBlank)
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "workflow_phase", "is required.")
  val timestamp = this["timestamp"]?.toString()?.takeIf(String::isNotBlank)
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "timestamp", "is required.")
  val sequenceNumber = this["sequence_number"].asDeclaredGoalProgressInt(sourceLabel, "sequence_number")
  val outcomeWire = this["outcome"]?.toString()?.takeIf(String::isNotBlank)
  val outcome = if (outcomeWire == null) {
    GoalProgressOutcome.NONE
  } else {
    GoalProgressOutcome.entries.firstOrNull { it.wireValue == outcomeWire }
      ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "outcome", "unrecognized value '$outcomeWire'.")
  }
  return try {
    GoalProgressEvent(
      eventKind = eventKind,
      workflowId = workflowId,
      workflowPhase = workflowPhase,
      processAlive = this["process_alive"] == true,
      sequenceNumber = sequenceNumber,
      timestamp = timestamp,
      stepId = this["step_id"]?.toString()?.takeIf(String::isNotBlank),
      operationName = this["operation_name"]?.toString()?.takeIf(String::isNotBlank),
      operationKind = this["operation_kind"]?.toString()?.takeIf(String::isNotBlank),
      expectedLong = this["expected_long"] == true,
      outcome = outcome,
    )
  } catch (error: IllegalArgumentException) {
    throw InvalidGoalProgressEventSchemaError(sourceLabel, "<root>", error.message ?: "invalid event.", error)
  }
}

internal fun Any?.asDeclaredGoalProgressInt(sourceLabel: String, fieldPath: String): Int = when (this) {
  is Int -> this
  is Number -> toInt()
  is String -> toIntOrNull()
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, fieldPath, "must be an integer.")
  else -> throw InvalidGoalProgressEventSchemaError(sourceLabel, fieldPath, "must be an integer.")
}.also { value ->
  if (value < 0) {
    throw InvalidGoalProgressEventSchemaError(sourceLabel, fieldPath, "must be non-negative.")
  }
}

internal fun Map<*, *>.toGoalRunnerProgressEventOrNull(): GoalRunnerProgressEvent? {
  val stepId = this["step_id"]?.toString()?.takeIf(String::isNotBlank)
  val kind = this["kind"]?.toString()?.takeIf(String::isNotBlank)
  val timestamp = this["timestamp"]?.toString()?.takeIf(String::isNotBlank)
  return if (stepId != null && kind != null && timestamp != null) {
    GoalRunnerProgressEvent(
      stepId = stepId,
      attemptCount = this["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
      kind = kind,
      message = this["message"]?.toString().orEmpty(),
      sequence = this["sequence"].asGoalRunnerIntOrNull() ?: 0,
      timestamp = timestamp,
    )
  } else {
    null
  }
}

internal fun GoalRunnerProgressEvent.summary(): String = buildString {
  append("durable_progress step=")
  append(stepId)
  append(" attempt=")
  append(attemptCount)
  append(" kind=")
  append(kind)
  append(" sequence=")
  append(sequence)
  append(" at=")
  append(timestamp)
  if (message.isNotBlank()) {
    append(" message=")
    append(message)
  }
}

internal fun GoalObservabilityEvent.toProgressEvent(): GoalObservabilityProgressEvent =
  GoalObservabilityProgressEvent(
    issueKey = issueKey,
    subtaskId = subtaskId,
    workflowPhase = workflowPhase,
    workerRole = workerRole,
    livenessClass = livenessClass,
    activitySummary = activitySummary,
    sequenceNumber = sequenceNumber,
    timestamp = timestamp,
  )

internal fun WorkflowStateSnapshot.progressToken(): String = listOf(
  workflowId,
  workflowStatus,
  currentStepId,
  stepsJson,
  artifactsJson,
  updatedAt.orEmpty(),
  finishedAt.orEmpty(),
).joinToString("\n")

internal fun decodeWorkflowSteps(stepsJson: String): List<WorkflowStepState> {
  val element = try {
    JsonSupport.json.parseToJsonElement(stepsJson)
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    throw InvalidWorkflowStateSchemaError("Workflow steps JSON is malformed: ${error.message}")
  }
  val items = JsonSupport.jsonElementToValue(element) as? List<*>
    ?: throw InvalidWorkflowStateSchemaError("Workflow steps JSON must be an array.")
  return items.mapIndexed { index, raw ->
    val item = raw as? Map<*, *>
      ?: throw InvalidWorkflowStateSchemaError("Workflow steps[$index] must be an object.")
    WorkflowStepState(
      stepId = item["step_id"]?.toString().orEmpty(),
      status = item["status"]?.toString().orEmpty(),
      attemptCount = item["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
    )
  }
}

// Resolves the step a blocked/crashed row should resume from off the truthful steps[] (lockstep
// with the runtime's per-phase records since SKILL-85 subtask 1). A running step wins; otherwise
// the first step that is not completed/skipped in definition order is the real resume boundary
// (e.g. completed preplan/plan with a never-started implement resumes at implement, not preplan).
// Only when steps[] carries no resumable boundary do we fall back to the coarse current step.
internal fun blockedStepId(
  record: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  requestedStepId: String,
  definitionStepIds: List<String>,
): String = requestedStepId.takeIf { stepId ->
  stepId.isNotBlank() && steps.firstOrNull { step -> step.stepId == stepId }?.status == "running"
}
  ?: steps.firstOrNull { step -> step.status == "running" }?.stepId
  ?: firstUnfinishedStepId(steps, definitionStepIds)
  ?: record.currentStepId.takeIf(String::isNotBlank)
  ?: requestedStepId.takeIf(String::isNotBlank)
  ?: "preplan"

// The first definition-ordered step whose truthful status is neither completed nor skipped, i.e.
// the earliest phase still owing work. Null when every step is terminal-done.
internal fun firstUnfinishedStepId(steps: List<WorkflowStepState>, definitionStepIds: List<String>): String? {
  val statusByStepId = steps.associate { step -> step.stepId to step.status }
  return definitionStepIds.firstOrNull { stepId ->
    statusByStepId[stepId]?.let { status -> status != "completed" && status != "skipped" } ?: true
  }
}
