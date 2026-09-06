package skillbill.application.workflow

import skillbill.workflow.engine.model.WorkflowId
import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.IssueKey

private val GOAL_TERMINAL_MANIFEST_STATUSES: Set<String> = setOf("complete", "skipped")

fun WorkflowStateRepository.goalContinuationFor(
  issueKey: IssueKey,
  repositoryIdentity: String,
  validator: DecompositionManifestValidator,
): GoalContinuationCandidate? {
  val record = findDecomposedParentWorkflow(issueKey, validator)
    ?.takeIf { it.workflowStatus !in IMPLEMENT_TERMINAL_STATUSES }
    ?: return null
  val manifest = record.toSnapshot().decompositionRuntime(validator)
    ?.takeIf { it.status !in GOAL_TERMINAL_MANIFEST_STATUSES }
    ?: return null
  val normalizedIssueKey = issueKey.value.trim().uppercase()
  val boundToThisRepository = findGoalChildFeatureTaskCandidates(normalizedIssueKey, repositoryIdentity).isNotEmpty() ||
    countGoalChildIdentities(normalizedIssueKey) == 0
  if (!boundToThisRepository) return null
  val running = record.workflowStatus == "running"
  return GoalContinuationCandidate(
    parentWorkflowId = record.workflowId,
    issueKey = manifest.issueKey,
    status = record.workflowStatus,
    currentSubtaskId = manifest.currentSubtaskIntent.subtaskId.value.takeIf { it > 0 },
    currentAction = manifest.currentSubtaskIntent.action,
    completeCount = manifest.subtasks.count { it.status == "complete" },
    pendingCount = manifest.subtasks.count { it.status !in GOAL_TERMINAL_MANIFEST_STATUSES },
    blockedCount = manifest.subtasks.count { it.status == "blocked" },
    updatedAt = record.updatedAt,
    summary = if (running) {
      "A goal run for '${manifest.issueKey}' is already in progress; check it before starting another."
    } else {
      "A prepared goal for '${manifest.issueKey}' owns durable state; continue it instead of starting new work."
    },
  )
}
