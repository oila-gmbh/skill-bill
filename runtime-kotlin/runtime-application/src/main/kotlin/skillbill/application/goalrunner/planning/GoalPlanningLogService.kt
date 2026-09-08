package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.goalrunner.planning.model.GoalPlanningLog
import skillbill.application.goalrunner.planning.model.GoalPlanningLogAttempt
import skillbill.application.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import java.time.Clock
import java.time.Instant

private const val GOAL_PLANNING_WORKFLOW_PHASE = "goal_planning"
private const val OPERATION_STARTED = "operation_started"
private const val OPERATION_COMPLETED = "operation_completed"
private const val OPERATION_NAME_SEGMENTS = 4
private const val OPERATION_PHASE_INDEX = 0
private const val OPERATION_SUBTASK_INDEX = 1
private const val OPERATION_LITERAL_INDEX = 2
private const val OPERATION_ATTEMPT_INDEX = 3
private const val OUTCOME_IN_FLIGHT = "in_flight"

@Inject
class GoalPlanningLogService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val database: DatabaseSessionFactory,
  private val diagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val clock: Clock,
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
      RejectedOutputDiagnosticService(repository, permissions, diagnosticMetadataValidator, clock = clock)
        .inspect(RejectedOutputDiagnosticSelector(workflowId = parentWorkflowId))
    }
  }
    .getOrDefault(emptyList())
    .associateBy { record -> rejectionKey(record.phaseId, record.attempt) }

  /**
   * Pairs each start with its own completion.
   *
   * A relaunched goal re-mints the same `<phase>:<subtask>:attempt:<n>` operation name for work it
   * retries, so the name does not identify one interval. Keying a start map and a completion map by
   * name alone kept the newest start and the newest completion independently, which paired a
   * relaunch's start with the previous segment's completion and reported a finish stamped before its
   * own start. Every start opens its own occurrence here, and a completion closes the newest
   * occurrence still open for that name: planning attempts do not overlap, so the completion belongs
   * to the attempt that started most recently, and an attempt whose process died without one stays
   * open and reports as in-flight rather than borrowing a sibling's finish.
   */
  private fun assembleAttempts(
    events: List<Map<String, Any?>>,
    rejections: Map<String, RejectedOutputDiagnostic>,
  ): List<GoalPlanningLogAttempt> {
    val occurrences = mutableListOf<AttemptOccurrence>()
    val open = mutableMapOf<String, MutableList<AttemptOccurrence>>()

    events.forEach { event ->
      val operation = event["operation_name"] as? String ?: return@forEach
      when (event["event_kind"]) {
        OPERATION_STARTED -> {
          val occurrence = AttemptOccurrence(operation, timestamp(event))
          occurrences += occurrence
          open.getOrPut(operation) { mutableListOf() } += occurrence
        }

        OPERATION_COMPLETED -> {
          val pending = open[operation]?.removeLastOrNull()
            // A completion whose start fell outside the retained events is still a real attempt; it
            // just has no measurable interval.
            ?: AttemptOccurrence(operation, startedAt = null).also { occurrences += it }
          pending.settle(timestamp(event), event["outcome"] as? String)
        }
      }
    }

    // Each resumed run restarts its progress sequence at zero, so ledger order interleaves segments.
    // Only the timestamps totally order attempts across a goal that blocked and was relaunched.
    return occurrences.mapNotNull { occurrence ->
      val parsed = parseOperation(occurrence.operation) ?: return@mapNotNull null
      val rejection = rejections[rejectionKey(parsed.diagnosticPhaseId, parsed.attempt)]
      GoalPlanningLogAttempt(
        phaseId = parsed.diagnosticPhaseId,
        subtaskId = parsed.subtaskId,
        attempt = parsed.attempt,
        startedAt = occurrence.startedAt,
        finishedAt = occurrence.finishedAt,
        outcome = occurrence.outcome,
        rule = rejection?.rule,
        reason = rejection?.reason,
        agentId = rejection?.agentId,
        rejectedOutputIdentity = rejection?.identity,
        rejectedOutputBytes = rejection?.byteSize,
      )
    }.sortedBy { attempt -> attempt.startedAt ?: attempt.finishedAt ?: Instant.EPOCH }
  }

  /**
   * One start-to-completion interval for an operation name that repeats across resumed runs.
   *
   * Holds the two values the log reads from a completion rather than the event itself: the interval
   * and the outcome are what an attempt is, and keeping the raw event here would carry the whole
   * progress row into a projection that never reads the rest of it.
   */
  private class AttemptOccurrence(val operation: String, val startedAt: Instant?) {
    var finishedAt: Instant? = null
      private set
    var outcome: String = OUTCOME_IN_FLIGHT
      private set

    fun settle(finishedAt: Instant?, outcome: String?) {
      this.finishedAt = finishedAt
      // A completion that names no outcome is no more settled than an absent one.
      this.outcome = outcome ?: OUTCOME_IN_FLIGHT
    }
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
