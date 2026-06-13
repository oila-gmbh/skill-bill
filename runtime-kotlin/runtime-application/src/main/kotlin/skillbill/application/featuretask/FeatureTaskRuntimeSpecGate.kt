package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.SpecScratchStore
import skillbill.workflow.model.SpecSource
import java.nio.file.Path

/**
 * Bundles the runtime's per-run spec-source concerns behind one collaborator: resolving the
 * persisted `spec_source` stamp (artifact-only, never config) and deleting single_spec linear-mode
 * scratch on terminal success. Grouping the read ([specSourceResolver]) and the mutation
 * ([specScratchStore]) keeps [FeatureTaskRuntimePhaseGates] a small bag of gates and lets the runner
 * delegate terminal cleanup instead of owning it.
 */
@Inject
class FeatureTaskRuntimeSpecGate(
  val specSourceResolver: SpecSourceResolver,
  val specScratchStore: SpecScratchStore,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  // Terminal-success cleanup for a single_spec linear-mode run: the spec scratch is never committed
  // and is deleted only when the run terminally succeeded (PR phase completed for a non-goal-
  // continuation run). Decomposed parent runs report Decomposed (not Completed) and goal-continuation
  // subtasks are owned by the goal runner, so both are skipped here. Local mode never deletes (AC6).
  // Deletion is failure-isolated so a filesystem fault cannot falsely-fail an otherwise good run.
  fun deleteSingleSpecScratchOnTerminalSuccess(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    specSource: SpecSource,
  ) {
    if (specSource != SpecSource.LINEAR ||
      request.goalContinuation != null ||
      report !is FeatureTaskRuntimeRunReport.Completed
    ) {
      return
    }
    val specDir = resolvedParentSpecPath(request.repoRoot, Path.of(request.runInvariants.specReference)).parent
      ?: return
    runCatching { specScratchStore.deleteDirectoryIfExists(specDir) }
      .onFailure { error ->
        diagnostics.warning(
          "Feature-task-runtime linear-mode spec scratch deletion at '$specDir' failed; " +
            "the successful run is unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }
}
