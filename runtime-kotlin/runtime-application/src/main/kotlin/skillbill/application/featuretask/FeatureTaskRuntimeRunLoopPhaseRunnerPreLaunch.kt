package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

internal fun FeatureTaskRuntimeRunLoop.preLaunchBlock(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome? {
  val persisted = state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
    val nextIteration = state.nextIteration(run.phaseId)
    val durable = state.recordFor(run.phaseId)
    if (shouldRelaunchPersistedBlock(state, run.phaseId, durable, persistedReason)) {
      return@let null
    }
    val reason = persistedReason.ifBlank {
      "Phase '${run.phaseId}' is durably blocked from a prior run; the runtime re-blocks rather than relaunching."
    }
    PreLaunchBlock(nextIteration, reason, durable)
  }
  val invalidPlanningContext = if (
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
    run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
  ) {
    state.auditGapPlanningContextError()?.let { reason -> PreLaunchBlock(state.nextIteration(run.phaseId), reason) }
  } else {
    null
  }
  val missing = persisted ?: invalidPlanningContext
    ?: missingRequiredUpstream(run, state)?.let { missingIds ->
      PreLaunchBlock(
        1,
        "Phase '${run.phaseId}' requires upstream output(s) ${missingIds.joinToString()} that are not " +
          "present; the runtime blocks rather than launching the phase blind.",
      )
    }
  return missing?.let { preLaunch ->
    val durable = preLaunch.durableRecord
    blockAndPersist(
      BlockAndPersistArgs(
        run = run,
        attemptCount = preLaunch.attemptCount,
        reason = preLaunch.reason,
        observability = observability,
        loopId = durable?.loopId,
        edgeIteration = durable?.edgeIteration,
        failureDisposition = durable?.failureDisposition
          ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        payload = BlockAndPersistPayload(
          fileManifest = durable?.let {
            FeatureTaskRuntimePhaseFileManifest(it.fileManifestBefore, it.fileManifestAfter)
          },
          outputArtifact = durable?.outputArtifact,
          rejectedOutput = durable?.rejectedOutput,
        ),
      ),
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.missingRequiredUpstream(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
): List<String>? {
  val recoverableAuditRepairSource =
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
      run.reentry.reentryGapCriteria.isNotEmpty()
  return missingUpstream(run.declaration, state.outputs())
    ?.filterNot {
      recoverableAuditRepairSource && it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
    }
    ?.takeIf(List<String>::isNotEmpty)
}

internal fun FeatureTaskRuntimeRunLoop.isRetryableGoalReviewPreparation(phaseId: String, reason: String): Boolean {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return false
  val legacyDatabaseContention =
    reason.startsWith("Goal-subtask review state or durable raw evidence is malformed:") &&
      "[SQLITE_BUSY]" in reason
  return legacyDatabaseContention ||
    "[SQLITE_BUSY]" in reason && (
      reason.startsWith("Goal-subtask review reservation failed") ||
        reason.startsWith("Goal-subtask review input persistence failed")
      )
}

// The gate that wrote this reason blocked a goal review on schema-invalid output instead of retrying it,
// and persisted a terminal needs_user_action disposition. That gate is gone, so such a record is stale
// rather than terminal: the reserved pass still has no completed output, which the review schema
// correction loop decides. The remaining attempt budget is deliberately not restarted.
internal fun FeatureTaskRuntimeRunLoop.isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
  phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
    reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

// Continuation used to hard-cap at five segments and persist needs_user_action. That cap is gone, so a
// durable block naming the old budget is stale rather than terminal: resume must relaunch implement and
// keep continuing until obligations close.
internal fun FeatureTaskRuntimeRunLoop.isRemovedImplementationContinuationBudgetBlock(
  phaseId: String,
  reason: String,
): Boolean = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
  "exhausted the bounded implementation-continuation budget" in reason

// A pre-quarantine build blocked a launch-seam planning-projection rejection with a terminal
// needs_user_action disposition; the current seam instead quarantines the upstream record and
// regenerates its producer. Such a legacy row is stale, not terminal: re-enter the phase so the live
// seam routes it through the quarantine-and-regenerate edge. Matches only that one legacy phrase, and
// only where a regeneration producer exists, so every other launch-seam block and any genuinely
// unmigratable record keeps its first-occurrence durable block.
internal fun FeatureTaskRuntimeRunLoop.isReenterableLaunchSeamRecordRejection(
  phaseId: String,
  reason: String,
): Boolean = reason.contains(LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION) &&
  FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER.containsKey(phaseId)

// A launch-seam record rejection never ran the consumer, so its attempts are not real fix-loop output
// attempts. Re-enterable whether the block still carries the launch-seam reason or was already
// overwritten with the generic fix-loop-exhaustion text on a prior re-entry (recognized from the ledger).
internal fun FeatureTaskRuntimeRunLoop.isReenterableRecordRejection(
  state: FeatureTaskRuntimeRunState,
  phaseId: String,
  reason: String,
): Boolean = isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
  state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

// Decides whether a phase with a persisted block relaunches instead of re-surfacing it, restarting the
// fix-loop budget for the re-enterable stale-block classes whose prior attempts were not real semantic
// output failures (goal-review preparation retries, launch-seam record rejections, and the removed
// implementation-continuation segment cap).
internal fun FeatureTaskRuntimeRunLoop.shouldRelaunchPersistedBlock(
  state: FeatureTaskRuntimeRunState,
  phaseId: String,
  durable: FeatureTaskRuntimePhaseRecord?,
  persistedReason: String,
): Boolean {
  val retryReviewPreparation = isRetryableGoalReviewPreparation(phaseId, persistedReason) ||
    state.legacyReviewPreparationRetryConsumedBudget(phaseId, persistedReason)
  val reenterableRecordRejection = isReenterableRecordRejection(state, phaseId, persistedReason)
  val removedContinuationBudget =
    isRemovedImplementationContinuationBudgetBlock(phaseId, persistedReason)
  val restartsBudget = listOf(
    retryReviewPreparation,
    reenterableRecordRejection,
    removedContinuationBudget,
    operatorReopenedPhase(phaseId),
  ).any { it }
  if (restartsBudget) {
    state.restartAttemptBudget(phaseId)
  }
  return shouldRetryPersistedBlock(
    phaseId,
    durable,
    retryReviewPreparation,
    reenterableRecordRejection,
    persistedReason,
  )
}

internal fun FeatureTaskRuntimeRunLoop.shouldRetryPersistedBlock(
  phaseId: String,
  durable: FeatureTaskRuntimePhaseRecord?,
  retryReviewPreparation: Boolean,
  reenterableRecordRejection: Boolean,
  persistedReason: String,
): Boolean {
  val disposition = durable?.failureDisposition
  return when {
    // Ahead of every disposition check: an operator reopen is a decision about this exact block,
    // whatever its class or disposition, so no persisted reason may veto it.
    operatorReopenedPhase(phaseId) -> true
    retryReviewPreparation -> true
    reenterableRecordRejection -> true
    isRemovedGoalReviewSchemaGateBlock(phaseId, persistedReason) -> true
    isRemovedImplementationContinuationBudgetBlock(phaseId, persistedReason) -> true
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      persistedReason.startsWith("Audit-gap recovery requires") -> true
    disposition != null -> disposition.retryOnResume
    else -> FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(phaseId)
  }
}
