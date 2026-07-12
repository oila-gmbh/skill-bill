package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.normalizeIssueKey
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.application.workflow.toRecord
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import java.time.Duration
import java.time.Instant

/**
 * Application-layer write/read seam for feature-task-runtime per-phase records and the
 * append-only phase ledger. Timestamps and durations are always minted here from the runtime
 * clock, never taken from agent-reported values.
 */
@Inject
@Suppress("TooManyFunctions") // cohesive durable read/write seam for per-phase records, briefings, and the ledger
class FeatureTaskRuntimePhaseRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  /**
   * Persists one per-phase record. A `running` transition for a new attempt re-mints
   * `started_at` so `duration_millis` measures only the current run (never spanning a
   * resume gap), while `first_started_at` preserves the original first-started timestamp.
   * A finishing call mints `finished_at` and derives `duration_millis` from the re-minted
   * `started_at`. A `blocked` status persists a durable terminal record (with the blocked
   * reason) so blocked-ness survives ledger pruning. The coarse workflow row is advanced to
   * the active phase and the matching workflow status. Returns true when the workflow row
   * exists and was updated.
   */
  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val now = Instant.now().toString()
      val previous = existingRecords[request.phaseId]
      val firstStartedAt = previous?.firstStartedAt ?: now
      // Re-mint started_at on every running transition so duration measures the current run.
      // A finishing/blocked write keeps the running attempt's started_at to time only this run.
      val startedAt = if (request.status == STATUS_RUNNING || previous == null) now else previous.startedAt
      val phaseRecord = FeatureTaskRuntimePhaseRecord(
        phaseId = request.phaseId,
        status = request.status,
        attemptCount = request.attemptCount,
        startedAt = startedAt,
        firstStartedAt = firstStartedAt,
        finishedAt = if (request.finished) now else null,
        durationMillis = if (request.finished) durationMillis(startedAt, now) else null,
        resolvedAgentId = request.resolvedAgentId,
        outputArtifact = request.outputArtifact,
        blockedReason = request.blockedReason,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
      val updatedRecords = LinkedHashMap(existingRecords).apply { put(request.phaseId, phaseRecord) }
      val patch = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
      )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        patch,
        WorkflowRowAdvance(
          currentStepId = request.phaseId,
          workflowStatus = workflowStatusFor(request),
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
    }

  /**
   * Durably drops the backward-edge context (loop_id + edge_iteration) from the named phase records
   * without otherwise mutating them. Used when a wider backward edge restarts a nested loop: the
   * nested loop's per-phase watermark is stale for the new outer iteration, so it must be cleared at
   * the durable source of truth or resume reconstruction would re-import the pre-reset count and deny
   * the fresh per-iteration budget. Phases without a record (or already context-free) are skipped.
   * Returns true when the workflow row exists.
   */
  fun clearBackwardEdgeContext(workflowId: String, phaseIds: Collection<String>, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction false
      val existingRecords = phaseRecordsFrom(decodeArtifacts(record.artifactsJson))
      val cleared = LinkedHashMap(existingRecords)
      phaseIds.forEach { phaseId ->
        val previous = existingRecords[phaseId] ?: return@forEach
        if (previous.loopId == null && previous.edgeIteration == null) {
          return@forEach
        }
        cleared[phaseId] = previous.copy(loopId = null, edgeIteration = null)
      }
      if (cleared == existingRecords) {
        return@transaction true
      }
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            cleared.mapValues { (_, value) -> value.toArtifactMap() },
        ),
      )
      true
    }

  /**
   * Persists the assembled per-phase launch briefing keyed by phase id; the latest briefing
   * per phase replaces the prior one. Returns true when the workflow row exists and was updated.
   */
  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existingBriefings = phaseBriefingsFrom(artifacts)
    val updatedBriefings = LinkedHashMap(existingBriefings).apply { put(briefing.phaseId, briefing) }
    val patch = mapOf(
      FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY to
        updatedBriefings.mapValues { (_, value) -> value.toArtifactMap() },
    )
    persistPatch(unitOfWork.workflowStates, record, patch)
    true
  }

  /**
   * Strict read of the per-phase briefings keyed by phase id; an absent key yields an empty
   * map and a malformed entry loud-fails. Returns null only when the workflow row is absent.
   */
  fun loadPhaseBriefings(
    workflowId: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@read null
    phaseBriefingsFrom(decodeArtifacts(record.artifactsJson))
  }

  /**
   * Appends one phase ledger entry, minting the timestamp and assigning the next monotonic
   * sequence from the persisted max. Returns true when the workflow row exists and was updated.
   */
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingEntries = phaseLedgerFrom(artifacts)
      val nextSequence = (existingEntries.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
      val entry = FeatureTaskRuntimePhaseLedgerEntry(
        action = request.action,
        sequenceNumber = nextSequence,
        timestamp = Instant.now().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        fixLoopIteration = request.fixLoopIteration,
        blockedReason = request.blockedReason,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        existing = existingEntries.map { it.toArtifactMap() },
        entry = entry.toArtifactMap(),
        retentionLimit = FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger),
      )
      true
    }

  /**
   * Persists the run-scoped resolved feature branch exactly once. Idempotent and non-divergent:
   * when a branch is already persisted this is a no-op (returns true) and never overwrites it, so a
   * resume/re-run can never force a second or divergent branch for the same run. Returns true when
   * the workflow row exists; false only when the row is absent.
   */
  fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    if (resolvedBranchFrom(artifacts) != null) {
      return@transaction true
    }
    persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to resolvedBranch.toArtifactMap()),
    )
    true
  }

  /**
   * Strict read of the run-scoped resolved feature branch. Returns null when the workflow row is
   * absent or no branch has been resolved yet; a malformed entry loud-fails.
   */
  fun loadResolvedBranch(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeResolvedBranch? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      resolvedBranchFrom(decodeArtifacts(record.artifactsJson))
    }

  /**
   * Strict read of the per-phase records keyed by phase id; an absent key yields an empty map
   * and a malformed record loud-fails. Returns null only when the workflow row is absent.
   */
  fun loadPhaseRecords(workflowId: String, dbOverride: String? = null): Map<String, FeatureTaskRuntimePhaseRecord>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseRecordsFrom(decodeArtifacts(record.artifactsJson))
    }

  /**
   * Strict read of the append-only phase ledger. A block is recorded both as a durable terminal
   * per-phase record (so blocked-ness survives ledger pruning) and as a ledger entry; this read
   * supplies the supplementary per-attempt detail. Absent key yields an empty list; a malformed
   * entry loud-fails. Returns null only when the workflow row is absent.
   */
  fun loadPhaseLedger(workflowId: String, dbOverride: String? = null): List<FeatureTaskRuntimePhaseLedgerEntry>? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      phaseLedgerFrom(decodeArtifacts(record.artifactsJson))
    }

  /** Reads a workflow row's mode without throwing on a foreign mode, unlike [WorkflowFamily.TASK_RUNTIME.get]. */
  fun existingWorkflowMode(workflowId: String, dbOverride: String? = null): FeatureTaskWorkflowMode? =
    database.read(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.mode
    }

  /**
   * Ensures a runtime workflow row exists, opening one at the definition's initial step when
   * absent. Idempotent: a no-op when a row already exists.
   */
  fun ensureWorkflowOpen(
    workflowId: String,
    sessionId: String,
    dbOverride: String? = null,
    issueKey: String? = null,
  ): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      val normalizedIssueKey = normalizeIssueKey(issueKey)
      val existing = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)
      if (existing != null) {
        if (existing.issueKey == null && normalizedIssueKey != null) {
          unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(existing.copy(issueKey = normalizedIssueKey))
        }
        return@transaction true
      }
      val opened = engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        workflowId,
        sessionId,
        WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
      )
      WorkflowFamily.TASK_RUNTIME.saveRecord(
        unitOfWork.workflowStates,
        opened.toRecord().copy(issueKey = normalizedIssueKey),
      )
      true
    }

  private fun persistPatch(
    workflowStates: WorkflowStateRepository,
    record: WorkflowStateSnapshot,
    patch: Map<String, Any?>,
    advance: WorkflowRowAdvance = WorkflowRowAdvance.keepFrom(record),
  ) {
    // The per-phase records map is the detailed source of truth; the coarse workflow row AND the
    // shared per-step steps[] are advanced to agree with it so the generic workflow
    // get/list/latest and the resume gate do not disagree with FeatureTaskRuntimeStatusService.
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = advance.workflowStatus,
        currentStepId = advance.currentStepId,
        stepUpdates = advance.stepUpdates,
        artifactsPatch = patch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  }

  private companion object {
    const val STATUS_RUNNING = "running"
  }
}

