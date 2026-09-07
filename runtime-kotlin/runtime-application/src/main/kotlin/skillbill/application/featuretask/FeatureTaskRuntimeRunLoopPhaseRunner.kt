package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.GoalSubtaskReviewInputBlocked
import skillbill.application.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.application.featuretask.model.GoalSubtaskReviewInputReady
import skillbill.application.featuretask.model.GoalSubtaskReviewPassCarryForward
import skillbill.application.featuretask.model.GoalSubtaskReviewPassInFlight
import skillbill.application.featuretask.model.GoalSubtaskReviewPassReservation
import skillbill.application.featuretask.model.GoalSubtaskReviewPassReserved
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

object FeatureTaskRuntimeRunLoopPhaseRunner {
  fun declaredCriterionRefs(runLoop: FeatureTaskRuntimeRunLoop): List<String> =
    acceptanceCriterionRefsFor(runLoop.request.runInvariants.acceptanceCriteria.size)

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
  ): PhaseOutcome {
    val prepared = FeatureTaskRuntimeRunLoopReview.prepareRuntimeOwnedReview(runLoop, run, state)
    return when (prepared) {
      is RuntimeOwnedReviewBlocked -> prepared.outcome
      is RuntimeOwnedReviewReady -> {
        FeatureTaskRuntimeRunLoopLaunch.prepareLaunchForCapture(runLoop, prepared.run, state, null)
        FeatureTaskRuntimeRunLoopReview.executePreparedReviewDriver(runLoop, prepared, observability)
      }
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
      if (FeatureTaskRuntimeRunLoopPhaseRunner.shouldRelaunchPersistedBlock(
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
      state.auditGapPlanningContextError?.let { reason -> PreLaunchBlock(state.nextIteration(run.phaseId), reason) }
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
    return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersist(
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

  fun isRemovedGoalReviewSchemaGateBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass")

  fun isRemovedImplementationContinuationBudgetBlock(phaseId: String, reason: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      "exhausted the bounded implementation-continuation budget" in reason

  fun isReenterableLaunchSeamRecordRejection(phaseId: String, reason: String): Boolean =
    reason.contains(LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION) &&
      FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER.containsKey(phaseId)

  fun isReenterableRecordRejection(state: FeatureTaskRuntimeRunState, phaseId: String, reason: String): Boolean =
    isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
      state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

  fun shouldRelaunchPersistedBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    durable: FeatureTaskRuntimePhaseRecord?,
    persistedReason: String,
  ): Boolean {
    val retryReviewPreparation = FeatureTaskRuntimeRunLoopPhaseRunner.isRetryableGoalReviewPreparation(
      phaseId,
      persistedReason,
    ) ||
      state.legacyReviewPreparationRetryConsumedBudget(phaseId, persistedReason)
    val reenterableRecordRejection = isReenterableRecordRejection(state, phaseId, persistedReason)
    val removedContinuationBudget =
      FeatureTaskRuntimeRunLoopPhaseRunner.isRemovedImplementationContinuationBudgetBlock(phaseId, persistedReason)
    val restartsBudget = listOf(
      retryReviewPreparation,
      reenterableRecordRejection,
      removedContinuationBudget,
      FeatureTaskRuntimeRunLoopPhaseAttempts.operatorReopenedPhase(runLoop, phaseId),
    ).any { it }
    if (restartsBudget) {
      state.restartAttemptBudget(phaseId)
    }
    return shouldRetryPersistedBlock(
      runLoop,
      ShouldRetryPersistedBlockArgs(
        phaseId = phaseId,
        durable = durable,
        retryReviewPreparation = retryReviewPreparation,
        reenterableRecordRejection = reenterableRecordRejection,
        persistedReason = persistedReason,
      ),
    )
  }

  internal fun shouldRetryPersistedBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ShouldRetryPersistedBlockArgs,
  ): Boolean {
    val phaseId = args.phaseId
    val durable = args.durable
    val retryReviewPreparation = args.retryReviewPreparation
    val reenterableRecordRejection = args.reenterableRecordRejection
    val persistedReason = args.persistedReason
    val disposition = durable?.failureDisposition
    return when {
      FeatureTaskRuntimeRunLoopPhaseAttempts.operatorReopenedPhase(runLoop, phaseId) -> true
      retryReviewPreparation -> true
      reenterableRecordRejection -> true
      FeatureTaskRuntimeRunLoopPhaseRunner.isRemovedGoalReviewSchemaGateBlock(phaseId, persistedReason) -> true
      FeatureTaskRuntimeRunLoopPhaseRunner.isRemovedImplementationContinuationBudgetBlock(
        phaseId,
        persistedReason,
      ) -> true
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        persistedReason.startsWith("Audit-gap recovery requires") -> true
      disposition != null -> disposition.retryOnResume
      else -> FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(phaseId)
    }
  }

  internal fun prepareGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = when {
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> GoalReviewRunReady(run)
    FeatureTaskRuntimeRunLoopOutputPersistence.isGoalReviewRun(run) ->
      FeatureTaskRuntimeRunLoopPhaseRunner.reserveGoalReviewRun(runLoop, run, observability)
    else -> prepareStandaloneReviewRun(runLoop, run, observability)
  }

  internal fun prepareStandaloneReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation {
    val resolved = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
      ?: return FeatureTaskRuntimeRunLoopPhaseRunner.blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        "Standalone review is missing its durable resolved branch.",
      )
    val reviewBaseSha = resolved.reviewBaseSha
      ?: return FeatureTaskRuntimeRunLoopPhaseRunner.blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        "Standalone review is missing the immutable review base captured before implementation.",
      )
    val result = runLoop.phaseGates.gitOperations.buildGoalSubtaskReviewInput(
      run.request.repoRoot,
      FeatureTaskRuntimeScopedReviewBaseline.of(
        runLoop.phaseGates.gitOperations,
        run.request.repoRoot,
        resolved,
        reviewBaseSha,
      ),
      resolved.branch,
    )
    val input = result.input
      ?: return FeatureTaskRuntimeRunLoopPhaseRunner.blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        result.error.ifBlank { "Standalone review input failed." },
      )
    return GoalReviewRunReady(run.copy(goalReviewInput = input))
  }

  fun scopedReviewUntrackedExclusions(
    runLoop: FeatureTaskRuntimeRunLoop,
    resolved: FeatureTaskRuntimeResolvedBranch,
  ): List<String> = FeatureTaskRuntimeScopedReviewBaseline.untrackedExclusions(
    runLoop.phaseGates.gitOperations,
    runLoop.request.repoRoot,
    resolved,
  )

  internal fun reserveGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    runLoop.goalContinuationRecorder.reserveGoalReviewPass(run.request.workflowId, run.request.dbPathOverride)
  }.fold(
    onSuccess = { reservation ->
      when (reservation) {
        GoalSubtaskReviewPassReservation.MissingState -> blockedGoalReviewRun(
          runLoop,
          run,
          observability,
          "Goal-subtask review runLoop.state is missing; review_base_sha must be captured before implementation " +
            "and cannot be substituted.",
        )
        is GoalSubtaskReviewPassCarryForward -> GoalReviewRunPreparation.CarryForward
        is GoalSubtaskReviewPassInFlight,
        is GoalSubtaskReviewPassReserved,
        -> buildGoalReviewRun(runLoop, run, observability)
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        goalReviewPreparationFailure("reservation", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

  internal fun buildGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
  ): GoalReviewRunPreparation = runCatching {
    val resolved = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    runLoop.goalContinuationRecorder.buildGoalReviewInput(
      workflowId = run.request.workflowId,
      gitOperations = runLoop.phaseGates.gitOperations,
      repoRoot = run.request.repoRoot,
      scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(
        dbOverride = run.request.dbPathOverride,
        scopedUntrackedExclusions = resolved?.let {
          FeatureTaskRuntimeRunLoopPhaseRunner.scopedReviewUntrackedExclusions(runLoop, it)
        },
        ownedPathspec = resolved?.workflowOwnedPaths.orEmpty(),
      ),
    )
  }.fold(
    onSuccess = { prepared ->
      when (prepared) {
        GoalSubtaskReviewInputPreparation.MissingState -> {
          blockedGoalReviewRun(
            runLoop,
            run,
            observability,
            "Goal-subtask review runLoop.state disappeared before review launch.",
          )
        }
        is GoalSubtaskReviewInputBlocked -> {
          blockedGoalReviewRun(runLoop, run, observability, prepared.reason)
        }
        is GoalSubtaskReviewInputReady ->
          GoalReviewRunReady(run.copy(goalReviewInput = prepared.input))
      }
    },
    onFailure = { error ->
      blockedGoalReviewRun(
        runLoop,
        run,
        observability,
        goalReviewPreparationFailure("input persistence", error),
        goalReviewPreparationDisposition(error),
      )
    },
  )

  fun goalReviewPreparationFailure(stage: String, error: Throwable): String {
    val location = error.stackTrace.firstOrNull { frame -> frame.className.startsWith("skillbill.") }
      ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
      .orEmpty()
    return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
  }

  fun goalReviewPreparationDisposition(error: Throwable): FeatureTaskRuntimeFailureDisposition =
    if ("[SQLITE_BUSY]" in error.message.orEmpty()) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }

  internal fun blockedGoalReviewRun(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    reason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): GoalReviewRunPreparation {
    FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersist(
      runLoop,
      BlockAndPersistArgs(
        run = run,
        attemptCount = 1,
        reason = reason,
        observability = observability,
        loopId = null,
        edgeIteration = null,
        failureDisposition = failureDisposition,
        payload = BlockAndPersistPayload(),
      ),
    )
    return GoalReviewRunPreparation.Blocked(reason, failureDisposition)
  }

  internal fun settleCarriedForwardGoalReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = loadCarriedForwardGoalReviewOutput(runLoop, run).getOrElse { error ->
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersist(
        runLoop,
        carriedForwardMissingReviewBlock(run, state, observability, error),
      )
    }
    val normalizedOutput = acceptedOutput.normalizedOutput
    val iteration = state.nextIteration(run.phaseId)
    val phaseState = FeatureTaskRuntimeRunLoopOutputPersistence.phaseStateRequest(
      runLoop,
      PhaseStateRequestArgs(
        write = PhaseStateWriteArgs(
          run = run,
          iteration = iteration,
          status = STATUS_COMPLETED,
          finished = true,
          outputArtifact = normalizedOutput.canonicalJson,
        ),
        extras = PhaseStateRequestAttachments(
          normalizedOutput = normalizedOutput,
          repairEvidence = acceptedOutput.repairEvidence,
        ),
      ),
    )
    state.reserveReviewPass(phaseState.reviewPassNumber)
    carriedForwardReviewPersistenceFailure(runLoop, phaseState, run)?.let { failure ->
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersist(
        runLoop,
        BlockAndPersistArgs(
          run = run,
          attemptCount = iteration,
          reason = failure,
          observability = observability,
          loopId = null,
          edgeIteration = null,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          payload = BlockAndPersistPayload(
            normalizedOutput = normalizedOutput,
            outputArtifact = normalizedOutput.canonicalJson,
            repairEvidence = acceptedOutput.repairEvidence,
          ),
        ),
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
    )
  }

  internal fun carriedForwardReviewPersistenceFailure(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    run: PhaseRun,
  ): String? {
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return runCatching {
      runLoop.recorder.recordCompletedPhase(phaseState, run.request.dbPathOverride)
    }.fold(
      onSuccess = { persisted -> if (persisted) null else prefix },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

  internal fun loadCarriedForwardGoalReviewOutput(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun) = runCatching {
    val output = runLoop.goalContinuationRecorder.lastGoalReviewResult(
      run.request.workflowId,
      run.request.dbPathOverride,
    )
      ?: throw MissingCarriedForwardGoalReviewResultException()
    runLoop.outputValidator.validatePhaseOutput(output, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
  }

  internal fun carriedForwardMissingReviewBlock(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    error: Throwable,
  ): BlockAndPersistArgs {
    val detail = if (error is MissingCarriedForwardGoalReviewResultException) {
      "missing."
    } else {
      "malformed: ${error.message.orEmpty()}"
    }
    return BlockAndPersistArgs(
      run = run,
      attemptCount = state.nextIteration(run.phaseId),
      reason = "Goal-subtask review pass budget is exhausted but its durable raw " +
        "review result is $detail",
      observability = observability,
      loopId = null,
      edgeIteration = null,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      payload = BlockAndPersistPayload(),
    )
  }
}
