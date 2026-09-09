package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal object GitGoalSubtaskReviewOperations : GoalSubtaskReviewGitOperations {
  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult {
    val expected = expectedBranch.trim()
    if (expected.isBlank()) {
      return GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
    }
    val branch = currentGoalReviewBranch(repoRoot, expected)
      ?: return GoalSubtaskReviewBaselineResult(
        status = "error",
        error = "Goal-subtask review baseline must be captured on durable child branch '$expected'.",
      )
    val head = goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
      ?: return GoalSubtaskReviewBaselineResult(status = "error", error = "Could not resolve HEAD on '$branch'.")
    return GoalSubtaskReviewBaselineResult(status = "ok", baseline = GoalSubtaskReviewBaseline(head))
  }

  override fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult {
    val expected = expectedBranch.trim()
    val branchFailure = validateGoalReviewInputBranch(repoRoot, expected)
    if (branchFailure != null) return branchFailure
    return buildGoalReviewInputFromHead(repoRoot, baseline)
  }

  override fun recoverBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    val expected = expectedBranch.trim()
    if (expected.isBlank()) {
      return GoalSubtaskReviewBaselineResult(status = "error", error = "Goal-subtask durable child branch is required.")
    }
    val snapshot = recoveredBaselineSnapshot(repoRoot, request, expected)
    return if (snapshot.recoveredBaseSha == null) {
      GoalSubtaskReviewBaselineResult(status = "error", error = snapshot.error)
    } else {
      GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = request.toRecoveredBaseline(snapshot.recoveredBaseSha),
      )
    }
  }
}

private fun validateGoalReviewInputBranch(repoRoot: Path, expected: String): GoalSubtaskReviewInputResult? = when {
  expected.isBlank() -> GoalSubtaskReviewInputResult(
    status = "error",
    error = "Goal-subtask durable child branch is required.",
  )
  currentGoalReviewBranch(repoRoot, expected) == null -> GoalSubtaskReviewInputResult(
    status = "error",
    error = "Goal-subtask review must run on durable child branch '$expected'.",
  )
  else -> null
}

private fun buildGoalReviewInputFromHead(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
): GoalSubtaskReviewInputResult {
  val head = goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
    ?: return GoalSubtaskReviewInputResult(status = "error", error = "Could not resolve current HEAD.")
  val failure = reachabilityFailure(repoRoot, baseline, head)
  if (failure != null) return failure
  val tree = goalReviewGitValue(repoRoot, "rev-parse", "$head^{tree}")?.trim()?.takeIf(String::isNotBlank)
    ?: return GoalSubtaskReviewInputResult(
      status = "error",
      error = "Could not resolve the tree of reviewed commit '$head'.",
    )
  return GoalSubtaskReviewInputResult(
    status = "ok",
    input = GoalSubtaskReviewInput(
      reviewBaseSha = baseline.reviewBaseSha,
      currentHeadSha = head,
      reviewedTreeSha = tree,
    ),
  )
}

private fun reachabilityFailure(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  head: String,
): GoalSubtaskReviewInputResult? {
  val exists = runGitCommand(repoRoot, "cat-file", "-e", "${baseline.reviewBaseSha}^{commit}")
  if (!exists.ok) {
    return GoalSubtaskReviewInputResult(
      status = "error",
      error = "Persisted review base '${baseline.reviewBaseSha}' is not an existing commit: ${exists.error}",
      failureReason = exists.takeIf(isDefinitiveMissingObject)
        ?.let { GoalSubtaskReviewInputFailureReason.BASE_MISSING },
    )
  }
  if (baseline.allowNonAncestorBase) return null
  val ancestor = runGitCommand(repoRoot, "merge-base", "--is-ancestor", baseline.reviewBaseSha, head)
  if (ancestor.ok) return null
  return GoalSubtaskReviewInputResult(
    status = "error",
    error = "Persisted review base '${baseline.reviewBaseSha}' is not an ancestor of reviewed commit '$head'; " +
      "refusing a broader review scope.",
    failureReason = ancestor.takeIf(isDefinitiveNonAncestor)
      ?.let { GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR },
  )
}

private data class GoalReviewRecoveredBaseline(
  val recoveredBaseSha: String? = null,
  val error: String = "",
)

private fun recoveredBaselineSnapshot(
  repoRoot: Path,
  request: GoalSubtaskReviewBaselineRecoveryRequest,
  expectedBranch: String,
): GoalReviewRecoveredBaseline {
  val unreachableSha = request.unreachableSha
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val head = branch?.let {
    goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
  }
  // Prefer the nearest reachable ancestor of the failed base so recovery preserves the pass's
  // intended scope. Fall back to origin/main|main only when the object is missing or that
  // nearest-ancestor resolution itself fails.
  val nearestAncestor = head?.takeIf {
    request.failureReason == GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR &&
      runGitCommand(repoRoot, "cat-file", "-e", "$unreachableSha^{commit}").ok
  }?.let { currentHead ->
    goalReviewGitValue(repoRoot, "merge-base", unreachableSha, currentHead)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.takeIf {
        runGitCommand(repoRoot, "merge-base", "--is-ancestor", it, currentHead) is WorkflowGitOperationResult.Ok
      }  }
  val branchBase = head?.takeIf { nearestAncestor == null }?.let { currentHead ->
    listOf("origin/main", "main")
      .filter { runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", it).ok }
      .distinct()
      .firstNotNullOfOrNull { candidate ->
        goalReviewGitValue(repoRoot, "merge-base", candidate, currentHead)
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.takeIf {
            runGitCommand(repoRoot, "merge-base", "--is-ancestor", it, currentHead) is
              WorkflowGitOperationResult.Ok
          }      }
  }
  val base = nearestAncestor ?: branchBase
  val error = when {
    branch == null ->
      "Goal-subtask review baseline recovery must run on durable child branch '$expectedBranch'."
    head == null -> "Could not resolve current HEAD for goal-subtask review baseline recovery."
    base == null ->
      "Goal-subtask review baseline recovery could not find a reachable base for unreachable sha " +
        "'$unreachableSha' on branch '$expectedBranch'."
    base == head ->
      "Recovered goal-subtask review base must differ from current HEAD."
    base == unreachableSha ->
      "Recovered goal-subtask review base unexpectedly matches the incompatible persisted base."
    else -> return GoalReviewRecoveredBaseline(recoveredBaseSha = base)
  }
  return GoalReviewRecoveredBaseline(error = error)
}

private val isDefinitiveMissingObject: (WorkflowGitOperationResult) -> Boolean =
  { result -> result.status == "error" && result.error.contains("exit code 128") }

private val isDefinitiveNonAncestor: (WorkflowGitOperationResult) -> Boolean =
  { result -> result.status == "error" && result.error.contains("exit code 1") }
