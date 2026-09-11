package skillbill.engine.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.commitMessage

internal fun WorkflowGitOperations.subtaskCommitSpanFailure(request: SubtaskCommitSpanFailureRequest): String? {
  if (request.checkpoints.any { it.branch != request.branch }) {
    return "active checkpoint identities belong to another branch; operator decision: reconcile branch ownership"
  }
  return walkSubtaskCommitSpan(this, request)
}

private fun walkSubtaskCommitSpan(git: WorkflowGitOperations, request: SubtaskCommitSpanFailureRequest): String? {
  val span = mutableListOf<String>()
  var current = request.headSha
  val visited = mutableSetOf<String>()
  var earlierHistory = false
  var failure: String? = null
  while (current != request.baseSha && failure == null) {
    if (visited.add(current)) {
      val step = inspectSubtaskCommitSpanStep(git, request, current, earlierHistory)
      failure = step.failure
      if (step.owned) span += current else earlierHistory = true
      current = step.parent ?: current
    } else {
      failure = "first-parent history repeats '$current'; operator decision: repair Git history"
    }
  }
  return failure ?: validateSubtaskCommitSpan(request, span)
}

private data class SubtaskCommitSpanStep(
  val parent: String?,
  val owned: Boolean,
  val failure: String?,
)

private fun inspectSubtaskCommitSpanStep(
  git: WorkflowGitOperations,
  request: SubtaskCommitSpanFailureRequest,
  current: String,
  earlierHistory: Boolean,
): SubtaskCommitSpanStep {
  val message = git.commitMessage(request.repoRoot, current)
  if (!message.ok) {
    return SubtaskCommitSpanStep(
      null,
      false,
      "commit '$current' cannot be read (${message.error}); operator decision: repair Git access",
    )
  }
  val owned = request.identity.matches(message.value.orEmpty())
  if (owned && earlierHistory) {
    return SubtaskCommitSpanStep(
      null,
      false,
      "active subtask history is interrupted by foreign commits; operator decision: identify the exact owned span",
    )
  }
  val parent = git.resolveCommit(request.repoRoot, "$current^")
  if (!parent.ok || parent.value.orEmpty().isBlank()) {
    return SubtaskCommitSpanStep(
      null,
      owned,
      "first-parent history does not reach base '${request.baseSha}'; operator decision: " +
        "identify the exact subtask base",
    )
  }
  return SubtaskCommitSpanStep(parent.value.orEmpty().trim(), owned, null)
}

private fun validateSubtaskCommitSpan(request: SubtaskCommitSpanFailureRequest, span: List<String>): String? = when {
  span.reversed() != request.checkpoints.map { it.commitSha } ->
    "durable identities do not cover every active subtask commit; operator decision: reconcile the complete " +
      "owned span"
  span.isNotEmpty() && span.first() != request.headSha ->
    "HEAD is outside the active subtask span; operator decision: restore the owned branch head"
  else -> null
}
