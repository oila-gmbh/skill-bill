package skillbill.infrastructure.fs

import skillbill.ports.workflow.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import java.nio.file.Path

private const val GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES: Int = 1_000_000

internal object GitGoalSubtaskReviewOperations : GoalSubtaskReviewGitOperations {
  override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult {
    val stable = stableSnapshot(repoRoot, expectedBranch, ::baselineSnapshot)
    val snapshot = stable.snapshot
    return if (snapshot == null) {
      GoalSubtaskReviewBaselineResult(status = "error", error = stable.error)
    } else {
      GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = GoalSubtaskReviewBaseline(snapshot.headSha, snapshot.untrackedPaths),
      )
    }
  }

  override fun buildInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewInputResult {
    val stable = stableSnapshot(repoRoot, expectedBranch) { root, branch ->
      reviewInputSnapshot(root, baseline, branch)
    }
    val snapshot = stable.snapshot
    return if (snapshot == null) {
      GoalSubtaskReviewInputResult(status = "error", error = stable.error, failureReason = stable.failureReason)
    } else {
      GoalSubtaskReviewInputResult(
        status = "ok",
        input = GoalSubtaskReviewInput(
          reviewBaseSha = baseline.reviewBaseSha,
          currentHeadSha = snapshot.headSha,
          trackedDelta = snapshot.trackedDelta,
          ownedUntrackedPatches = snapshot.ownedUntrackedPatches,
        ),
      )
    }
  }

  override fun recoverBaseline(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult {
    val stable = stableSnapshot(repoRoot, expectedBranch) { root, branch ->
      recoveredBaselineSnapshot(root, baseline, branch)
    }
    val snapshot = stable.snapshot
    return if (snapshot == null) {
      GoalSubtaskReviewBaselineResult(status = "error", error = stable.error)
    } else {
      GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = GoalSubtaskReviewBaseline(snapshot.recoveredBaseSha, baseline.baselineUntrackedPaths),
      )
    }
  }
}

private fun <T> stableSnapshot(
  repoRoot: Path,
  expectedBranch: String,
  readSnapshot: (Path, String) -> GoalReviewSnapshotResult<T>,
): GoalReviewSnapshotResult<T> {
  val expected = expectedBranch.trim()
  if (expected.isBlank()) {
    return GoalReviewSnapshotResult(error = "Goal-subtask durable child branch is required.")
  }
  val first = readSnapshot(repoRoot, expected)
  val second = if (first.ok) readSnapshot(repoRoot, expected) else first
  return when {
    !first.ok -> first
    !second.ok -> second
    first.snapshot != second.snapshot -> GoalReviewSnapshotResult(
      error =
      "Goal-subtask repository state changed while preparing its immutable review state; " +
        "refusing a torn snapshot.",
    )
    else -> first
  }
}

private data class GoalReviewBaselineSnapshot(
  val branch: String,
  val headSha: String,
  val indexTree: String,
  val trackedDelta: String,
  val untrackedPaths: List<String>,
)

private data class GoalReviewInputSnapshot(
  val branch: String,
  val headSha: String,
  val indexTree: String,
  val trackedDelta: String,
  val untrackedPaths: List<String>,
  val ownedUntrackedPatches: String,
)

private data class GoalReviewRecoveredBaselineSnapshot(
  val branch: String,
  val headSha: String,
  val recoveredBaseSha: String,
)

private data class GoalReviewSnapshotResult<T>(
  val snapshot: T? = null,
  val error: String = "",
  val failureReason: GoalSubtaskReviewInputFailureReason? = null,
) {
  val ok: Boolean get() = snapshot != null && error.isBlank()
}

