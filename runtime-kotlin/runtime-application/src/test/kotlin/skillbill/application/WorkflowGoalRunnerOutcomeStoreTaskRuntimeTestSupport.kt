package skillbill.application

import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.contracts.JsonSupport
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal fun outcomeStoreSqliteTimestamp(instant: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  .withZone(ZoneOffset.UTC)
  .format(instant.truncatedTo(ChronoUnit.SECONDS))

internal data class BlockedContinuationRecordFixture(
  val workflowId: String,
  val workflowStatus: String,
  val stepStatus: String,
  val blockedReasonArtifact: String?,
  val storedBlockedReason: String,
  val declaredProgressTimestamp: Instant? = null,
)

internal fun blockedContinuationRecord(fixture: BlockedContinuationRecordFixture): WorkflowStateRecord {
  val workflowId = fixture.workflowId
  val workflowStatus = fixture.workflowStatus
  val stepStatus = fixture.stepStatus
  val blockedReasonArtifact = fixture.blockedReasonArtifact
  val storedBlockedReason = fixture.storedBlockedReason
  val declaredProgressTimestamp = fixture.declaredProgressTimestamp
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
  val artifacts = linkedMapOf<String, Any?>(
    "goal_continuation" to mapOf(
      "issue_key" to "SKILL-176.4",
      "subtask_id" to 4,
      "suppress_pr" to true,
    ),
    "goal_continuation_outcome" to mapOf(
      "issue_key" to "SKILL-176.4",
      "subtask_id" to 4,
      "status" to "blocked",
      "workflow_id" to workflowId,
      "blocked_reason" to storedBlockedReason,
      "last_resumable_step" to "review",
    ),
  )
  if (blockedReasonArtifact != null) {
    artifacts["blocked_reason"] = blockedReasonArtifact
  }
  if (declaredProgressTimestamp != null) {
    artifacts["goal_progress_latest_event"] = GoalProgressEvent(
      eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
      workflowId = workflowId,
      workflowPhase = "goal_runner_supervision",
      processAlive = true,
      sequenceNumber = 1,
      timestamp = declaredProgressTimestamp.toString(),
      operationName = "child_agent_run",
      operationKind = "long_child_run",
      expectedLong = true,
    ).toArtifactMap()
  }
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = workflowStatus,
      currentStepId = "review",
      stepUpdates = listOf(
        mapOf("step_id" to "review", "status" to stepStatus, "attempt_count" to 1),
      ),
      artifactsPatch = artifacts,
      sessionId = "ftr-176",
    ),
  ).toRecord()
}

internal fun completeWithoutShaContinuationRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "commit_push",
      stepUpdates = listOf(
        mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
      ),
      artifactsPatch = mapOf(
        "goal_continuation" to mapOf(
          "issue_key" to "SKILL-176.4",
          "subtask_id" to 4,
          "suppress_pr" to true,
        ),
        "goal_continuation_outcome" to mapOf(
          "issue_key" to "SKILL-176.4",
          "subtask_id" to 4,
          "status" to "complete",
          "workflow_id" to workflowId,
          "last_resumable_step" to "commit_push",
        ),
      ),
      sessionId = "ftr-176",
    ),
  ).toRecord()
}

internal fun runtimeCandidateRecordNoDeclaredEvent(workflowId: String, updatedAt: String?): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "implement",
      stepUpdates = listOf(
        mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
      ),
      artifactsPatch = mapOf(
        "goal_continuation" to mapOf(
          "issue_key" to "SKILL-87.1",
          "subtask_id" to 1,
          "suppress_pr" to true,
        ),
      ),
      sessionId = "ftr-001",
    ),
  ).toRecord().copy(updatedAt = updatedAt)
}

internal fun goalReviewWorkflowRecord(
  workflowId: String,
  state: GoalSubtaskReviewState,
  rawReviewResult: String,
): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "review",
      stepUpdates = null,
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = "SKILL-119",
          subtaskId = 2,
          suppressPr = true,
          goalBranch = "feat/SKILL-119",
          codeReviewMode = CodeReviewExecutionMode.AUTO,
        ).toArtifactMap(),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to mapOf("1" to rawReviewResult),
      ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun runtimeCandidateRecord(workflowId: String, declaredProgressTimestamp: Instant): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  val declaredEvent = GoalProgressEvent(
    eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
    workflowId = workflowId,
    workflowPhase = "goal_runner_supervision",
    processAlive = true,
    sequenceNumber = 1,
    timestamp = declaredProgressTimestamp.toString(),
    operationName = "child_agent_run",
    operationKind = "long_child_run",
    expectedLong = true,
  )
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "implement",
      stepUpdates = listOf(
        mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
      ),
      artifactsPatch = mapOf(
        "goal_continuation" to mapOf(
          "issue_key" to "SKILL-87.1",
          "subtask_id" to 1,
          "suppress_pr" to true,
        ),
        "goal_progress_latest_event" to declaredEvent.toArtifactMap(),
      ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun taskRuntimeWorkflowRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "implement",
      stepUpdates = null,
      artifactsPatch = emptyMap(),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun decodeArtifacts(artifactsJson: String): Map<String, Any?> {
  val element = JsonSupport.json.parseToJsonElement(artifactsJson)
  return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(element)))
}

internal fun tornBlockedReviewRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  val reviewRecord = FeatureTaskRuntimePhaseRecord(
    phaseId = "review",
    status = "running",
    attemptCount = 2,
    startedAt = "2026-08-15T09:17:42Z",
    resolvedAgentId = "cursor",
  )
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "blocked",
      currentStepId = "review",
      stepUpdates = listOf(
        mapOf("step_id" to "review", "status" to "blocked", "attempt_count" to 2),
      ),
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
          "review" to reviewRecord.toArtifactMap(),
        ),
        "goal_continuation" to mapOf(
          "issue_key" to "SKILL-191",
          "subtask_id" to 9,
          "suppress_pr" to true,
        ),
      ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun crashedChildRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "implement",
      stepUpdates = null,
      artifactsPatch = mapOf(
        "goal_continuation" to mapOf(
          "issue_key" to "SKILL-87.1",
          "subtask_id" to 1,
          "suppress_pr" to true,
        ),
      ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun expiredLeaseOwnership(workflowId: String, expiresAt: String = "2000-01-01T00:00:30Z") =
  FeatureTaskRuntimeWorkerOwnership(
    workflowId = workflowId,
    generation = 1,
    ownerToken = "crashed-owner-$workflowId",
    hostIdentity = "host",
    bootIdentity = "boot",
    pid = 9,
    processBirthToken = "birth-9",
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = "2000-01-01T00:00:00Z",
    expiresAt = expiresAt,
    phaseId = "implement",
    phaseAttempt = 1,
  )

internal object DeadProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")
  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.NotRunning
  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat
  override fun pause(durationMillis: Long) = Unit
}

internal object LiveProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")
  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive
  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat
  override fun pause(durationMillis: Long) = Unit
}

internal object MeasuringHeadShaGitOperations : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "measured-head-sha")
}
