package skillbill.workflow.taskruntime

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.featureTaskRuntimeAppendCheckpointIdentity
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeOwnedPathDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FeatureTaskRuntimeCheckpointIdentityModelsTest {
  @Test
  fun `an identity round-trips through the durable artifact shape`() {
    val identity = identity(sequenceNumber = 3)

    val decoded = featureTaskRuntimeCheckpointIdentitiesFromArtifact(
      featureTaskRuntimeCheckpointIdentitiesToArtifact(listOf(identity)),
    )

    assertEquals(listOf(identity), decoded)
  }

  @Test
  fun `an absent artifact decodes to an empty history so a legacy workflow needs no migration`() {
    assertEquals(emptyList(), featureTaskRuntimeCheckpointIdentitiesFromArtifact(null))
  }

  @Test
  fun `an unsupported contract version loud-fails so the store is quarantined and regenerated`() {
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        mapOf("contract_version" to "0.0", "checkpoints" to emptyList<Any?>()),
      )
    }

    assertContains(error.message.orEmpty(), "unsupported contract version")
  }

  @Test
  fun `an unsupported field loud-fails rather than being reinterpreted`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        mapOf(
          "contract_version" to "0.1",
          "checkpoints" to listOf(identity().toArtifactMap() + ("raw_prompt" to "…")),
        ),
      )
    }
  }

  @Test
  fun `a duplicated commit sha loud-fails because one commit yields exactly one record`() {
    val duplicated = listOf(identity(sequenceNumber = 0), identity(sequenceNumber = 1))

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        mapOf("contract_version" to "0.1", "checkpoints" to duplicated.map { it.toArtifactMap() }),
      )
    }

    assertContains(error.message.orEmpty(), "more than once")
  }

  @Test
  fun `re-appending an already-recorded commit is a no-op so crash resume converges`() {
    val first = identity(sequenceNumber = 0)
    val history = featureTaskRuntimeAppendCheckpointIdentity(emptyList(), first)

    val resumed = featureTaskRuntimeAppendCheckpointIdentity(history, identity(sequenceNumber = 1))

    assertEquals(history, resumed)
    assertEquals(1, resumed.size)
  }

  @Test
  fun `appending prunes oldest-first at the retention limit`() {
    val history = (0 until FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT).fold(
      emptyList<FeatureTaskRuntimeCheckpointIdentity>(),
    ) { acc, index ->
      featureTaskRuntimeAppendCheckpointIdentity(acc, identity(sequenceNumber = index, commitSuffix = index))
    }
    assertEquals(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT, history.size)

    val overflowed = featureTaskRuntimeAppendCheckpointIdentity(
      history,
      identity(sequenceNumber = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT, commitSuffix = 9_999),
    )

    assertEquals(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT, overflowed.size)
    assertEquals(1, overflowed.first().sequenceNumber, "the oldest entry is the one dropped")
  }

  @Test
  fun `the owned-path digest is order-independent but content-sensitive`() {
    val ordered = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt", "b/Two.kt"))
    val reordered = featureTaskRuntimeOwnedPathDigest(listOf("b/Two.kt", "a/One.kt"))
    val different = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt", "b/Three.kt"))

    assertEquals(ordered, reordered)
    assertNotEquals(ordered, different)
  }

  @Test
  fun `a path containing the join delimiter cannot forge another inventory's digest`() {
    val split = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt", "b/Two.kt"))
    val joined = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt\u0000b/Two.kt"))

    assertNotEquals(split, joined)
  }

  private fun identity(
    sequenceNumber: Int = 0,
    commitSuffix: Int = 1,
  ): FeatureTaskRuntimeCheckpointIdentity = FeatureTaskRuntimeCheckpointIdentity(
    sequenceNumber = sequenceNumber,
    issueKey = "SKILL-150",
    branch = "feat/SKILL-150-scoped-checkpoint",
    phaseId = "audit",
    generation = 1,
    ownedPathDigest = featureTaskRuntimeOwnedPathDigest(listOf("src/Owned.kt")),
    ownedPathCount = 1,
    commitSha = commitSuffix.toString(16).padStart(40, '0'),
    recordedAt = "2026-08-04T00:00:00Z",
    loopId = "audit_gap",
    parentSha = "b".repeat(40),
  )
}
