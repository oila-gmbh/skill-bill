package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.time.Instant
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.StackDetectionException
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.application.review.model.UsageValidationException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus


internal fun FeatureTaskRuntimeRunLoop.blockUnattributableRecordRejection(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    rejection: RecordRejection,
    producer: String?,
  ): PhaseOutcome {
    val detail = payloadFreeRejectionReason(
      "reconciliation-${rejection.rejectionClass}",
      rejectionPath(rejection.rejectionDetail),
    )
    val rejectedOutput = run.declaration.projectionDeclarations
      .asSequence()
      .map { it.producerIteration.phaseId }
      .distinct()
      .mapNotNull { phaseId -> state.outputFor(phaseId) }
      .firstOrNull()
    val evidence = rejectedOutput?.let { output ->
      val agentId = state.recordFor(output.phaseId)?.resolvedAgentId ?: return@let null
      when (
        val read = recorder.producerOutput(
          request.workflowId,
          output.phaseId,
          output.iteration.coerceAtLeast(1),
          agentId,
          request.dbPathOverride,
          state.evidenceGeneration(output.phaseId),
        )
      ) {
        is FeatureTaskRuntimeProducerOutputRead.Found -> read.evidence
        is FeatureTaskRuntimeProducerOutputRead.Absent,
        is FeatureTaskRuntimeProducerOutputRead.Unreadable,
        -> null
      }
    }
    evidence?.let {
      recordRejectedOutput(
        run = run,
        iteration = it.attempt,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = retryRejectionReason(detail, rejection.rejectionDetail),
        outputBytes = it.payload ?: byteArrayOf(),
        phaseId = it.phaseId,
        agentId = it.agentId,
        model = it.model,
        path = rejectionPath(rejection.rejectionDetail),
        outputByteSize = it.byteSize,
        outputSha256 = it.sha256,
        outputTruncated = it.payload == null,
        repairTurn = it.repairTurn,
      )
    }
    val reason = if (producer == null) {
      "Feature-task-runtime phase '${run.phaseId}' rejected an upstream durable record " +
        "(${rejection.rejectionClass}) it cannot attribute to a producing phase, so no regeneration edge " +
        "applies; the run blocks durably. Recover the record out of band by deleting or migrating the " +
        "offending row. Detail: $detail"
    } else {
      "Feature-task-runtime phase '${run.phaseId}' rejected the durable record produced by '$producer', but " +
        "'$producer' is absent from this run's resolved pipeline (a goal-continuation truncation dropped it), " +
        "so it cannot be regenerated in-band; the run blocks durably. Recover the record out of band by " +
        "deleting or migrating the offending row. Detail: $detail"
    }
    return blockAndPersistInPhase(
      run,
      iteration,
      reason,
      observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      childNeverLaunched = true,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.rejectionPath(detail: String): String {
    Regex("""(?:instance location|path|pointer)\s*[:=]\s*['"]?(/[^\s,'"]*)""", RegexOption.IGNORE_CASE)
      .find(detail)
      ?.groupValues
      ?.get(1)
      ?.let { return it }
    val dollarPath = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""").find(detail)?.value ?: return "/"
    return dollarPath.removePrefix("$")
      .replace(Regex("""\.([A-Za-z0-9_-]+)"""), "/${'$'}1")
      .replace(Regex("""\[([0-9]+)]"""), "/${'$'}1")
  }

internal fun FeatureTaskRuntimeRunLoop.payloadFreeRejectionReason(rule: String, path: String): String =
    "Rejected output violated '$rule' at '$path'. Inspect the private diagnostic for the exact response."

  /**
   * The retry-facing counterpart of [payloadFreeRejectionReason]. A producer cannot repair an output from a
   * rule name and a path alone, so the validator's constraint text — the violated rule, the expected shape
   * and the offending field, all authored from the schema and never from the response — is appended for the
   * next prompt and for the private diagnostic row. The payload-free sentence stays the prefix so both
   * readers still learn where the raw response is kept.
   *
   * A null or blank [validationReason] means the producing seam had no value-free restatement to offer, so
   * the payload-free sentence stands alone; the value-bearing variant is never substituted in its place.
   */
internal fun FeatureTaskRuntimeRunLoop.retryRejectionReason(payloadFreeReason: String, validationReason: String?): String =
    if (validationReason.isNullOrBlank()) {
      payloadFreeReason
    } else {
      "$payloadFreeReason Violated constraint: ${boundedSchemaGateDetail(validationReason)}"
    }

  /**
   * Semantic-gate detail that is safe to place outside the authorized repair section.
   *
   * Mutating-reconciliation is a fixed template. Producer/consumer projection and output-verification
   * may carry schema-structure text the producer needs, but only after response-derived dumps
   * (quoted wire verdicts, offending-value appendices, expected=/actual= receipt lists) are scrubbed.
   * Audit ledger/repair gates stay null except for scrubbed bounded artifact_ref/check_ref
   * constraints — those must reach the retry reason so compound or oversized refs get actionable
   * guidance instead of a generic audit sentence alone.
   */
internal fun FeatureTaskRuntimeRunLoop.payloadFreeSemanticGateConstraint(
    rule: String,
    detail: String,
    rejectedOutput: Map<String, Any?>,
  ): String? = when (rule) {
    "mutating-reconciliation" -> detail.takeUnless { it.isBlank() }
    "repair-receipt" -> detail.takeUnless { it.isBlank() }
    "producer-projection",
    "consumer-projection",
    "output-verification",
    -> scrubResponseDerivedGateDetail(detail, rejectedOutput)
    else -> scrubBoundedReferenceGateConstraint(detail)
  }

  /**
   * Extracts a payload-free bounded-reference constraint from semantic-gate detail. Returns null when
   * the detail does not name artifact_ref or check_ref, so audit identifiers and expected=/actual=
   * receipt lists never reach the retry reason by themselves.
   */
internal fun FeatureTaskRuntimeRunLoop.scrubBoundedReferenceGateConstraint(detail: String): String? {
    if (detail.isBlank()) return null
    val namesArtifactRef = detail.contains("artifact_ref")
    val namesCheckRef = detail.contains("check_ref")
    if (!namesArtifactRef && !namesCheckRef) return null
    val cap = BOUNDED_REF_LENGTH_CAP_PATTERN.find(detail)?.groupValues?.get(1)?.replace(",", "")
    return when {
      namesArtifactRef && cap != null -> "artifact_ref allows at most $cap characters."
      namesCheckRef && cap != null -> "check_ref allows at most $cap characters."
      namesArtifactRef ->
        "artifact_ref must be a bounded path or symbol reference such as " +
          "src/main/Example.kt or src/main/Example.kt:Example."
      else ->
        "check_ref must match AC-###, F-###, or a name ending in Test or Check, optionally followed " +
          "by :symbol; examples: AC-005, FeatureTaskRuntimeAuditEntryGateTest, or codeCheck:detekt."
    }
  }

  /**
   * Strips known response-value dumps from semantic-gate detail before it can enter a retry prompt
   * outside the authorized repair section. Schema-structure fragments (property names, found/expected
   * types, maxLength caps) remain so length and shape corrections still fire.
   *
   * Caps at [SCHEMA_GATE_DETAIL_MAX_CHARS] before pattern work so an oversized wire verdict cannot
   * amplify retry CPU; when the cap cuts inside a quoted verdict, [scrubOffVocabularyVerdictQuote]
   * strips the open marker through end rather than leaving a partial response-derived quote.
   */
internal fun FeatureTaskRuntimeRunLoop.scrubResponseDerivedGateDetail(detail: String, rejectedOutput: Map<String, Any?>): String? {
    if (detail.isBlank()) return null
    var text = detail.take(SCHEMA_GATE_DETAIL_MAX_CHARS)
    text = scrubOffVocabularyVerdictQuote(text)
    text = OFFENDING_VALUE_APPENDIX_PATTERN.replace(text, "")
    text = EXPECTED_ACTUAL_LIST_PATTERN.replace(text, "")
    responseStringValues(rejectedOutput)
      .filter { value ->
        value.length >= MIN_RESPONSE_STRING_VALUE_LENGTH &&
          SCHEMA_DETAIL_TYPE_WORDS.none { typeWord -> typeWord.equals(value, ignoreCase = true) } &&
          text.contains(value)
      }
      .sortedByDescending(String::length)
      .forEach { value -> text = text.replace(value, "[response value omitted]") }
    return text.trim().takeUnless { it.isBlank() }
  }

internal fun FeatureTaskRuntimeRunLoop.responseStringValues(value: Any?): List<String> {
    val values = mutableListOf<String>()
    collectResponseStringValues(value, values)
    return values.distinct()
  }

internal fun FeatureTaskRuntimeRunLoop.collectResponseStringValues(value: Any?, values: MutableList<String>) {
    when (value) {
      is String -> values += value
      is Map<*, *> -> value.values.forEach { nested -> collectResponseStringValues(nested, values) }
      is Iterable<*> -> value.forEach { nested -> collectResponseStringValues(nested, values) }
    }
  }

internal fun FeatureTaskRuntimeRunLoop.attemptOnce(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    priorCorrection: PriorAttemptCorrection?,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): AttemptResult {
    // The running write is what the IDE reads as current_model while the child is in flight, so it
    // stamps the directive the launch below is rendered from. The settling exits then clear it only
    // where the launch proved no child ever ran, via LaunchResult.childNeverLaunched.
    persistPhase(
      run,
      iteration,
      STATUS_RUNNING,
      finished = false,
      outputArtifact = state.outputFor(run.phaseId)?.payload,
      launched = launchedModelDirective(run),
    )
    val launch = launchAndCapture(run, state, priorCorrection, phaseTokenAccumulator)
    launch.providerLimitReason?.let { reason ->
      return AttemptResult.settled(pauseAndPersistInPhase(run, iteration, reason, observability, launch.fileManifest))
    }
    launch.infraFailureReason?.let { reason ->
      // Persisted before the block so the artifact exists even if the block write then degrades:
      // the whole point is to leave something readable behind a failure that reaches no output gate.
      persistChildProcessFailureOutput(run, iteration, reason, launch.infraFailureChildOutput)
      return AttemptResult.settled(
        blockAndPersistInPhase(
          run,
          iteration,
          reason,
          observability,
          failureDisposition = launch.failureDisposition,
          fileManifest = launch.fileManifest,
          childNeverLaunched = launch.childNeverLaunched,
        ),
      )
    }
    launch.recordRejection?.let { rejection ->
      return AttemptResult.settled(
        settleRecordRejection(run, state, iteration, observability, rejection),
      )
    }
    val fileManifest = requireNotNull(launch.fileManifest)
    return gateOutput(
      run,
      iteration,
      requireNotNull(launch.capturedStdout),
      requireNotNull(launch.capturedStdoutBytes),
      launch.capturedStdoutTruncated,
      requireNotNull(launch.capturedStdoutByteSize),
      requireNotNull(launch.capturedStdoutSha256),
      observability,
      fileManifest,
    )
  }