// How the coarse workflow row + shared steps[] advance alongside a per-phase record write. Grouping
// these together keeps persistPatch a three-argument seam; the default keeps the row untouched for
// writes (briefings, ledger, resolved branch) that only patch artifacts.
private data class WorkflowRowAdvance(
  val currentStepId: String,
  val workflowStatus: String,
  val stepUpdates: List<Map<String, Any?>>? = null,
) {
  companion object {
    fun keepFrom(record: WorkflowStateSnapshot): WorkflowRowAdvance =
      WorkflowRowAdvance(currentStepId = record.currentStepId, workflowStatus = record.workflowStatus)
  }
}

// Projects the per-phase records map onto shared per-step step_updates so steps[] tracks records
// in lockstep: each record's runtime status maps to its step status and carries its attempt count.
// The engine's mergeStepUpdates preserves definition order and leaves unmentioned steps untouched,
// so prior completed phases keep their completed step and only the touched phases are rewritten.
//
// Phase statuses share the step-status vocabulary (running/completed/blocked): a blocked record
// stays blocked even when it also carries a finished timestamp; otherwise a finished record is
// completed. An unrecognized status loud-fails rather than silently producing an out-of-vocabulary
// step status the engine would reject.
private fun stepUpdatesFrom(records: Map<String, FeatureTaskRuntimePhaseRecord>): List<Map<String, Any?>> {
  fun stepStatusFor(record: FeatureTaskRuntimePhaseRecord): String = when {
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
    record.finishedAt != null -> "completed"
    record.status == "running" || record.status == "completed" -> record.status
    else -> throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime phase '${record.phaseId}' has unmappable status '${record.status}' for steps[].",
    )
  }
  return records.values.map { record ->
    linkedMapOf<String, Any?>(
      "step_id" to record.phaseId,
      "status" to stepStatusFor(record),
      "attempt_count" to record.attemptCount,
    )
  }
}

