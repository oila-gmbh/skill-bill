package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
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
  private val specStatusWriter: FeatureTaskRuntimeSpecStatusWriter,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  // Single terminal-finalize entry point for the runner: reconcile the single-spec `Agent:` line and
  // delete linear-mode scratch. Keeps the runner's finalize path to one call; each step self-gates.
  fun finalizeSingleSpecOnTerminal(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    specSource: SpecSource,
    finalizingAgentId: (FeatureTaskRuntimeRunRequest) -> String?,
  ) {
    reconcileSingleSpecAgentLine(request, report) { finalizingAgentId(request) }
    deleteSingleSpecScratchOnTerminalSuccess(request, report, specSource)
  }

  // Completion-time reconciliation of the single-spec `## Status` `Agent:` line (SKILL-89 Seam D,
  // runtime). Only for a single-spec run (no goal continuation) that reached terminal success; the
  // finalizing agent is the Seam A ledger derivation supplied lazily by the runner, computed only
  // when this gate decides to write. Failure-isolated: a write fault never falsely-fails a good run.
  private fun reconcileSingleSpecAgentLine(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    finalizingAgentId: () -> String?,
  ) {
    if (request.goalContinuation != null || report !is FeatureTaskRuntimeRunReport.Completed) {
      return
    }
    val agentId = finalizingAgentId()?.takeIf(String::isNotBlank) ?: return
    runCatching {
      specStatusWriter.writeFinalizingAgent(Path.of(request.runInvariants.specReference), agentId)
    }.onFailure { error ->
      diagnostics.warning(
        "Feature-task-runtime single-spec Agent line reconciliation for workflow " +
          "'${request.workflowId}' failed; the successful run is unaffected.",
        error,
      )
    }
  }

  // Terminal-success cleanup for a single_spec linear-mode run: the spec scratch is never committed
  // and is deleted only when the run terminally succeeded (PR phase completed for a non-goal-
  // continuation run). Decomposed parent runs report Decomposed (not Completed) and goal-continuation
  // subtasks are owned by the goal runner, so both are skipped here. Local mode never deletes (AC6).
  // Deletion is failure-isolated so a filesystem fault cannot falsely-fail an otherwise good run.
  private fun deleteSingleSpecScratchOnTerminalSuccess(
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
