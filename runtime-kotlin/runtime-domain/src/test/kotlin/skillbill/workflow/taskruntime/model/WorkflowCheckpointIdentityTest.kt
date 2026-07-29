package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowCheckpointIdentityTest {
  @Test
  fun `checkpoint identity round trips every authority field`() {
    val identity = WorkflowCheckpointIdentity(
      branch = "feat/SKILL-150",
      phase = "implement",
      loop = "initial",
      generation = 1,
      parentSha = "a".repeat(40),
      ownedPathDigest = "b".repeat(64),
      ownedPaths = listOf("runtime-kotlin/owned.kt"),
      commitSha = "c".repeat(40),
    )

    assertEquals(identity, WorkflowCheckpointIdentity.fromArtifactMap(identity.toArtifactMap()))
  }

  @Test
  fun `checkpoint identity rejects an invalid digest`() {
    assertFailsWith<IllegalArgumentException> {
      WorkflowCheckpointIdentity(
        branch = "feat/SKILL-150",
        phase = "implement",
        loop = "initial",
        generation = 1,
        parentSha = "a".repeat(40),
        ownedPathDigest = "not-a-digest",
        ownedPaths = listOf("runtime-kotlin/owned.kt"),
        commitSha = "c".repeat(40),
      )
    }
  }
}
