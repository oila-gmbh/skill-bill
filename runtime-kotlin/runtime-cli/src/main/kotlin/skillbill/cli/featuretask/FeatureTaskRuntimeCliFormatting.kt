package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.openFeatureTask
import skillbill.cli.core.CliRunInputs
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import java.nio.file.Path

internal fun WorkflowService.openRuntimeWorkflowId(
  inputs: CliRunInputs,
  issueKey: String?,
  specPath: String,
  repoRoot: String,
  routeScope: FeatureTaskRouteScope,
): String = when (
  val opened = openFeatureTask(
    WorkflowServiceOpenFeatureTaskArgs(
      kind = WorkflowFamilyKind.TASK_RUNTIME,
      sessionId = "",
      currentStepId = null,
      dbOverride = inputs.dbPathOverride,
      issueKey = requireNotNull(issueKey),
      repositoryIdentity = repositoryIdentity(Path.of(repoRoot)),
      governedSpecPath = governedSpecPath(Path.of(repoRoot), Path.of(specPath)),
      routeScope = routeScope,
    ),
  )
) {
  is WorkflowOpenResult.Ok -> opened.workflowId
  is WorkflowOpenResult.Error -> throw UsageError(
    "Could not open a feature-task workflow: ${opened.error}",
  )
}

internal fun repositoryIdentity(start: Path): String {
  return "repo-root-realpath-v1:${canonicalGitRoot(start)}"
}

internal fun canonicalGitRoot(start: Path): Path {
  val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
  var candidate = resolvedStart
  while (!candidate.resolve(".git").toFile().exists()) {
    candidate = candidate.parent ?: return resolvedStart
  }
  return candidate.toRealPath()
}

internal fun governedSpecPath(repositoryRoot: Path, specPath: Path): String {
  val root = canonicalGitRoot(repositoryRoot)
  val resolved = (if (specPath.isAbsolute) specPath else root.resolve(specPath)).normalize().toRealPath()
  if (!resolved.startsWith(root)) {
    throw UsageError("Governed spec path must remain inside repository '$root'.")
  }
  val relative = root.relativize(resolved).joinToString("/") { it.toString() }
  if (!relative.startsWith(".feature-specs/") || !relative.endsWith(".md")) {
    throw UsageError("Governed spec path must be Markdown beneath .feature-specs/.")
  }
  return relative
}

internal data class VerifyRuntimeResumeArgs(
  val lookupService: FeatureTaskContinuationLookupService,
  val inputs: CliRunInputs,
  val workflowId: String,
  val issueKey: String,
  val specPath: String,
  val repoRoot: String,
  val goalChild: Boolean,
)

internal fun resumeRepositoryRoot(repoRoot: String, specPath: Path): Path {
  if (repoRoot != "." || !specPath.isAbsolute) return Path.of(repoRoot)
  var candidate: Path? = specPath.parent
  while (candidate != null && candidate.fileName?.toString() != ".feature-specs") candidate = candidate.parent
  return candidate?.parent ?: Path.of(repoRoot)
}

internal fun runtimeRunEventSink(inputs: CliRunInputs, monitor: Boolean): FeatureTaskRuntimeRunEventSink =
  if (!monitor) {
    FeatureTaskRuntimeRunEventSink.NONE
  } else {
    FeatureTaskRuntimeRunEventSink { event ->
      inputs.liveStdout(event.runtimeProgressLine())
    }
  }

internal fun FeatureTaskRuntimeRunEvent.runtimeProgressLine(): String = when (this) {
  is FeatureTaskRuntimeRunEvent.RunStarted ->
    "feature-task-runtime $workflowId: run started feature_size=$featureSize\n"
  is FeatureTaskRuntimeRunEvent.BranchResolved ->
    "feature-task-runtime $workflowId: branch ${if (reused) "reused" else "created"} $branch\n"
  is FeatureTaskRuntimeRunEvent.BranchSetupBlocked ->
    "feature-task-runtime $workflowId: branch setup blocked at phase $phaseId: $blockedReason\n"
  is FeatureTaskRuntimeRunEvent.PhaseStarted -> progressLine()
  is FeatureTaskRuntimeRunEvent.PhaseLoopEdge ->
    "feature-task-runtime $workflowId: phase $phaseId $continuationKind loop=$loopId " +
      "edge_iteration=$edgeIteration driving_verdict=$drivingVerdict\n"
  is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration ->
    "feature-task-runtime $workflowId: phase $phaseId " +
      "${continuationKind ?: "fix_loop"} attempt=$attemptCount iteration=$fixLoopIteration\n"
  is FeatureTaskRuntimeRunEvent.ValidationGateProgress ->
    "feature-task-runtime $workflowId: phase $phaseId gate_run_count=$gateRunCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseCompleted ->
    "feature-task-runtime $workflowId: phase $phaseId completed agent=$resolvedAgentId attempt=$attemptCount\n"
  is FeatureTaskRuntimeRunEvent.PhaseBlocked ->
    "feature-task-runtime $workflowId: phase $phaseId blocked attempt=$attemptCount: $blockedReason\n"
  is FeatureTaskRuntimeRunEvent.PhasePaused ->
    "feature-task-runtime $workflowId: phase $phaseId paused attempt=$attemptCount: $pauseReason\n"
  is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning ->
    "feature-task-runtime $workflowId: decomposed at planning into $subtaskCount subtasks: $reason. " +
      "Work the first subtask first.\n"
}

internal fun FeatureTaskRuntimeRunEvent.PhaseStarted.progressLine(): String =
  "feature-task-runtime $workflowId: phase $phaseId ${if (resumed) "resumed" else "started"} " +
    "agent=$resolvedAgentId attempt=$attemptCount" +
    model?.let { " model=$it" }.orEmpty() +
    effort?.let { " effort=$it" }.orEmpty() +
    continuationKind?.let { " continuation=$it" }.orEmpty() +
    "\n"
