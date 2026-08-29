package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.application.goalrunner.verificationBoundaryFindingPaths
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal fun FeatureTaskRuntimeRunLoop.findingPathsForBoundaryMemory(
  finding: StructuredGoalReviewFinding,
): List<String> = GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding)

internal fun FeatureTaskRuntimeRunLoop.verifyFindingsSpecIntentSection(run: PhaseRun): String {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return ""
  val checkpoint = recorder.loadFindingVerificationCheckpoint(run.request.workflowId, run.request.dbPathOverride)
  val boundarySelection = phaseGates.findingVerificationBoundaryMemory.boundarySelectionsForResolvedBodies(
    persisted = recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
      run.request.dbPathOverride,
    ),
  )
  val resolution = phaseGates.specIntentProjectionResolver.resolve(
    SpecIntentProjectionResolveRequest(
      repoRoot = run.request.repoRoot,
      explicitSpecPath = Path.of(run.request.runInvariants.specReference),
      branchName = resolvedBranch ?: "HEAD",
      changedPaths = emptyList(),
      budget = ReviewContextBudgetPolicy.DEFAULT,
    ),
  )
  val boundarySections = findingVerificationBoundarySections(run)
  return buildString {
    when (resolution) {
      is SpecIntentResolution.Resolved -> {
        appendLine()
        appendLine("## Spec intent projection (verify_findings)")
        appendLine(JsonSupport.mapToJsonString(resolution.projection.toProjectionPayload()))
      }
      is SpecIntentResolution.None -> Unit
    }
    append(phaseGates.findingVerificationBoundaryMemory.promptSection(boundarySections))
    if (boundarySelection != null) {
      append(
        phaseGates.findingVerificationBoundaryMemory.resolvedBodiesPromptSection(
          repoRoot = run.request.repoRoot,
          sections = boundarySections,
          selectionsByFindingId = boundarySelection,
        ),
      )
    }
    if (!checkpoint.isNullOrEmpty()) {
      appendLine()
      appendLine("## Persisted verify_findings checkpoint")
      appendLine(
        "Reuse these in-flight dispositions verbatim unless repository evidence contradicts them; " +
          "do not mint a second verification pass.",
      )
      appendLine(
        checkpoint.joinToString(prefix = "[", postfix = "]") { disposition ->
          JsonSupport.mapToJsonString(disposition.toArtifactMap())
        },
      )
    }
  }
}

internal fun FeatureTaskRuntimeRunLoop.launchAndCapture(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  priorCorrection: PriorAttemptCorrection? = null,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
): LaunchResult {
  val before = when (val captured = captureLaunchBeforeState(run)) {
    is LaunchCaptureBeforeResult.Ready -> captured.state
    is LaunchCaptureBeforeResult.Failed ->
      return launchCaptureInfraFailure(run.phaseId, captured.detail, childNeverLaunched = true)
  }
  val prepared = when (val preparation = prepareLaunchForCapture(run, state, priorCorrection)) {
    is PreparedLaunchReady -> preparation.value
    is LaunchPreparationRejected -> return preparation.result
    is LaunchMeasurementContextReady,
    is ClosedCriterionRefsReady,
    -> error("Unexpected launch preparation result.")
  }
  val (isReviewPhase, isVerifyFindingsPhase) = isReadOnlyLaunchPhase(run.phaseId)
  val outcome = executeSubtaskLaunch(run, prepared, isReviewPhase, isVerifyFindingsPhase)
  recordLaunchTokenUsage(run, prepared.briefing, outcome, phaseTokenAccumulator)
  val fileManifest = when (val captured = buildLaunchFileManifest(run, before)) {
    is LaunchCaptureAfterResult.Ready -> captured.manifest
    is LaunchCaptureAfterResult.Failed ->
      return launchCaptureInfraFailure(run.phaseId, captured.detail, childNeverLaunched = false)
  }
  capturePhaseContentIdentities(run.phaseId)
  return reconcileLaunch(run.phaseId, outcome, fileManifest)
}

/**
 * Records what the phase left on disk the instant it stopped running. Anything that differs from
 * this at checkpoint time was written by someone other than the phase, which is the only way to
 * detect a concurrent unstaged edit to a file this workflow owns.
 */
internal fun FeatureTaskRuntimeRunLoop.capturePhaseContentIdentities(phaseId: String) {
  val owned = gitOperations.repositoryOwnedPaths(request.repoRoot)
  if (!owned.ok) return
  val paths = owned.value.orEmpty().split(OWNED_PATH_DELIMITER).map(String::trim).filter(String::isNotBlank)
  val identities = gitOperations.pathContentIdentities(request.repoRoot, paths)
  if (!identities.ok) return
  phaseContentIdentities[phaseId] = parseContentIdentities(identities.value.orEmpty())
}

internal fun FeatureTaskRuntimeRunLoop.parseContentIdentities(raw: String): Map<String, String> = raw
  .split(OWNED_PATH_DELIMITER)
  .filter(String::isNotBlank)
  .mapNotNull { record ->
    val identity = record.substringBefore('\t', missingDelimiterValue = "")
    val path = record.substringAfter('\t', missingDelimiterValue = "")
    if (identity.isBlank() || path.isBlank()) null else path to identity
  }
  .toMap()

internal fun FeatureTaskRuntimeRunLoop.prepareLaunchForCapture(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  priorCorrection: PriorAttemptCorrection?,
): LaunchPreparation {
  val measurementContext = when (val resolution = resolveLaunchMeasurementContext(run, state)) {
    is LaunchMeasurementContextReady -> resolution.value
    is LaunchPreparationRejected -> return resolution
    is PreparedLaunchReady,
    is ClosedCriterionRefsReady,
    -> error("Unexpected launch measurement result.")
  }
  val durablyClosedCriterionRefs = when (
    val resolution = resolveDurablyClosedCriterionRefs(run, state, measurementContext)
  ) {
    is ClosedCriterionRefsReady -> resolution.value
    is LaunchPreparationRejected -> return resolution
    is PreparedLaunchReady,
    is LaunchMeasurementContextReady,
    -> error("Unexpected closed-criterion result.")
  }
  return prepareDeclaredLaunch(
    run,
    state,
    priorCorrection,
    durablyClosedCriterionRefs,
    measurementContext,
  )
}
