package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.normalizeIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
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
      val phaseRecord = phaseRecordFor(request, previous, now)
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

  @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest, dbOverride: String? = null): Boolean {
    require(request.status == "completed" && request.finished)
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingRecords = phaseRecordsFrom(artifacts)
      val updatedRecords = LinkedHashMap(existingRecords).apply {
        put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString()))
      }
      val ledger = phaseLedgerFrom(artifacts)
      val completion = FeatureTaskRuntimePhaseLedgerEntry(
        action = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = Instant.now().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        ledger.map { it.toArtifactMap() },
        completion.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      val outputProduced = request.normalizedOutput
        ?.envelope
        ?.get("produced_outputs")
        ?.let(JsonSupport::anyToStringAnyMap)
      val priorAuditState = artifacts[FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY]?.let {
        auditRepairStateFromWire(it, FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY)
      }
      val latestPlan = outputProduced?.get("audit_repair_plan")
        ?.takeIf { request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT }
        ?.let { auditRepairPlanFromWire(it, "audit.produced_outputs.audit_repair_plan") }
      val repairResults = (outputProduced?.get("repair_item_results") as? List<*>)
        ?.takeIf {
          request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
            request.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
        }
        ?.mapIndexed { index, value -> repairItemResultFromWire(value, "implement.repair_item_results[$index]") }
      val currentDispositions = (outputProduced?.get("prior_gap_dispositions") as? List<*>)
        ?.takeIf { request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT }
        ?.mapIndexed { index, value -> priorGapDispositionFromWire(value, "audit.prior_gap_dispositions[$index]") }
      val reconcilesAuditState = request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
        priorAuditState != null
      val auditRepairPatch = if (latestPlan != null || repairResults != null || currentDispositions != null ||
        reconcilesAuditState
      ) {
        mapOf(
          FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY to auditRepairStateToWire(
            reconcileAuditRepairState(
              AuditRepairReconciliation(
                prior = priorAuditState,
                latestPlan = latestPlan,
                repairResults = repairResults.orEmpty(),
                dispositions = currentDispositions,
                repositoryFingerprint = request.repositoryFingerprint,
                edgeIteration = request.edgeIteration,
              ),
            ),
          ),
        )
      } else {
        emptyMap()
      }
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger,
        ) + auditRepairPatch,
        WorkflowRowAdvance(request.phaseId, workflowStatusFor(request), stepUpdatesFrom(updatedRecords)),
      )
      true
    }
  }

  private data class AuditRepairReconciliation(
    val prior: FeatureTaskRuntimeAuditRepairState?,
    val latestPlan: FeatureTaskRuntimeAuditRepairPlan?,
    val repairResults: List<FeatureTaskRuntimeRepairItemResult>,
    val dispositions: List<FeatureTaskRuntimePriorGapDisposition>?,
    val repositoryFingerprint: String?,
    val edgeIteration: Int?,
  )

  private data class GapReconciliation(
    val dispositions: List<FeatureTaskRuntimePriorGapDisposition>,
    val recurringIds: Set<String>,
    val latestIds: Set<String>,
    val unresolvedGaps: List<FeatureTaskRuntimeUnresolvedGap>,
  )

  private fun reconcileAuditRepairState(input: AuditRepairReconciliation): FeatureTaskRuntimeAuditRepairState {
    val acceptedPlans = listOfNotNull(input.latestPlan ?: input.prior?.acceptedPlans?.lastOrNull())
    if (acceptedPlans.isEmpty()) schemaError("Audit-repair state requires an accepted plan.")
    val gaps = reconcileUnresolvedGaps(input)
    val latestItemIds = acceptedPlans.single().gaps
      .flatMap { it.repairItems }
      .mapTo(linkedSetOf()) { it.repairItemId }
    val allResults = reconcileLatestRepairResults(
      input.prior?.repairItemResults.orEmpty(),
      input.repairResults,
      latestItemIds,
    )
    // Attempted counts every item the remediation phase was handed; resolved counts the terminal
    // results it actually returned. They diverge when a phase reports results for only part of its plan.
    val newlyAttemptedCount = if (input.repairResults.isEmpty()) 0 else latestItemIds.size
    val newlyResolvedCount = input.repairResults.size
    val priorPlanGapIds = input.prior?.unresolvedGapLedger?.unresolvedGaps.orEmpty().mapTo(linkedSetOf()) { it.gapId }
    // Only an audit write observes a fresh gap set; recomputing on the remediation write would compare
    // the audit's own already-persisted ledger against itself and clobber both counters to zero.
    val auditWrite = input.latestPlan != null
    val ledgerSize = gaps.unresolvedGaps.size
    val recurringGapCount = if (auditWrite) {
      gaps.recurringIds.size
    } else {
      (input.prior?.progress?.recurringGapCount ?: 0).coerceAtMost(ledgerSize)
    }
    val newGapCount = if (auditWrite) {
      gaps.latestIds.count { it !in priorPlanGapIds }
    } else {
      (input.prior?.progress?.newGapCount ?: 0).coerceAtMost(ledgerSize - recurringGapCount)
    }
    val progress = FeatureTaskRuntimeAuditRepairProgress(
      firstPassConvergence = false,
      recurringGapCount = recurringGapCount,
      newGapCount = newGapCount,
      attemptedRepairItemCount = (input.prior?.progress?.attemptedRepairItemCount ?: 0) + newlyAttemptedCount,
      resolvedRepairItemCount = (input.prior?.progress?.resolvedRepairItemCount ?: 0) + newlyResolvedCount,
      auditGapIterationCount = maxOf(
        input.prior?.progress?.auditGapIterationCount ?: 0,
        input.edgeIteration ?: 0,
      ),
    )
    return FeatureTaskRuntimeAuditRepairState(
      acceptedPlans = acceptedPlans,
      repairItemResults = allResults,
      priorGapDispositions = gaps.dispositions,
      unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(gaps.unresolvedGaps),
      repositoryFingerprint = input.repositoryFingerprint ?: input.prior?.repositoryFingerprint,
      progress = progress,
    )
  }

  private fun reconcileUnresolvedGaps(input: AuditRepairReconciliation): GapReconciliation {
    val priorUnresolved = input.prior?.unresolvedGapLedger?.unresolvedGaps.orEmpty()
    val priorIds = priorUnresolved.mapTo(linkedSetOf()) { it.gapId }
    val dispositions = input.dispositions ?: input.prior?.priorGapDispositions.orEmpty()
    if (input.dispositions != null && priorIds.isNotEmpty() && dispositions.map { it.gapId }.toSet() != priorIds) {
      schemaError("Audit reconciliation must disposition every prior unresolved gap exactly once.")
    }
    val resolvedIds = dispositions
      .filter { it.status == FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED }
      .mapTo(linkedSetOf()) { it.gapId }
    val recurringIds = dispositions
      .filter { it.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING }
      .mapTo(linkedSetOf()) { it.gapId }
    val latestGaps = input.latestPlan?.gaps
      ?: if (input.repairResults.isNotEmpty()) input.prior?.acceptedPlans?.lastOrNull()?.gaps.orEmpty() else emptyList()
    val latestIds = latestGaps.mapTo(linkedSetOf()) { it.gapId }
    if (!latestIds.containsAll(recurringIds) || latestIds.any(resolvedIds::contains)) {
      schemaError("Recurring gaps must retain their identities and resolved gaps cannot remain in the latest plan.")
    }
    val merged = linkedMapOf<String, FeatureTaskRuntimeUnresolvedGap>()
    priorUnresolved.filterNot { it.gapId in resolvedIds }.forEach { merged[it.gapId] = it }
    latestGaps.forEach { gap ->
      val renamed = merged.values.firstOrNull {
        it.acceptanceCriterionRef == gap.acceptanceCriterionRef && it.gapId != gap.gapId
      }
      if (renamed != null) schemaError("Recurring gap '${renamed.gapId}' was renamed to '${gap.gapId}'.")
      merged[gap.gapId] = FeatureTaskRuntimeUnresolvedGap(
        gapId = gap.gapId,
        acceptanceCriterionRef = gap.acceptanceCriterionRef,
        generation = gap.gapId.substringAfterLast('-').toIntOrNull()
          ?: schemaError("Gap '${gap.gapId}' has no numeric generation."),
      )
    }
    return GapReconciliation(
      dispositions = dispositions,
      recurringIds = recurringIds,
      latestIds = latestIds,
      unresolvedGaps = merged.values.toList(),
    )
  }

  internal fun completeGoalReviewPhase(
    completion: GoalReviewPhaseCompletionRequest,
    dbOverride: String? = null,
  ): Boolean {
    val request = validatedGoalReviewPhaseState(completion)
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val reviewArtifacts = GoalSubtaskReviewArtifactDecoder.decode(artifacts)
        ?: return@transaction false
      val completedState = reviewArtifacts.state.completeReservedPass(
        verdict = completion.verdict,
        unresolvedFindingCount = completion.unresolvedFindingCount,
        findings = completion.findings,
      )
      val passNumber = completedState.completedPassCount.toString()
      val existingRecords = phaseRecordsFrom(artifacts)
      val phaseRecord = phaseRecordFor(request, existingRecords[request.phaseId], Instant.now().toString())
      val updatedRecords = LinkedHashMap(existingRecords).apply { put(request.phaseId, phaseRecord) }
      val ledger = phaseLedgerFrom(artifacts)
      val completionEntry = FeatureTaskRuntimePhaseLedgerEntry(
        action = skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = Instant.now().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
      val updatedLedger = appendBoundedHistoryBySequence(
        ledger.map { it.toArtifactMap() },
        completionEntry.toArtifactMap(),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
      persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to completedState.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to
            (reviewArtifacts.rawResults + (passNumber to completion.rawReviewResult)),
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
            updatedRecords.mapValues { (_, value) -> value.toArtifactMap() },
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to updatedLedger,
        ),
        WorkflowRowAdvance(
          currentStepId = request.phaseId,
          workflowStatus = workflowStatusFor(request),
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
    }
  }

  private fun validatedGoalReviewPhaseState(
    completion: GoalReviewPhaseCompletionRequest,
  ): FeatureTaskRuntimePhaseStateRequest {
    val request = completion.phaseState
    require(request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      "Goal review completion can only persist the review phase."
    }
    require(request.status == "completed" && request.finished) {
      "Goal review completion must persist a finished completed review phase."
    }
    require(completion.rawReviewResult.isNotBlank()) { "Goal-subtask review pass result must be non-blank." }
    return request
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

  fun loadAuditRepairState(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditRepairState? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      val artifact = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY]
        ?: return@read null
      auditRepairStateFromWire(artifact, FEATURE_TASK_RUNTIME_AUDIT_REPAIR_STATE_ARTIFACT_KEY)
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
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val normalizedIssueKey = normalizeIssueKey(issueKey)
    val existing = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)
    if (existing != null) {
      val persistedIssueKey = existing.issueKey
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::normalizeIssueKey)
      if (
        persistedIssueKey != null &&
        normalizedIssueKey != null &&
        persistedIssueKey != normalizedIssueKey
      ) {
        throw WorkflowIssueKeyConflictError(workflowId, persistedIssueKey, normalizedIssueKey)
      }
      if (persistedIssueKey == null && normalizedIssueKey != null) {
        unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(
          existing.copy(issueKey = normalizedIssueKey, sessionId = existing.sessionId.ifBlank { sessionId }),
        )
      } else if (existing.sessionId.isBlank()) {
        unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(existing.copy(sessionId = sessionId))
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

  private fun phaseRecordFor(
    request: FeatureTaskRuntimePhaseStateRequest,
    previous: FeatureTaskRuntimePhaseRecord?,
    now: String,
  ): FeatureTaskRuntimePhaseRecord {
    val firstStartedAt = previous?.firstStartedAt ?: now
    val startedAt = if (request.status == STATUS_RUNNING || previous == null) now else previous.startedAt
    return FeatureTaskRuntimePhaseRecord(
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
      failureDisposition = request.failureDisposition,
      fileManifestBefore = request.fileManifestBefore,
      fileManifestAfter = request.fileManifestAfter,
      fileManifestIntroduced = request.fileManifestIntroduced,
      loopId = request.loopId,
      edgeIteration = request.edgeIteration,
      reviewPassNumber = request.reviewPassNumber,
    )
  }

  private companion object {
    const val STATUS_RUNNING = "running"
  }
}

internal fun reconcileLatestRepairResults(
  priorResults: List<FeatureTaskRuntimeRepairItemResult>,
  currentResults: List<FeatureTaskRuntimeRepairItemResult>,
  latestPlanItemIds: Set<String>,
): List<FeatureTaskRuntimeRepairItemResult> = linkedMapOf<String, FeatureTaskRuntimeRepairItemResult>().apply {
  priorResults.forEach { put(it.repairItemId, it) }
  currentResults.forEach { put(it.repairItemId, it) }
  keys.retainAll(latestPlanItemIds)
}.values.toList()

internal data class GoalReviewPhaseCompletionRequest(
  val phaseState: FeatureTaskRuntimePhaseStateRequest,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val rawReviewResult: String,
)

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

private fun durationMillis(startedAt: String, finishedAt: String): Long =
  Duration.between(Instant.parse(startedAt), Instant.parse(finishedAt)).toMillis().coerceAtLeast(0)