private fun baselineSnapshot(
  repoRoot: Path,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewBaselineSnapshot> {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val cleanError = branch?.let { trackedWorktreeClean(repoRoot) }
  val head = branch?.takeIf { cleanError == null }?.let {
    goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()
  }
  val indexTree = head?.takeIf(String::isNotBlank)?.let {
    goalReviewGitValue(repoRoot, "write-tree")?.trim()
  }
  val tracked = indexTree?.takeIf(String::isNotBlank)?.let {
    goalReviewGitValue(repoRoot, "diff", "--binary", "HEAD")
  }
  val untracked = tracked?.let { goalReviewUntrackedPaths(repoRoot) }
  val error = when {
    branch == null ->
      "Goal-subtask review baseline must be captured on durable child branch '$expectedBranch'."
    cleanError != null -> cleanError
    head.isNullOrBlank() -> "Could not resolve HEAD."
    indexTree.isNullOrBlank() ->
      "Could not resolve the git index while capturing the immutable review baseline."
    tracked == null ->
      "Could not read tracked worktree state while capturing the immutable review baseline."
    untracked == null ->
      "Could not read untracked inventory while capturing the immutable review baseline."
    else -> null
  }
  return if (error != null) {
    GoalReviewSnapshotResult(error = error)
  } else {
    GoalReviewSnapshotResult(
      snapshot = GoalReviewBaselineSnapshot(
        branch = requireNotNull(branch),
        headSha = requireNotNull(head),
        indexTree = requireNotNull(indexTree),
        trackedDelta = requireNotNull(tracked),
        untrackedPaths = requireNotNull(untracked),
      ),
    )
  }
}

private fun reviewInputSnapshot(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewInputSnapshot> {
  val material = materializeReviewInput(repoRoot, baseline, expectedBranch)
  val failure = reviewInputFailure(material, baseline, expectedBranch)
  return if (failure != null) {
    GoalReviewSnapshotResult(error = failure.message, failureReason = failure.reason)
  } else {
    GoalReviewSnapshotResult(snapshot = material.toSnapshot())
  }
}

private fun recoveredBaselineSnapshot(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewSnapshotResult<GoalReviewRecoveredBaselineSnapshot> {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val head = branch?.let {
    goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
  }
  val base = head?.let { currentHead ->
    listOf("origin/main", "main")
      .filter { runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", it).ok }
      .distinct()
      .firstNotNullOfOrNull { candidate ->
        goalReviewGitValue(repoRoot, "merge-base", candidate, currentHead)
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.takeIf { runGitCommand(repoRoot, "merge-base", "--is-ancestor", it, currentHead).ok }
      }
  }
  val error = when {
    branch == null ->
      "Goal-subtask review baseline recovery must run on durable child branch '$expectedBranch'."
    head == null -> "Could not resolve current HEAD for goal-subtask review baseline recovery."
    base == null ->
      "Goal-subtask review baseline recovery could not find a branch base for '$expectedBranch'."
    base == head ->
      "Recovered goal-subtask review base must differ from current HEAD."
    base == baseline.reviewBaseSha ->
      "Recovered goal-subtask review base unexpectedly matches the incompatible persisted base."
    else -> null
  }
  return if (error != null) {
    GoalReviewSnapshotResult(error = error)
  } else {
    GoalReviewSnapshotResult(
      snapshot = GoalReviewRecoveredBaselineSnapshot(
        branch = requireNotNull(branch),
        headSha = requireNotNull(head),
        recoveredBaseSha = requireNotNull(base),
      ),
    )
  }
}

private fun materializeReviewInput(
  repoRoot: Path,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewInputMaterial {
  val branch = currentGoalReviewBranch(repoRoot, expectedBranch)
  val head = branch?.let {
    goalReviewGitValue(repoRoot, "rev-parse", "HEAD")?.trim()?.takeIf(String::isNotBlank)
  }
  val baseExists = head?.let {
    runGitCommand(repoRoot, "cat-file", "-e", "${baseline.reviewBaseSha}^{commit}")
  }
  val baseIsAncestor = baseExists?.takeIf { it.ok }?.let {
    runGitCommand(repoRoot, "merge-base", "--is-ancestor", baseline.reviewBaseSha, head)
  }
  val indexTree = baseIsAncestor?.takeIf { it.ok }?.let {
    goalReviewGitValue(repoRoot, "write-tree")?.trim()
  }
  val trackedDelta = indexTree?.takeIf(String::isNotBlank)?.let {
    goalReviewGitValue(repoRoot, "diff", "--binary", baseline.reviewBaseSha)
  }
  val untracked = trackedDelta?.let { goalReviewUntrackedPaths(repoRoot) }
  val patches = untracked?.let { paths ->
    ownedUntrackedPatches(repoRoot, paths.filterNot { it in baseline.baselineUntrackedPaths })
  }
  val totalBytes = trackedDelta?.toByteArray()?.size?.plus(patches?.value?.toByteArray()?.size ?: 0)
  return GoalReviewInputMaterial(
    branch,
    head,
    baseExists,
    baseIsAncestor,
    indexTree,
    trackedDelta,
    untracked,
    patches,
    totalBytes,
  )
}

private data class GoalReviewInputMaterial(
  val branch: String?,
  val head: String?,
  val baseExists: skillbill.ports.workflow.model.WorkflowGitOperationResult?,
  val baseIsAncestor: skillbill.ports.workflow.model.WorkflowGitOperationResult?,
  val indexTree: String?,
  val trackedDelta: String?,
  val untracked: List<String>?,
  val patches: GoalReviewStringResult?,
  val totalBytes: Int?,
)

private data class GoalReviewInputFailure(
  val message: String,
  val reason: GoalSubtaskReviewInputFailureReason? = null,
)

private fun reviewInputFailure(
  material: GoalReviewInputMaterial,
  baseline: GoalSubtaskReviewBaseline,
  expectedBranch: String,
): GoalReviewInputFailure? = when {
  material.branch == null ->
    GoalReviewInputFailure("Goal-subtask review must run on durable child branch '$expectedBranch'.")
  material.head == null -> GoalReviewInputFailure("Could not resolve current HEAD.")
  material.baseExists?.ok != true ->
    GoalReviewInputFailure(
      "Persisted review base '${baseline.reviewBaseSha}' is not an existing commit: " +
        material.baseExists?.error.orEmpty(),
      material.baseExists?.takeIf(isDefinitiveMissingObject)?.let {
        GoalSubtaskReviewInputFailureReason.BASE_MISSING
      },
    )
  material.baseIsAncestor?.ok != true ->
    GoalReviewInputFailure(
      "Persisted review base '${baseline.reviewBaseSha}' is not an ancestor of current HEAD; " +
        "refusing a broader review scope.",
      material.baseIsAncestor?.takeIf(isDefinitiveNonAncestor)?.let {
        GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR
      },
    )
  material.indexTree.isNullOrBlank() ->
    GoalReviewInputFailure("Could not resolve the git index while materializing the exact review input.")
  material.trackedDelta == null ->
    GoalReviewInputFailure("Could not materialize tracked changes from the immutable review base.")
  material.untracked == null ->
    GoalReviewInputFailure("Could not read untracked inventory while materializing the exact review input.")
  material.patches?.ok != true -> GoalReviewInputFailure(material.patches?.error.orEmpty())
  material.totalBytes != null && material.totalBytes > GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES ->
    GoalReviewInputFailure("Goal-subtask review input exceeds the ${GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES}-byte bound.")
  else -> null
}

private val isDefinitiveMissingObject: (skillbill.ports.workflow.model.WorkflowGitOperationResult) -> Boolean =
  { result -> result.status == "error" && result.error.contains("exit code 128") }

private val isDefinitiveNonAncestor: (skillbill.ports.workflow.model.WorkflowGitOperationResult) -> Boolean =
  { result -> result.status == "error" && result.error.contains("exit code 1") }

private fun GoalReviewInputMaterial.toSnapshot(): GoalReviewInputSnapshot = GoalReviewInputSnapshot(
  branch = requireNotNull(branch),
  headSha = requireNotNull(head),
  indexTree = requireNotNull(indexTree),
  trackedDelta = requireNotNull(trackedDelta),
  untrackedPaths = requireNotNull(untracked),
  ownedUntrackedPatches = requireNotNull(patches?.value),
)

private data class GoalReviewStringResult(
  val value: String? = null,
  val error: String = "",
) {
  val ok: Boolean get() = value != null && error.isBlank()
}

private fun ownedUntrackedPatches(repoRoot: Path, paths: List<String>): GoalReviewStringResult {
  val patches = StringBuilder()
  paths.forEach { path ->
    val patch = runGitProcess(repoRoot, listOf("diff", "--binary", "--no-index", "/dev/null", path))
    if (patch.timedOut || patch.readFailure != null || patch.exitCode !in setOf(0, 1)) {
      return GoalReviewStringResult(
        error = patch.readFailure?.message ?: "Could not diff owned untracked path '$path'.",
      )
    }
    patches.append(patch.output)
    if (!patches.endsWith("\n")) patches.append('\n')
    if (patches.toString().toByteArray().size > GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES) {
      return GoalReviewStringResult(
        error = "Goal-subtask review input exceeds the ${GOAL_SUBTASK_REVIEW_INPUT_MAX_BYTES}-byte bound.",
      )
    }
  }
  return GoalReviewStringResult(value = patches.toString())
}

// The baseline is the subtask's review floor: capturing it while tracked changes are already present
// folds unrelated pre-existing work into every diff the reviewer sees.
private fun trackedWorktreeClean(repoRoot: Path): String? {
  val unstaged = runGitProcess(repoRoot, listOf("diff", "--quiet"))
  if (unstaged.timedOut || unstaged.readFailure != null || unstaged.exitCode !in setOf(0, 1)) {
    return unstaged.readFailure?.message ?: "Could not inspect unstaged tracked changes before review baseline capture."
  }
  if (unstaged.exitCode == 1) {
    return "Goal-subtask review baseline capture requires a clean tracked worktree; " +
      "unstaged tracked changes are present."
  }
  val staged = runGitProcess(repoRoot, listOf("diff", "--cached", "--quiet"))
  if (staged.timedOut || staged.readFailure != null || staged.exitCode !in setOf(0, 1)) {
    return staged.readFailure?.message ?: "Could not inspect staged tracked changes before review baseline capture."
  }
  return if (staged.exitCode == 1) {
    "Goal-subtask review baseline capture requires a clean tracked worktree; staged tracked changes are present."
  } else {
    null
  }
}