@Inject
class FeatureTaskRuntimeDecomposeTerminalRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = "completed",
        currentStepId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        stepUpdates = null,
        artifactsPatch = mapOf(FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY to terminal.toArtifactMap()),
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
    true
  }

  fun loadDecomposeTerminal(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeDecomposeTerminal? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      decomposeTerminalFrom(decodeArtifacts(record.artifactsJson))
    }
}

// Coarse workflow-row status mirrors the phase transition: a blocked phase blocks the row, the
// final phase completing completes it, every other transition keeps it running. The per-phase
// records map remains the detailed source of truth.
private fun workflowStatusFor(request: FeatureTaskRuntimePhaseStateRequest): String = when {
  request.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED -> "blocked"
  request.finished && request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.last() ->
    "completed"
  else -> "running"
}

private fun schemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

// Strict decode of a keyed artifact map. Corrupt state loud-fails rather than being coerced to
// empty, which would otherwise turn it into a blind re-run / lost outputs on resume.
private fun <T> decodeStrictKeyedArtifactMap(
  artifacts: Map<String, Any?>,
  artifactKey: String,
  decodeEntry: (String, Map<String, Any?>) -> T,
): Map<String, T> {
  val raw = artifacts[artifactKey] ?: return emptyMap()
  val rawMap = raw as? Map<*, *>
    ?: schemaError("Feature-task-runtime artifact '$artifactKey' must decode to a map.")
  return rawMap.entries.associate { (key, value) ->
    val phaseId = key as? String
      ?: schemaError("Feature-task-runtime artifact '$artifactKey' must have string keys; found '$key'.")
    val entryMap = JsonSupport.anyToStringAnyMap(value)
      ?: schemaError("Feature-task-runtime artifact '$artifactKey' entry for '$phaseId' must decode to a map.")
    phaseId to decodeEntry(phaseId, entryMap)
  }
}

private fun phaseRecordsFrom(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseRecord> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY) { _, recordMap ->
    FeatureTaskRuntimePhaseRecord.fromArtifactMap(recordMap)
  }

private fun phaseBriefingsFrom(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseLaunchBriefing> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY) { _, briefingMap ->
    FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(briefingMap)
  }

private fun resolvedBranchFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY] ?: return null
  val entryMap = JsonSupport.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeResolvedBranch.fromArtifactMap(entryMap)
}

private fun decomposeTerminalFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeDecomposeTerminal? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY] ?: return null
  val entryMap = JsonSupport.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap(entryMap)
}

private fun phaseLedgerFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
  val raw = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] ?: return emptyList()
  val rawList = raw as? List<*>
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY' must decode to a list.",
    )
  return rawList.map { item ->
    val entryMap = JsonSupport.anyToStringAnyMap(item)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime phase ledger entry must decode to a string-keyed map.",
      )
    FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entryMap)
  }
}

private fun durationMillis(startedAt: String, finishedAt: String): Long =
  Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).toMillis().coerceAtLeast(0)
