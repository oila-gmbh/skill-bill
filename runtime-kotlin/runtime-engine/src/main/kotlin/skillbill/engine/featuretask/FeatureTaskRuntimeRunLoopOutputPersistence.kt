package skillbill.engine.featuretask

import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.GoalReviewPhaseCompletionRequest
import skillbill.error.AuditRepairCycleConflictError
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.install.model.InstallAgent
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairCycleCodec
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor

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
    val reviewState = runLoop.goalContinuationRecorder.reviewState(run.request.workflowId)
    val passNumber = reviewState?.completedPassCount?.takeIf { it > 0 } ?: 1
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(reviewOutput)
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
    FeatureTaskRuntimeRunLoopLaunch.prepareAuditLaunch(runLoop, run)
    val state = args.state
    val priorCorrection = args.priorCorrection
    val durablyClosedCriterionRefs = args.durablyClosedCriterionRefs
    val repositoryCheckpoint = args.repositoryCheckpoint
    val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId)
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
    val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId)
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
    ) + FeatureTaskRuntimeRunLoopLaunch.verifyFindingsSpecIntentSection(runLoop, run) +
      auditRepairStageChannel(runLoop, run)
  }

  private fun auditRepairStageChannel(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return ""
    val binding = FeatureTaskRuntimeRunLoopLaunch.prepareAuditLaunch(runLoop, run)
      ?: return "The durable audit channel has no active worker lease. Stop and report blocked."
    val existing = runLoop.phaseSettlementService.auditRepairCycle(run.request.workflowId, binding.auditAttempt)
    val activity = runLoop.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)
      ?: throw AuditRepairCycleConflictError("The audit repository fingerprint is unavailable.")
    val initial = existing?.diagnosis?.checkpoint ?: binding.checkpoint ?: initialAuditCheckpoint(
      runLoop,
      run,
      binding,
      activity,
    )
    val pinned = runLoop.phaseSettlementService.bindAuditRepairLaunch(binding.identity, initial)
    return auditChannelText(run, pinned, existing, initial)
  }

  private fun initialAuditCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    binding: AuditRepairLaunchBinding,
    activity: String,
  ): AuditRepairCheckpoint {
    val repository = FeatureTaskRuntimeRunLoopOutputVerification.buildRepositoryCheckpoint(runLoop, run)
      ?: throw AuditRepairCycleConflictError("The audit implementation scope is unavailable.")
    return runLoop.phaseSettlementService.prepareAuditCheckpoint(
      binding.cycleId,
      activity,
      repository.workingTreeOwnedPaths,
    )
  }

  private fun auditChannelText(
    run: PhaseRun,
    binding: AuditRepairLaunchBinding,
    existing: AuditRepairCycle?,
    initial: AuditRepairCheckpoint,
  ): String {
    val criterionRefs = acceptanceCriterionRefsFor(run.request.runInvariants.acceptanceCriteria.size)
    return """
      ## Durable audit-repair stage channel
      Call MCP tool `feature_task_audit_stage` for every stage acknowledgement. If MCP is unavailable,
      invoke `skill-bill feature-task audit-stage --request-json '<object>'` with the same request.
      The command returns only after the stage is durable. Use its revision as the next expected_revision.
      Do not put stage evidence only in the final response.

      workflow_id: ${binding.workflowId}
      audit_attempt: ${binding.auditAttempt}
      execution_id: ${binding.executionId}
      session_id: ${binding.requestedSessionId}
      cycle_id: ${binding.cycleId}
      owner_token: ${binding.ownerToken}
      fencing_generation: ${binding.fencingGeneration}
      launch_execution_id: ${binding.executionId}
      requested_session_id: ${binding.requestedSessionId}
      provider_session_id: ${binding.providerSessionId ?: "captured by runtime at session start"}
      current_expected_revision: ${existing?.current?.revision ?: 0}
      criterion_refs: ${criterionRefs.joinToString(prefix = "[", postfix = "]")}
      diagnosis_checkpoint: ${AuditRepairCycleCodec.encodeCheckpoint(initial)}
      restored_cycle_evidence: ${existing?.let(AuditRepairCycleCodec::encode) ?: "none"}

      Stage protocol: diagnosis at revision 0, authorized_repair after a complete diagnosis with gaps,
      checkpoint_pending after every authorized repair outcome, final_audit after the verified checkpoint,
      satisfied only after a complete gap-free final assessment, or paused when progress cannot continue.
      For every stage after diagnosis, set revision to current_expected_revision + 1 and
      expected_revision to current_expected_revision.
      Use diagnosis_checkpoint unchanged for diagnosis. For authorized_repair, copy repository_fingerprint
      from the latest assessment checkpoint. For checkpoint_pending, supply the repair
      outcomes and a unique intent ID. The runtime retains the content and returns its immutable
      checkpoint, content fingerprint and paths. Use them for final_audit and assess every criterion.
      After any stage rejection or a paused acknowledgement, stop edits and report blocked.

      Every paused stage requires a fresh retry_fix grant before recovery. Keep the restored diagnosis
      and repair identities. A paused diagnosis resumes at
      authorized_repair, or checkpoint_pending if it already had no gaps. A paused repair reconciles
      partial edits and preserves completed receipts before checkpoint_pending. A checkpoint intent without
      an attached checkpoint retries its original request while still pending. If paused, submit a new
      checkpoint_pending transition at the current revision with the original intent and repair outcomes;
      this consumes the fresh retry_fix grant before attachment. A paused checkpoint with attached content
      or a paused final audit resumes through final_audit without repeating repairs.
      A failed final assessment needs a fresh retry_fix grant before another authorized_repair.
      A satisfied cycle needs only final settlement with its exact final value.
    """.trimIndent()
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
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(outputMap)
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
