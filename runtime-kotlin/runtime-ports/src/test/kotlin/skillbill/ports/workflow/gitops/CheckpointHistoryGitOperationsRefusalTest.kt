package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class CheckpointHistoryGitOperationsRefusalTest {
  private val repoRoot: Path = Path.of("/tmp/skillbill-checkpoint-history-default")

  @Test
  fun `the test composite refuses every checkpoint history operation`() {
    val operations: WorkflowGitOperations = NoopWorkflowGitOperations
    val prefix = "refs/skill-bill/checkpoints/"

    assertFalse(operations.amendHeadCommit(repoRoot, "0".repeat(40)) is WorkflowGitOperationResult.Ok)
    assertFalse(
      operations.updateCheckpointRef(repoRoot, prefix, "${prefix}subtask-1", "0".repeat(40)) is
        WorkflowGitOperationResult.Ok,
    )
    assertFalse(
      operations.resolveCheckpointRef(repoRoot, prefix, "${prefix}subtask-1") is WorkflowGitOperationResult.Ok,
    )
    assertFalse(operations.listCheckpointRefs(repoRoot, prefix) is WorkflowGitOperationResult.Ok)
    assertFalse(operations.deleteCheckpointRef(repoRoot, prefix, "${prefix}subtask-1") is WorkflowGitOperationResult.Ok)
  }
}
