package skillbill.ports.workflow.gitops

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class CheckpointHistoryGitOperationsRefusalTest {
  private val repoRoot: Path = Path.of("/tmp/skillbill-checkpoint-history-default")

  @Test
  fun `the test composite refuses every checkpoint history operation`() {
    val operations: WorkflowGitOperations = NoopWorkflowGitOperations
    val prefix = "refs/skill-bill/checkpoints/"

    assertFalse(operations.amendHeadCommit(repoRoot, "0".repeat(40)).ok)
    assertFalse(operations.updateCheckpointRef(repoRoot, prefix, "${prefix}subtask-1", "0".repeat(40)).ok)
    assertFalse(operations.resolveCheckpointRef(repoRoot, prefix, "${prefix}subtask-1").ok)
    assertFalse(operations.listCheckpointRefs(repoRoot, prefix).ok)
    assertFalse(operations.deleteCheckpointRef(repoRoot, prefix, "${prefix}subtask-1").ok)
  }
}
