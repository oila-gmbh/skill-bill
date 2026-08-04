package skillbill.application.featuretask

import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NUL: Char = '\u0000'

private class OwnedPathsGitOperations(private val result: WorkflowGitOperationResult) :
  WorkflowGitOperations by NoopWorkflowGitOperations,
  RepositoryOwnedPathsGitOperationsProvider {
  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations =
    object : RepositoryOwnedPathsGitOperations {
      override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult = result
    }
}

class FeatureTaskRuntimeScopedReviewBaselineTest {
  private val repoRoot: Path = Path.of("/tmp/scoped-review-baseline")
  private val baseSha = "a".repeat(40)

  private fun resolved() = FeatureTaskRuntimeResolvedBranch(
    branch = "feat/SKILL-150",
    baselineUntrackedPaths = listOf("baseline/pre-existing.txt"),
    workflowOwnedPaths = listOf("src/Owned.kt", "untracked/owned-new.kt"),
  )

  @Test
  fun `scoped baseline carries the owned inventory and excludes foreign untracked paths`() {
    val git = OwnedPathsGitOperations(
      WorkflowGitOperationResult(
        status = "ok",
        value = listOf("untracked/owned-new.kt", "foreign/sibling.kt", ".feature-specs/OTHER-1/spec.md")
          .joinToString(NUL.toString()),
      ),
    )

    val baseline = FeatureTaskRuntimeScopedReviewBaseline.of(git, repoRoot, resolved(), baseSha)

    assertEquals(listOf("src/Owned.kt", "untracked/owned-new.kt"), baseline.ownedPathspec)
    assertEquals(
      listOf(".feature-specs/OTHER-1/spec.md", "baseline/pre-existing.txt", "foreign/sibling.kt"),
      baseline.baselineUntrackedPaths,
    )
  }

  @Test
  fun `an unreadable owned-path listing falls back to the durable baseline instead of widening`() {
    val git = OwnedPathsGitOperations(WorkflowGitOperationResult(status = "error", error = "git failed"))

    val baseline = FeatureTaskRuntimeScopedReviewBaseline.of(git, repoRoot, resolved(), baseSha)

    assertEquals(listOf("baseline/pre-existing.txt"), baseline.baselineUntrackedPaths)
    assertEquals(listOf("src/Owned.kt", "untracked/owned-new.kt"), baseline.ownedPathspec)
  }
}
