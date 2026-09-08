package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RuntimePhaseFileManifestGitOperations {
  fun headCommit(repoRoot: Path): WorkflowGitOperationResult

  fun changedPathsBetweenCommits(repoRoot: Path, beforeCommit: String, afterCommit: String): WorkflowGitOperationResult
}

interface RuntimePhaseFileManifestGitOperationsProvider {
  val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations
}

fun WorkflowGitOperations.runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult =
  runtimePhaseFileManifestOperations().headCommit(repoRoot)

fun WorkflowGitOperations.runtimePhaseChangedPathsBetweenCommits(
  repoRoot: Path,
  beforeCommit: String,
  afterCommit: String,
): WorkflowGitOperationResult = runtimePhaseFileManifestOperations().changedPathsBetweenCommits(
  repoRoot,
  beforeCommit,
  afterCommit,
)

private fun WorkflowGitOperations.runtimePhaseFileManifestOperations(): RuntimePhaseFileManifestGitOperations =
  (this as? RuntimePhaseFileManifestGitOperationsProvider)?.runtimePhaseFileManifestOperations
    ?: NoopRuntimePhaseFileManifestGitOperations

private object NoopRuntimePhaseFileManifestGitOperations : RuntimePhaseFileManifestGitOperations {
  private const val NAME = "NoopRuntimePhaseFileManifestGitOperations"

  override fun headCommit(repoRoot: Path): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "headCommit(repoRoot=$repoRoot)")
    return WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun changedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(
      NAME,
      "changedPathsBetweenCommits(repoRoot=$repoRoot, beforeCommit=$beforeCommit, afterCommit=$afterCommit)",
    )
    return WorkflowGitOperationResult(status = "ok", value = "")
  }
}
