package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.GoalReviewPhaseCompletionRequest
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.UnaddressedFindingLedgerScope
import skillbill.install.model.InstallAgent
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

object FeatureTaskRuntimeRunLoopOutputPersistence {
  internal fun persistRejectedVerificationFindings(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    verifyOutput: Map<String, Any?>,
  ) {
    if (!isGoalContinuationRun(run.request)) return
    val continuation = run.request.goalContinuation ?: return
    val reviewOutput = runLoop.state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.normalizedOutput?.envelope
      ?: return
    val reviewState = runLoop.goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
    val passNumber = reviewState?.completedPassCount?.takeIf { it > 0 } ?: 1
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(reviewOutput, run.request.dbPathOverride)
    val truncationRecords = mutableListOf<String>()
    val rejected = GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
      verifyOutput = verifyOutput,
      reviewOutput = reviewOutput,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation.parentIssueKey,
        subtaskId = continuation.subtaskId,
        workflowId = run.request.workflowId,
        reviewPassNumber = passNumber,
      ),
      recordedVerdicts = recordedVerdicts,
      truncationRecords = truncationRecords,
    )
    truncationRecords.forEach { record ->
      runCatching { runLoop.diagnostics.warning(record) }
    }
    if (rejected.isEmpty()) return
    runLoop.recorder.appendRejectedVerificationFindings(
      workflowId = run.request.workflowId,
      passNumber = passNumber,
      rejected = rejected,
      dbOverride = run.request.dbPathOverride,
    )
  }

  internal fun persistStandaloneReviewCompletion(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseReviewPersistenceArgs,
    outputText: String,
    acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome? {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val fileManifest = args.fileManifest
    val persisted = try {
      runLoop.recorder.recordCompletedPhase(
        phaseStateRequest(
          runLoop,
          PhaseStateRequestArgs(
            write = PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_COMPLETED,
              finished = true,
              outputArtifact = outputText,
            ),
            extras = PhaseStateRequestAttachments(
              fileManifest = fileManifest,
              normalizedOutput = acceptedOutput.normalizedOutput,
              repairEvidence = acceptedOutput.repairEvidence,
              reviewRunId = runLoop.state.recordFor(run.phaseId)?.reviewRunId,
            ),
          ),
        ),
        run.request.dbPathOverride,
      )
    } catch (error: RuntimeOwnedFactUnavailable) {
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned review settlement could not establish its persistence fact: " +
            error.message.orEmpty(),
          observability = runLoop.observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    return if (persisted) {
      null
    } else {
      FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned review settlement could not be persisted.",
          observability = runLoop.observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
  }

  internal fun persistGoalReviewCompletion(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseReviewPersistenceArgs,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): PhaseOutcome? {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val fileManifest = args.fileManifest
    val completion = goalReviewPhaseCompletionRequest(runLoop, args, normalizedOutput, repairEvidence)
    val completed = runCatching {
      runLoop.recorder.completeGoalReviewPhase(
        completion = completion,
        dbOverride = run.request.dbPathOverride,
      )
    }.getOrElse { error ->
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          "Goal-subtask review could not atomically persist its pass and completed phase: " +
            error.message.orEmpty(),
          runLoop.observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
        ),
      )
    }
    return if (completed) {
      null
    } else {
      FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
          observability = runLoop.observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
        ),
      )
    }
  }

  internal fun isGoalReviewRun(run: PhaseRun): Boolean =
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)

  internal fun schemaInvalidAttempt(
    operatorReason: String,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    malformedOutput: Boolean = false,
    retryReason: String = operatorReason,
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
  ): AttemptResult = AttemptResult.schemaInvalid(
    SchemaInvalidArgs(
      operatorReason = operatorReason,
      fileManifest = fileManifest,
      rejectedOutput = null,
      malformedOutput = malformedOutput,
      retryReason = retryReason,
      correctiveRepairContext = correctiveRepairContext,
    ),
  )

  internal fun prepareLaunch(runLoop: FeatureTaskRuntimeRunLoop, args: PrepareLaunchArgs): PreparedLaunch {
    val run = args.run
    val state = args.state
    val priorCorrection = args.priorCorrection
    val durablyClosedCriterionRefs = args.durablyClosedCriterionRefs
    val repositoryCheckpoint = args.repositoryCheckpoint
    val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    val handoff = assembleLaunchHandoff(
      runLoop,
      AssembleLaunchHandoffArgs(run, state, durablyClosedCriterionRefs, repositoryCheckpoint, resolvedBranchRecord),
    )
    runLoop.recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
    val sharedEvidence = FeatureTaskRuntimeRunLoopOutputVerification.resolveSharedReviewEvidence(
      runLoop,
      run,
      repositoryCheckpoint,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      run.request.workflowId,
      runLoop.planningProjectionValidator,
      run.request.agentAddonSelection,
      sharedEvidence?.reference,
    )
    runLoop.recorder.recordPhaseBriefing(
      run.request.workflowId,
      briefing,
      run.request.dbPathOverride,
      sharedEvidence?.measurement,
    )
    val prompt = composeLaunchPrompt(
      runLoop,
      ComposeLaunchPromptArgs(run, state, handoff, priorCorrection, briefing),
    )
    return PreparedLaunch(briefing, prompt)
  }

  private fun assembleLaunchHandoff(runLoop: FeatureTaskRuntimeRunLoop, args: AssembleLaunchHandoffArgs) =
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration = args.run.declaration,
        runInvariants = args.run.request.runInvariants,
        recordedOutputs = args.state.outputs(),
        drivingVerdict = args.run.reentry?.drivingVerdict,
        reentryGapCriteria = emptyList(),
        priorGapMemory = FeatureTaskRuntimeRunLoopLaunch.priorGapMemoryFor(runLoop, args.run, args.state),
        durablyClosedCriterionRefs = args.durablyClosedCriterionRefs,
        repairLedger = null,
        repositoryCheckpoint = args.repositoryCheckpoint,
        expectedRepositoryCheckpoint = expectedCheckpointForLaunch(args.run, args.repositoryCheckpoint)
          ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
        branchIdentity = args.resolvedBranchRecord?.branch,
        baseBranch = args.resolvedBranchRecord?.baseBranch ?: "main",
        validationDepth = args.run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
        qualityGateSelection = FeatureTaskRuntimeRunLoopTransitions.qualityGateSelection(runLoop),
      ),
    ).copy(
      recordedFindingVerdicts = FeatureTaskRuntimeRunLoopOutputVerification.recordedFindingVerdictsForFixHandoff(
        runLoop,
        args.run,
        args.state,
      ),
    )

  private fun composeLaunchPrompt(runLoop: FeatureTaskRuntimeRunLoop, args: ComposeLaunchPromptArgs): String {
    val run = args.run
    val state = args.state
    val handoff = args.handoff
    val priorCorrection = args.priorCorrection
    val briefing = args.briefing
    val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    val passNumber = reviewPassNumber(runLoop, run, state)
    val depthResolution = passNumber?.let { pass ->
      FeatureTaskRuntimeReviewPassSequence.resolveForPass(run.request.runInvariants.codeReviewMode, pass)
    }
    val executedTier = RuntimeOwnedReviewMode.execute(
      depthResolution?.resolvedTier ?: run.request.runInvariants.codeReviewMode,
    )
    depthResolution?.let { resolution ->
      FeatureTaskRuntimeRunLoopPlanningBranch.persistResolvedReviewTier(runLoop, run, resolution)
    }
    return FeatureTaskRuntimePhasePromptComposer.compose(
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = run.request.issueKey,
        briefing = briefing,
        suppressDecomposition = isGoalContinuationRun(run.request),
        codeReviewMode = executedTier,
        reviewPassNumber = passNumber,
        goalSubtaskReviewInput = run.goalReviewInput,
        baselineUntrackedPaths = resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
        resolvedReviewTier = depthResolution?.let { executedTier },
        reviewDecidingRule = depthResolution?.decidingRule,
        repairLedger = handoff.repairLedger,
        priorReviewContext = null,
        priorSchemaFailure = priorCorrection?.schemaGateReason,
        priorTerminalFailure = priorCorrection?.retryableTerminalReason,
        priorFindingCoverage = priorCorrection?.findingCoverageReason,
        correctiveRepairContext = priorCorrection?.correctiveRepairContext,
        operatorBlockRetry = runLoop.session.operatorBlockRetry
          ?.takeIf { it.phaseId == run.phaseId && !runLoop.session.operatorBlockRetryCompleted },
        implementationContinuation =
        FeatureTaskRuntimeRunLoopOutputVerification.implementationContinuationFor(runLoop, run),
        validationGateFindings = run.validationGateFindings,
        validationGateTriagePlan = run.validationGateTriagePlan,
        validationGateRepair = run.validationGateRepair,
        validationGateTriage = run.validationGateTriage,
        agentRunValidateFallback = run.agentRunValidateFallback,
        packCollectAllCommand = FeatureTaskRuntimeRunLoopValidationGate.packCollectAllCommand(runLoop, run),
        packBuildCommand = FeatureTaskRuntimeRunLoopValidationGate.packBuildCommand(runLoop, run),
      ),
    ) + FeatureTaskRuntimeRunLoopLaunch.verifyFindingsSpecIntentSection(runLoop, run)
  }

  internal fun persistPhase(runLoop: FeatureTaskRuntimeRunLoop, args: PersistPhaseArgs) {
    val write = args.write
    val phaseState =
      phaseStateRequest(
        runLoop,
        PhaseStateRequestArgs(
          write = write,
          extras = PhaseStateRequestAttachments(
            fileManifest = args.fileManifest,
            launched = args.launched,
            reviewRunId = args.reviewRunId,
          ),
        ),
      )
    runLoop.state.reserveReviewPass(phaseState.reviewPassNumber)
    runLoop.recorder.recordPhaseState(
      phaseState,
      write.run.request.dbPathOverride,
    )
  }

  internal fun phaseStateRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseStateRequestArgs,
  ): FeatureTaskRuntimePhaseStateRequest {
    val write = args.write
    val run = write.run
    val extras = args.extras
    val fileManifest = extras.fileManifest
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = write.status,
      attemptCount = write.iteration,
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = write.finished,
      outputArtifact = write.outputArtifact,
      normalizedOutput = extras.normalizedOutput,
      repairEvidence = extras.repairEvidence,
      repositoryFingerprint = extras.repositoryFingerprint,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
      reviewPassNumber = reviewPassNumber(runLoop, run, runLoop.state),
      auditScopeCriterionRefs = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        FeatureTaskRuntimeRunLoopPhaseRunner.openAuditCriterionRefs(runLoop)
      } else {
        emptyList()
      },
      launchedModel = extras.launched?.modelOverride,
      launchedEffort = extras.launched?.persistedEffort,
      launchOutcomeKnown = extras.launched != null,
      reviewRunId = extras.reviewRunId,
    )
  }

  internal fun launchedModelDirective(run: PhaseRun): LaunchedModelDirective {
    val model = run.modelDirective?.model
    val effort = run.modelDirective?.effort
    if (run.resolvedAgent.resolvedAgentId == InstallAgent.CURSOR.id && model != null && effort != null) {
      return LaunchedModelDirective("$model[effort=$effort]", effort, persistedEffort = null)
    }
    return LaunchedModelDirective(model, effort, effort)
  }

  internal fun reviewPassNumber(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): Int? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
    val durable = FeatureTaskRuntimeRunLoopPlanningBranch.goalReviewStateOrNull(runLoop) ?: return 1
    return resolveReviewPassNumber(
      reservedPassNumber = durable.reservedPassNumber ?: state.currentReviewPassNumber,
      completedReviewPassCount = durable.completedPassCount,
    )
  }

  internal fun goalReviewPhaseCompletionRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseReviewPersistenceArgs,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): GoalReviewPhaseCompletionRequest {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelope
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(outputMap, runLoop.request.dbPathOverride)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    return GoalReviewPhaseCompletionRequest(
      phaseState = phaseStateRequest(
        runLoop,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(
            run = args.run,
            iteration = args.iteration,
            status = STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
          ),
          extras = PhaseStateRequestAttachments(
            fileManifest = args.fileManifest,
            normalizedOutput = normalizedOutput,
            repairEvidence = repairEvidence,
          ),
        ),
      ),
      verdict = outcome.verdict,
      unresolvedFindingCount = outcome.unresolvedFindingCount,
      findings = findings,
      rawReviewResult = outputText,
      blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
        outputMap,
        FeatureTaskRuntimeRunLoopPlanningBranch.priorBlockerFindingIds(runLoop),
      ),
      commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
    )
  }
}

private fun expectedCheckpointForLaunch(
  run: PhaseRun,
  repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
): String? = if (
  run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
  run.reentry?.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
) {
  repositoryCheckpoint?.fingerprint
} else {
  run.reentry?.expectedRepositoryCheckpoint ?: repositoryCheckpoint?.fingerprint
}
