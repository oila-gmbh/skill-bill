package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.toProjectionPayload
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.StructuredGoalReviewFinding
import skillbill.application.subtaskreview.verificationBoundaryFindingPaths
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeRunLoopLaunch {
  internal fun findingPathsForBoundaryMemory(finding: StructuredGoalReviewFinding): List<String> =
    GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding)

  internal fun verifyFindingsSpecIntentSection(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return ""
    val checkpoint = runLoop.recorder.loadFindingVerificationCheckpoint(
      run.request.workflowId,
      run.request.dbPathOverride,
    )
    val boundarySelection = runLoop.recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
      run.request.dbPathOverride,
    )?.takeIf { it.isNotEmpty() }
    val resolution = runLoop.phaseGates.specIntentProjectionResolver.resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = run.request.repoRoot,
        explicitSpecPath = Path.of(run.request.runInvariants.specReference),
        branchName = runLoop.session.resolvedBranch ?: "HEAD",
        changedPaths = emptyList(),
        budget = ReviewContextBudgetPolicy.DEFAULT,
      ),
    )
    val boundarySections = runLoop.collaborators.outputVerificationContinued2
      .findingVerificationBoundarySections(runLoop, run)
    return buildString {
      when (resolution) {
        is SpecIntentResolution.Resolved -> {
          appendLine()
          appendLine("## Spec intent projection (verify_findings)")
          appendLine(JsonSupport.mapToJsonString(resolution.projection.toProjectionPayload()))
        }
        is SpecIntentResolution.None -> Unit
      }
      append(runLoop.phaseGates.findingVerificationBoundaryMemory.promptSection(boundarySections))
      if (boundarySelection != null) {
        append(
          runLoop.phaseGates.findingVerificationBoundaryMemory.resolvedBodiesPromptSection(
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

  internal fun launchAndCapture(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection? = null,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): LaunchResult {
    val before = when (val captured = runLoop.collaborators.launchContinued1.captureLaunchBeforeState(runLoop, run)) {
      is LaunchCaptureBeforeResult.Ready -> captured.state
      is LaunchCaptureBeforeResult.Failed ->
        return runLoop.collaborators.launchContinued1.launchCaptureInfraFailure(
          run.phaseId,
          captured.detail,
          childNeverLaunched = true,
        )
    }
    val prepared = when (val preparation = prepareLaunchForCapture(runLoop, run, state, priorCorrection)) {
      is PreparedLaunchReady -> preparation.value
      is LaunchPreparationRejected -> return preparation.result
      is LaunchMeasurementContextReady,
      is ClosedCriterionRefsReady,
      -> error("Unexpected launch preparation result.")
    }
    val (
      isReviewPhase,
      isVerifyFindingsPhase,
    ) = runLoop.collaborators.launchContinued1.isReadOnlyLaunchPhase(run.phaseId)
    val outcome = runLoop.collaborators.launchContinued1.executeSubtaskLaunch(
      runLoop,
      run,
      prepared,
      isReviewPhase,
      isVerifyFindingsPhase,
    )
    runLoop.collaborators.launchContinued1.recordLaunchTokenUsage(
      run,
      prepared.briefing,
      outcome,
      runLoop.phaseTokenAccumulator,
    )
    val fileManifest = when (
      val captured = runLoop.collaborators.launchContinued1.buildLaunchFileManifest(
        runLoop,
        run,
        before,
      )
    ) {
      is LaunchCaptureAfterResult.Ready -> captured.manifest
      is LaunchCaptureAfterResult.Failed ->
        return runLoop.collaborators.launchContinued1.launchCaptureInfraFailure(
          run.phaseId,
          captured.detail,
          childNeverLaunched = false,
        )
    }
    capturePhaseContentIdentities(runLoop, run.phaseId)
    return runLoop.collaborators.launchContinued3.reconcileLaunch(run.phaseId, outcome, fileManifest)
  }

  /**
   * Records what the phase left on disk the instant it stopped running. Anything that differs from
   * this at checkpoint time was written by someone other than the phase, which is the only way to
   * detect a concurrent unstaged edit to a file this workflow owns.
   */
  fun capturePhaseContentIdentities(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String) {
    val owned = runLoop.gitOperations.repositoryOwnedPaths(runLoop.request.repoRoot)
    if (!owned.ok) return
    val paths = owned.value.orEmpty().split(OWNED_PATH_DELIMITER).map(String::trim).filter(String::isNotBlank)
    val identities = runLoop.gitOperations.pathContentIdentities(runLoop.request.repoRoot, paths)
    if (!identities.ok) return
    runLoop.session.phaseContentIdentities[phaseId] = parseContentIdentities(identities.value.orEmpty())
  }

  fun parseContentIdentities(raw: String): Map<String, String> = raw
    .split(OWNED_PATH_DELIMITER)
    .filter(String::isNotBlank)
    .mapNotNull { record ->
      val identity = record.substringBefore('\t', missingDelimiterValue = "")
      val path = record.substringAfter('\t', missingDelimiterValue = "")
      if (identity.isBlank() || path.isBlank()) null else path to identity
    }
    .toMap()

  internal fun prepareLaunchForCapture(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection?,
  ): LaunchPreparation {
    val measurementContext = when (
      val resolution = runLoop.collaborators.launchContinued2.resolveLaunchMeasurementContext(
        runLoop,
        run,
        state,
      )
    ) {
      is LaunchMeasurementContextReady -> resolution.value
      is LaunchPreparationRejected -> return resolution
      is PreparedLaunchReady,
      is ClosedCriterionRefsReady,
      -> error("Unexpected launch measurement result.")
    }
    val durablyClosedCriterionRefs = when (
      val resolution = runLoop.collaborators.launchContinued2.resolveDurablyClosedCriterionRefs(
        runLoop,
        run,
        state,
        measurementContext,
      )
    ) {
      is ClosedCriterionRefsReady -> resolution.value
      is LaunchPreparationRejected -> return resolution
      is PreparedLaunchReady,
      is LaunchMeasurementContextReady,
      -> error("Unexpected closed-criterion result.")
    }
    return runLoop.collaborators.launchContinued2.prepareDeclaredLaunch(
      runLoop,
      DeclaredLaunchArgs(run, state, priorCorrection, durablyClosedCriterionRefs, measurementContext),
    )
  }

  internal data class LaunchCaptureBeforeState(
    val beforeManifest: String,
    val beforeCommit: String,
  )

  internal sealed interface LaunchCaptureBeforeResult {
    data class Ready(val state: LaunchCaptureBeforeState) : LaunchCaptureBeforeResult
    data class Failed(val detail: String) : LaunchCaptureBeforeResult
  }

  internal sealed interface LaunchCaptureAfterResult {
    data class Ready(val manifest: FeatureTaskRuntimePhaseFileManifest) : LaunchCaptureAfterResult
    data class Failed(val detail: String) : LaunchCaptureAfterResult
  }
}
