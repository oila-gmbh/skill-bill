package skillbill.ports.workflow.gitops

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object NoopRepositoryFingerprintGitOperations : RepositoryFingerprintGitOperations {
  private const val NAME = "NoopRepositoryFingerprintGitOperations"

  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "repositoryFingerprint(repoRoot=$repoRoot)")
    return WorkflowGitOperationResult(status = "ok", value = NOOP_REPOSITORY_FINGERPRINT)
  }
}

private const val NOOP_REPOSITORY_FINGERPRINT: String = "noop-repository-fingerprint"
