package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubtaskCommitWriteContextTest {
  private val branch = "feat/SKILL-236-review-evidence-recovery"
  private val baseSha = "a".repeat(40)
  private val otherSha = "b".repeat(40)
  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity("SKILL-236", "1")

  @Test
  fun `an unpublished branch at its recorded base creates its first commit without amending the base`() {
    listOf("Published base commit", "Previous subtask\n\nSkill-Bill-Subtask: SKILL-236/0").forEach { message ->
      val context = context().copy(headMessage = message)

      assertNull(context.ownershipFailure(true, branch, identity))
      assertIs<FeatureTaskRuntimeSubtaskCommitCreate>(
        FeatureTaskRuntimeSubtaskCommitResolver.decide(
          identity,
          context.ledger?.commitSha,
          FeatureTaskRuntimeSubtaskCommitHeadState(context.headSha, context.headMessage, context.unpushed),
          0,
        ),
      )
    }
  }

  @Test
  fun `an unexpected unowned commit or missing durable base still blocks the first checkpoint`() {
    val context = context()
    listOf(
      context.copy(headSha = otherSha),
      context.copy(resolvedBranch = null),
      context.copy(resolvedBranch = context.resolvedBranch?.copy(reviewBaseSha = null)),
    ).forEach { candidate ->
      assertNotNull(candidate.ownershipFailure(true, branch, identity))
    }
    assertNotNull(context.ownershipFailure(false, branch, identity))
  }

  @Test
  fun `base equality cannot bypass checked out or recorded branch ownership`() {
    val context = context()
    assertNotNull(context.ownershipFailure(true, "feat/another", identity))
    assertNotNull(
      context.copy(resolvedBranch = context.resolvedBranch?.copy(branch = "feat/another"))
        .ownershipFailure(true, branch, identity),
    )
    assertNotNull(
      context.copy(ledger = SubtaskCommitLedgerState(null, 0, "feat/another"))
        .ownershipFailure(true, branch, identity),
    )
  }

  @Test
  fun `a recorded subtask commit still requires its exact head and matching trailer`() {
    val context = context().copy(ledger = SubtaskCommitLedgerState(baseSha, 1, branch))
    assertNotNull(context.ownershipFailure(true, branch, identity))
    assertNotNull(
      context.copy(headSha = otherSha, headMessage = "Owned\n\n${identity.trailer}")
        .ownershipFailure(true, branch, identity),
    )
  }

  private fun context() = SubtaskCommitWriteContext(
    ledger = SubtaskCommitLedgerState(null, 0),
    headSha = baseSha,
    headOk = true,
    unpushed = true,
    unpushedOk = true,
    headMessage = "Published base commit",
    currentBranch = branch to (true to null),
    resolvedBranch = FeatureTaskRuntimeResolvedBranch(branch, "main", true, baseSha),
  )
}
