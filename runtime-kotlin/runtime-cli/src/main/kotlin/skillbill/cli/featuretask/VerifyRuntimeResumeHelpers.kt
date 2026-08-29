package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Path

internal fun verifyRuntimeResume(args: VerifyRuntimeResumeArgs) {
  val effectiveRoot = resumeRepositoryRoot(args.repoRoot, Path.of(args.specPath))
  val identity = repositoryIdentity(effectiveRoot)
  val result = if (args.goalChild) {
    args.lookupService.lookupGoalChild(args.issueKey, identity, args.workflowId, args.state.dbOverride)
  } else {
    args.lookupService.lookup(args.issueKey, identity, args.workflowId, args.state.dbOverride)
  }
  val candidate = resumableRuntimeCandidate(args.workflowId, result)
  requireRuntimeMode(args.workflowId, candidate.mode)
  requireMatchingGovernedSpec(args.workflowId, candidate.governedSpecPath, effectiveRoot, Path.of(args.specPath))
}

private fun resumableRuntimeCandidate(workflowId: String, result: FeatureTaskContinuationLookupResult) = when (result) {
  is FeatureTaskContinuationLookupResult.Resumable -> result.candidate
  is FeatureTaskContinuationLookupResult.AlreadyRunning -> result.candidate
  is FeatureTaskContinuationLookupResult.TerminalOnly ->
    throw UsageError("Workflow '$workflowId' is terminal and cannot be resumed; no phase was launched.")
  FeatureTaskContinuationLookupResult.NoMatch,
  is FeatureTaskContinuationLookupResult.Ambiguous,
  is FeatureTaskContinuationLookupResult.GoalContinuation,
  is FeatureTaskContinuationLookupResult.NeedsIdentityRepair,
  -> throw UsageError("Workflow '$workflowId' is not a resumable runtime workflow.")
}

private fun requireRuntimeMode(workflowId: String, mode: FeatureTaskWorkflowMode) {
  if (mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw UsageError("Workflow '$workflowId' was persisted in ${mode.wireValue} mode.")
  }
}

private fun requireMatchingGovernedSpec(
  workflowId: String,
  persistedPath: String,
  effectiveRoot: Path,
  specPath: Path,
) {
  if (persistedPath != governedSpecPath(effectiveRoot, specPath)) {
    throw UsageError("Workflow '$workflowId' was persisted with a different governed spec path.")
  }
}
