package skillbill.application.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val ISSUE = "SKILL-190"
private const val HEAD_SHA = "1111111111111111111111111111111111111111"
private const val OTHER_SHA = "2222222222222222222222222222222222222222"

class FeatureTaskRuntimeSubtaskCommitResolverTest {
  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey = ISSUE, subtaskId = "3")

  // The defect this guard exists for: an ownership-keyed amend rewrites the PREVIOUS subtask's
  // finished commit, destroying a delivered subtask on the shared branch.
  @Test
  fun `amend is keyed on this subtask's own unpushed commit and nothing else`() {
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Amend>(
      decide(durableCommitSha = HEAD_SHA, headCommitMessage = null),
    )

    // The predecessor subtask's finished commit at HEAD: runtime-written, but not ours.
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Create>(
      decide(durableCommitSha = null, headCommitMessage = "done\n\nSkill-Bill-Subtask: $ISSUE/2\n"),
    )
    // A human commit landed on top of our recorded commit.
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Create>(
      decide(durableCommitSha = OTHER_SHA, headCommitMessage = null),
    )
    // No identity at all.
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Create>(
      decide(durableCommitSha = null, headCommitMessage = "a hand-written commit\n"),
    )
    // Already published but durably ours: a reopened subtask must amend its one commit rather than
    // stack a second one on the branch, and finalisation's lease reconciles the remote.
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Amend>(
      decide(durableCommitSha = HEAD_SHA, headCommitMessage = null, headIsUnpushed = false),
    )
    // Already published and no durable pointer claims it: someone else's history.
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Create>(
      decide(
        durableCommitSha = null,
        headCommitMessage = "wip\n\nSkill-Bill-Subtask: $ISSUE/3\n",
        headIsUnpushed = false,
      ),
    )
    // Nothing to amend onto.
    assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Create>(
      decide(durableCommitSha = HEAD_SHA, headCommitMessage = null, headSha = null),
    )
  }

  // A crash that wiped the pointer must reattach to the same commit rather than open a second one,
  // and the recovery is a degradation the caller has to be able to record.
  @Test
  fun `an absent pointer recovers the amend target from the HEAD trailer and flags the fallback`() {
    val decision = assertIs<FeatureTaskRuntimeSubtaskCommitDecision.Amend>(
      decide(durableCommitSha = null, headCommitMessage = "wip\n\nSkill-Bill-Subtask: $ISSUE/3\n"),
    )

    assertEquals(HEAD_SHA, decision.ownedHeadSha)
    assertEquals(4, decision.sequenceNumber)
    assertEquals(true, decision.recoveredFromTrailer)
  }

  private fun decide(
    durableCommitSha: String?,
    headCommitMessage: String?,
    headIsUnpushed: Boolean = true,
    headSha: String? = HEAD_SHA,
  ) = FeatureTaskRuntimeSubtaskCommitResolver.decide(
    identity = identity,
    durableCommitSha = durableCommitSha,
    head = FeatureTaskRuntimeSubtaskCommitHeadState(
      sha = headSha,
      commitMessage = headCommitMessage,
      isUnpushed = headIsUnpushed,
    ),
    sequenceNumber = 4,
  )
}
