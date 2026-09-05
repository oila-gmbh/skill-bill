package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor

@Inject
class FeatureTaskRuntimeRunLoopPhaseRunner {
  fun declaredCriterionRefs(runLoop: FeatureTaskRuntimeRunLoop): List<String> =
    acceptanceCriterionRefsFor(runLoop.request.runInvariants.acceptanceCriteria.size)

  // Empty by construction: every audit re-decides every declared criterion against the tree, so no
  // criterion is ever durably closed against a later audit. Kept as a seam because the audit briefing
  // and the open-criteria projection both read it.
  fun durablyClosedCriterionRefs(): List<String> = emptyList()

  fun openAuditCriterionRefs(
    runLoop: FeatureTaskRuntimeRunLoop,
    closedCriterionRefs: List<String> = durablyClosedCriterionRefs(),
  ): List<String> = declaredCriterionRefs(runLoop) - closedCriterionRefs.toSet()

  internal fun runDeclaredReviewDriverCycle(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome = when (val prepared = runLoop.collaborators.review.prepareRuntimeOwnedReview(runLoop, run, state)) {
    is RuntimeOwnedReviewBlocked -> prepared.outcome
    is RuntimeOwnedReviewReady -> {
      runLoop.collaborators.launch.prepareLaunchForCapture(
        runLoop,
        prepared.run,
        state,
        state.nextIteration(prepared.run.phaseId),
        null,
      )
      runLoop.collaborators.review.executePreparedReviewDriver(runLoop, prepared, observability)
    }
  }

  internal fun preLaunchBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val persisted = state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
      val nextIteration = state.nextIteration(run.phaseId)
      val durable = state.recordFor(run.phaseId)
      if (runLoop.collaborators.phaseRunnerContinued1.shouldRelaunchPersistedBlock(
          runLoop,
          state,
          run.phaseId,
          durable,
          persistedReason,
        )
      ) {
        return@let null
      }
      val reason = persistedReason.ifBlank {
        "Phase '${run.phaseId}' is durably runLoop.session.blocked from a prior run; " +
          "the runtime re-blocks rather than relaunching."
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
    return missing?.let { persistPreLaunchBlock(runLoop, run, observability, it) }
  }

  private fun persistPreLaunchBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    preLaunch: PreLaunchBlock,
  ): PhaseOutcome {
    val durable = preLaunch.durableRecord
    return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersist(
      runLoop,
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
  internal fun missingRequiredUpstream(run: PhaseRun, state: FeatureTaskRuntimeRunState): List<String>? {
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

  fun isRetryableGoalReviewPreparation(phaseId: String, reason: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return false
    val legacyDatabaseContention =
      reason.startsWith("Goal-subtask review runLoop.state or durable raw evidence is malformed:") &&
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
  fun isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

  // Continuation used to hard-cap at five segments and persist needs_user_action. That cap is gone, so a
  // durable block naming the old budget is stale rather than terminal: resume must relaunch implement and
  // keep continuing until obligations close.
  fun isRemovedImplementationContinuationBudgetBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      "exhausted the bounded implementation-continuation budget" in reason

  // A pre-quarantine build blocked a launch-seam planning-projection rejection with a terminal
  // needs_user_action disposition; the current seam instead quarantines the upstream record and
  // regenerates its producer. Such a legacy row is stale, not terminal: re-enter the phase so the live
  // seam routes it through the quarantine-and-regenerate edge. Matches only that one legacy phrase, and
  // only where a regeneration producer exists, so every other launch-seam block and any genuinely
  // unmigratable record keeps its first-occurrence durable block.
}
