package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.RejectedOutputDiagnosticService
import skillbill.application.model.GoalPlanningLog
import skillbill.application.model.GoalPlanningLogAttempt
import skillbill.application.model.GoalPlanningLogRequest
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import java.time.Instant

private const val GOAL_PLANNING_WORKFLOW_PHASE = "goal_planning"
private const val OPERATION_STARTED = "operation_started"
private const val OPERATION_COMPLETED = "operation_completed"
private const val OPERATION_NAME_SEGMENTS = 4
private const val OPERATION_PHASE_INDEX = 0
private const val OPERATION_SUBTASK_INDEX = 1
private const val OPERATION_LITERAL_INDEX = 2
private const val OPERATION_ATTEMPT_INDEX = 3

@Inject
class GoalPlanningLogService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val database: DatabaseSessionFactory,
  private val diagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
) {
  fun log(request: GoalPlanningLogRequest): GoalPlanningLog {
    val parentWorkflowId = manifestStore
      .readByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.parentWorkflowId
      ?: return GoalPlanningLog(request.issueKey, null)

    val events = outcomeStore.progressEvents(parentWorkflowId, request.dbPathOverride)
      .filter { event -> event["workflow_phase"] == GOAL_PLANNING_WORKFLOW_PHASE }
    val rejections = readRejections(parentWorkflowId, request.dbPathOverride)

    val attempts = assembleAttempts(events, rejections)
      .filter { attempt -> request.subtaskId == null || attempt.subtaskId == request.subtaskId }
      .filter { attempt -> !request.failuresOnly || attempt.outcome == "failed" }

    return GoalPlanningLog(
      issueKey = request.issueKey,
      parentWorkflowId = parentWorkflowId,
      attempts = attempts,
    )
  }

  /**
   * Diagnostics are keyed by the goal-planning diagnostic phase id (`preplan`, `plan:<subtask>`),
   * which is not enumerable from the store, so the whole workflow's diagnostics are read once and
   * joined in memory rather than issued as one query per observed phase.
   */
  private fun readRejections(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): Map<String, RejectedOutputDiagnostic> = runCatching {
    database.transaction(dbPathOverride) { unitOfWork ->
      val repository = unitOfWork.rejectedOutputDiagnostics ?: return@transaction emptyList()
      val permissions = unitOfWork.rejectedOutputDiagnosticPermissions ?: return@transaction emptyList()
      RejectedOutputDiagnosticService(repository, permissions, diagnosticMetadataValidator)
        .inspect(RejectedOutputDiagnosticSelector(workflowId = parentWorkflowId))
    }
  }
    .getOrDefault(emptyList())
    .associateBy { record -> rejectionKey(record.phaseId, record.attempt) }

  private fun assembleAttempts(
    events: List<Map<String, Any?>>,
    rejections: Map<String, RejectedOutputDiagnostic>,
  ): List<GoalPlanningLogAttempt> {
    val started = mutableMapOf<String, Instant?>()
    val ordered = mutableListOf<String>()
    val completed = mutableMapOf<String, Map<String, Any?>>()

    events.forEach { event ->
      val operation = event["operation_name"] as? String ?: return@forEach
      when (event["event_kind"]) {
        OPERATION_STARTED -> {
          if (!started.containsKey(operation)) ordered += operation
          started[operation] = timestamp(event)
        }

        OPERATION_COMPLETED -> {
          if (!started.containsKey(operation) && !completed.containsKey(operation)) ordered += operation
          completed[operation] = event
        }
      }
    }

    // Each resumed run restarts its progress sequence at zero, so ledger order interleaves segments.
    // Only the timestamps totally order attempts across a goal that blocked and was relaunched.
    return ordered.mapNotNull { operation ->
      val parsed = parseOperation(operation) ?: return@mapNotNull null
      val completion = completed[operation]
      val rejection = rejections[rejectionKey(parsed.diagnosticPhaseId, parsed.attempt)]
      GoalPlanningLogAttempt(
        phaseId = parsed.diagnosticPhaseId,
        subtaskId = parsed.subtaskId,
        attempt = parsed.attempt,
        startedAt = started[operation],
        finishedAt = completion?.let(::timestamp),
        outcome = completion?.get("outcome") as? String ?: "in_flight",
        rule = rejection?.rule,
        reason = rejection?.reason,
        agentId = rejection?.agentId,
        rejectedOutputIdentity = rejection?.identity,
        rejectedOutputBytes = rejection?.byteSize,
      )
    }.sortedBy { attempt -> attempt.startedAt ?: attempt.finishedAt ?: Instant.EPOCH }
  }

  private fun timestamp(event: Map<String, Any?>): Instant? =
    (event["timestamp"] as? String)?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }

  private data class ParsedOperation(val diagnosticPhaseId: String, val subtaskId: Int, val attempt: Int)

  /** Operation names are minted as `<phase>:<subtask>:attempt:<n>` by the planning attempt recorder. */
  private fun parseOperation(operation: String): ParsedOperation? {
    val parts = operation.split(":")
    if (parts.size != OPERATION_NAME_SEGMENTS || parts[OPERATION_LITERAL_INDEX] != "attempt") return null
    val subtaskId = parts[OPERATION_SUBTASK_INDEX].toIntOrNull() ?: return null
    val attempt = parts[OPERATION_ATTEMPT_INDEX].toIntOrNull() ?: return null
    val phase = parts[OPERATION_PHASE_INDEX]
    return ParsedOperation(if (subtaskId == 0) phase else "$phase:$subtaskId", subtaskId, attempt)
  }

  private fun rejectionKey(phaseId: String, attempt: Int): String = "$phaseId#$attempt"
}
