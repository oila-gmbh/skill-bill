package skillbill.cli

import skillbill.ports.workflow.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object TestRepositoryFingerprintOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "test-repository-fingerprint")
}
