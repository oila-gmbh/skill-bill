package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

fun WorkflowGitOperations.deleteCheckpointRefsUnderPrefix(
  repoRoot: Path,
  namespacePrefix: String,
  subtaskRefPrefix: String,
): WorkflowGitOperationResult {
  val listed = listCheckpointRefs(repoRoot, subtaskRefPrefix)
  if (!listed.ok) return listed
  val refs = listed.value.orEmpty()
    .split('\u0000')
    .filter(String::isNotBlank)
    .chunked(2)
    .mapNotNull { parts -> parts.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
  refs.forEach { refName ->
    val deleted = deleteCheckpointRef(repoRoot, namespacePrefix, refName)
    if (!deleted.ok) return deleted
  }
  return WorkflowGitOperationResult(status = "ok", value = refs.size.toString())
}
