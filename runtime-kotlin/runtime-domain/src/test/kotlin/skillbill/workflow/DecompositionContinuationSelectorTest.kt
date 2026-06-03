package skillbill.workflow

import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionContinuationSelection
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DecompositionContinuationSelectorTest {
  @Test
  fun `select resumes in-progress subtask at last resumable step`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        resumableSubtask(1, workflowId = "wfl-1", lastResumableStep = "validate"),
        subtask(2, dependencies = listOf(DecompositionDependency(1))),
      ),
    )

    val resumed = assertIs<DecompositionContinuationSelection.Resume>(selection)
    assertEquals(1, resumed.subtask.id)
    assertEquals("wfl-1", resumed.workflowId)
    assertEquals("validate", resumed.resumeStepId)
  }

  @Test
  fun `select re-opens in-progress subtask that has no workflow id`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1, status = "complete"),
        subtask(2, status = "in_progress", dependencies = listOf(DecompositionDependency(1))),
      ),
    )

    val started = assertIs<DecompositionContinuationSelection.Start>(selection)
    assertEquals(2, started.subtask.id)
  }

  @Test
  fun `select starts first pending subtask whose dependencies are complete`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1, status = "complete"),
        subtask(2, dependencies = listOf(DecompositionDependency(1))),
      ),
    )

    val started = assertIs<DecompositionContinuationSelection.Start>(selection)
    assertEquals(2, started.subtask.id)
    assertEquals("feat/SKILL-51-demo", started.branchPlan.branch)
  }

  @Test
  fun `select honors explicit runnable subtask constraint`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1, status = "complete"),
        subtask(2, dependencies = listOf(DecompositionDependency(1))),
      ),
      requestedSubtaskId = 2,
    )

    val started = assertIs<DecompositionContinuationSelection.Start>(selection)
    assertEquals(2, started.subtask.id)
  }

  @Test
  fun `select blocks explicit subtask that is not next runnable`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1),
        subtask(2, dependencies = listOf(DecompositionDependency(1))),
      ),
      requestedSubtaskId = 2,
    )

    val blocked = assertIs<DecompositionContinuationSelection.Blocked>(selection)
    assertEquals(2, blocked.subtask.id)
    assertEquals("Requested subtask 2 is not the next runnable subtask for SKILL-51.", blocked.reason)
  }

  @Test
  fun `select reports terminal outcome for explicit complete subtask`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1, status = "complete"),
        subtask(2, status = "complete", dependencies = listOf(DecompositionDependency(1))),
      ),
      requestedSubtaskId = 2,
    )

    val terminal = assertIs<DecompositionContinuationSelection.TerminalSubtask>(selection)
    assertEquals(2, terminal.subtask.id)
  }

  @Test
  fun `select reports terminal outcome for explicit complete subtask while later work remains`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1, status = "complete"),
        subtask(2, dependencies = listOf(DecompositionDependency(1))),
      ),
      requestedSubtaskId = 1,
    )

    val terminal = assertIs<DecompositionContinuationSelection.TerminalSubtask>(selection)
    assertEquals(1, terminal.subtask.id)
  }

  @Test
  fun `select reports blocked subtask when dependent pending work is not explicitly skippable`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        blockedSubtask(1, "Needs API decision."),
        subtask(2, dependencies = listOf(DecompositionDependency(1))),
      ),
    )

    val blocked = assertIs<DecompositionContinuationSelection.Blocked>(selection)
    assertEquals(1, blocked.subtask.id)
    assertEquals("Needs API decision.", blocked.reason)
  }

  @Test
  fun `select allows explicit optional skipped dependency`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        blockedSubtask(1, "Optional path paused."),
        subtask(2, dependencies = listOf(DecompositionDependency(1, optional = true, skipped = true))),
      ),
    )

    val started = assertIs<DecompositionContinuationSelection.Start>(selection)
    assertEquals(2, started.subtask.id)
  }

  @Test
  fun `select uses stacked branch plan when manifest opts in`() {
    val selection = DecompositionContinuationSelector.select(
      manifest(
        subtask(1),
        executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
        featureBranch = null,
        stackBranches = listOf(DecompositionStackBranch(1, "feat/SKILL-51-demo-1", "main")),
      ),
    )

    val started = assertIs<DecompositionContinuationSelection.Start>(selection)
    assertEquals("feat/SKILL-51-demo-1", started.branchPlan.branch)
    assertEquals("main", started.branchPlan.baseBranch)
    assertEquals(true, started.branchPlan.validateBase)
  }

  private fun manifest(
    vararg subtasks: DecompositionSubtask,
    executionModel: DecompositionExecutionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
    featureBranch: String? = "feat/SKILL-51-demo",
    stackBranches: List<DecompositionStackBranch> = emptyList(),
  ): DecompositionManifest = DecompositionManifest(
    issueKey = "SKILL-51",
    featureName = "demo",
    parentSpecPath = ".feature-specs/SKILL-51-demo/spec.md",
    executionModel = executionModel,
    baseBranch = "main",
    featureBranch = featureBranch,
    stackBranches = stackBranches,
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
    subtasks = subtasks.toList(),
  )

  private fun subtask(
    id: Int,
    status: String = "pending",
    dependencies: List<DecompositionDependency> = emptyList(),
  ): DecompositionSubtask = DecompositionSubtask(
    id = id,
    name = "subtask-$id",
    specPath = ".feature-specs/SKILL-51-demo/spec_subtask_$id.md",
    status = status,
    dependencies = dependencies,
  )

  private fun resumableSubtask(id: Int, workflowId: String, lastResumableStep: String): DecompositionSubtask =
    subtask(id, status = "in_progress").copy(
      workflowId = workflowId,
      lastResumableStep = lastResumableStep,
    )

  private fun blockedSubtask(id: Int, reason: String): DecompositionSubtask =
    subtask(id, status = "blocked").copy(blockedReason = reason)
}
