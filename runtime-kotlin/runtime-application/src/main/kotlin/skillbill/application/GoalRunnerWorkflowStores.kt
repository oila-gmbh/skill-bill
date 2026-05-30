package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepState
import skillbill.workflow.model.WorkflowUpdateInput
import java.nio.file.Path

@Inject
class WorkflowGoalRunnerManifestStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestFileStore: DecompositionManifestFileStore,
) : GoalRunnerManifestStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState? =
    database.read(dbPathOverride) { unitOfWork ->
      val record = unitOfWork.workflowStates.findDecomposedParentWorkflow(issueKey, decompositionManifestValidator)
        ?: return@read null
      val snapshot = record.toSnapshot()
      val manifest = snapshot.decompositionRuntime(decompositionManifestValidator) ?: return@read null
      GoalRunnerManifestState(
        parentWorkflowId = snapshot.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = manifest,
      )
    }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    var projectionArtifactsJson: String? = null
    val saved = database.transaction(dbPathOverride) { unitOfWork ->
      val existing = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, state.parentWorkflowId)
        ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
          state.manifest.issueKey,
          decompositionManifestValidator,
        )?.toSnapshot()
        ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
      val existingSnapshot = existing
      val updated = engine.updateRecord(
        WorkflowFamily.IMPLEMENT.definition,
        existingSnapshot,
        WorkflowUpdateInput(
          workflowStatus = existingSnapshot.workflowStatus,
          currentStepId = existingSnapshot.currentStepId,
          stepUpdates = null,
          artifactsPatch = mapOf(
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
              state.manifest,
              decompositionManifestValidator,
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
            ),
          ),
          sessionId = existingSnapshot.sessionId.orEmpty(),
        ),
      )
      WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updated)
      val refreshed = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, updated.workflowId) ?: updated
      projectionArtifactsJson = refreshed.artifactsJson
      GoalRunnerManifestState(
        parentWorkflowId = refreshed.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshed.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
      )
    }
    projectionArtifactsJson?.let { artifactsJson ->
      DecompositionManifestWriter.writeProjectionFromWorkflowState(
        Path.of("").toAbsolutePath(),
        artifactsJson,
        decompositionManifestValidator,
        decompositionManifestFileStore,
      )
    }
    return saved
  }
}

@Inject
class WorkflowGoalRunnerOutcomeStore(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) : GoalRunnerWorkflowOutcomeStore {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = database.read(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    val snapshot = record
    engine.snapshotView(WorkflowFamily.IMPLEMENT.definition, snapshot)
    val artifacts = decodeArtifacts(snapshot.artifactsJson)
    val goalContinuation = artifacts["goal_continuation"] as? Map<*, *> ?: return@read null
    if (goalContinuation["issue_key"]?.toString() != issueKey) return@read null
    if (goalContinuation["subtask_id"].asGoalRunnerIntOrNull() != subtaskId) return@read null
    val suppressPr = goalContinuation["suppress_pr"] == true
    val commitSha = commitShaFrom(artifacts)
    val steps = decodeWorkflowSteps(snapshot.stepsJson)
    val status = terminalStatus(snapshot, steps, suppressPr, commitSha) ?: return@read null
    GoalRunnerStoredOutcome(
      status = status,
      workflowId = snapshot.workflowId,
      commitSha = commitSha,
      blockedReason = blockedReasonFrom(artifacts, steps, status),
      lastResumableStep = snapshot.currentStepId,
      suppressPr = suppressPr,
    )
  }
}

private fun terminalStatus(
  snapshot: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  suppressPr: Boolean,
  commitSha: String?,
): GoalRunnerTerminalStatus? = when {
  suppressPr && steps.any { it.stepId == "commit_push" && it.status == "completed" } ->
    if (commitSha.isNullOrBlank()) {
      GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
    } else {
      GoalRunnerTerminalStatus.COMPLETE
    }
  snapshot.workflowStatus == "failed" || steps.any { it.status == "failed" } -> GoalRunnerTerminalStatus.FAILED
  snapshot.workflowStatus == "blocked" || steps.any { it.status == "blocked" } -> GoalRunnerTerminalStatus.BLOCKED
  snapshot.workflowStatus in setOf("completed", "abandoned") -> GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME
  else -> null
}

private fun blockedReasonFrom(
  artifacts: Map<String, Any?>,
  steps: List<WorkflowStepState>,
  status: GoalRunnerTerminalStatus,
): String? = artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
  ?: steps.firstOrNull { it.status in setOf("failed", "blocked") }
    ?.let { step -> "Workflow step '${step.stepId}' is ${step.status}." }
  ?: "Workflow reached a terminal state without a goal-continuation commit SHA."
    .takeIf { status == GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME }

private fun commitShaFrom(artifacts: Map<String, Any?>): String? =
  (artifacts["commit_push_result"] as? Map<*, *>)?.get("commit_sha")?.toString()?.takeIf(String::isNotBlank)

private fun decodeWorkflowSteps(stepsJson: String): List<WorkflowStepState> {
  val element = runCatching { JsonSupport.json.parseToJsonElement(stepsJson) }.getOrNull() ?: return emptyList()
  return (JsonSupport.jsonElementToValue(element) as? List<*>).orEmpty().mapNotNull { raw ->
    val item = raw as? Map<*, *> ?: return@mapNotNull null
    WorkflowStepState(
      stepId = item["step_id"]?.toString().orEmpty(),
      status = item["status"]?.toString().orEmpty(),
      attemptCount = item["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
    )
  }
}

private fun Any?.asGoalRunnerIntOrNull(): Int? = when (this) {
  is Int -> this
  is Number -> toInt()
  is String -> toIntOrNull()
  else -> null
}
