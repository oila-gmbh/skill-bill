package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContentsResult
import java.nio.file.Path

interface SuppressionEvidenceGitOperations {
  fun scopedPathContentsAgainstBase(
    repoRoot: Path,
    baseRef: String,
    headPaths: List<String>,
  ): WorkflowScopedPathContentsResult
}

interface SuppressionEvidenceGitOperationsProvider {
  val suppressionEvidenceOperations: SuppressionEvidenceGitOperations
}

fun WorkflowGitOperations.scopedPathContentsAgainstBase(
  repoRoot: Path,
  baseRef: String,
  headPaths: List<String>,
): WorkflowScopedPathContentsResult = (this as? SuppressionEvidenceGitOperationsProvider)?.suppressionEvidenceOperations
  ?.scopedPathContentsAgainstBase(repoRoot, baseRef, headPaths)
  ?: WorkflowScopedPathContentsResult(
    status = "error",
    error = "WorkflowGitOperations must provide a suppression-evidence implementation.",
  )
