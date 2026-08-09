package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FeatureTaskRuntimeCheckpointScopeTest {
  @Test
  fun `stages the whole worktree delta, including paths the run does not own`() {
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      worktreeDeltaPaths = listOf("src/Owned.kt", "unrelated/AppearedSince.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt", "unrelated/AppearedSince.kt"), stage.stagedPaths)
  }

  // The reason the ownership gate is gone: a human adds files mid-run and expects them committed.
  @Test
  fun `a file a human added mid-run is committed rather than blocking the run`() {
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      worktreeDeltaPaths = listOf("src/Owned.kt", "notes/HumanAdded.md"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertContains(stage.stagedPaths, "notes/HumanAdded.md")
  }

  @Test
  fun `a concurrently prepared foreign feature spec is committed rather than blocking`() {
    val decision = decide(
      ownedPaths = listOf(".feature-specs/$ISSUE-scoped/spec.md"),
      worktreeDeltaPaths = listOf(
        ".feature-specs/$ISSUE-scoped/spec.md",
        ".feature-specs/OTHER-999-concurrent/spec.md",
      ),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertContains(stage.stagedPaths, ".feature-specs/OTHER-999-concurrent/spec.md")
  }

  @Test
  fun `an empty worktree delta is the only path to no checkpoint`() {
    assertIs<FeatureTaskRuntimeCheckpointDecision.Skip>(
      decide(ownedPaths = listOf("src/Owned.kt"), worktreeDeltaPaths = emptyList()),
    )
  }

  @Test
  fun `foreign dirt alone is still a checkpoint now that ownership no longer bounds staging`() {
    val decision = decide(
      ownedPaths = emptyList(),
      worktreeDeltaPaths = emptyList(),
      foreignStagedPaths = listOf("unrelated/Foreign.kt", "unrelated/AlsoForeign.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("unrelated/AlsoForeign.kt", "unrelated/Foreign.kt"), stage.stagedPaths)
  }

  // A diverged path can sit outside the delta, so the union is what keeps it from being dropped.
  @Test
  fun `a diverged path outside the working-tree delta is still staged`() {
    val decision = decide(
      ownedPaths = listOf("src/Contested.kt"),
      worktreeDeltaPaths = emptyList(),
      concurrentlyModifiedOwnedPaths = listOf("src/Contested.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Contested.kt"), stage.stagedPaths)
  }

  @Test
  fun `a path appearing in both the delta and the staged set is staged once`() {
    val decision = decide(
      ownedPaths = listOf("src/Contested.kt"),
      worktreeDeltaPaths = listOf("src/Contested.kt"),
      foreignStagedPaths = listOf("src/Contested.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Contested.kt"), stage.stagedPaths)
  }

  // AC-002: the phase's own manifest, not the whole since-baseline listing, names its writes.
  @Test
  fun `phase-written paths are the delta the phase's own file manifest accounts for`() {
    val written = FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(
      worktreeDeltaPaths = listOf("src/Owned.kt", "unrelated/SiblingAgentWrote.kt", "new/dir/Nested.kt"),
      phaseManifestPaths = listOf("src/Owned.kt", "new/dir"),
    )

    assertEquals(listOf("new/dir/Nested.kt", "src/Owned.kt"), written)
  }

  @Test
  fun `phase-written paths are empty when the phase's manifest accounts for nothing`() {
    assertEquals(
      emptyList(),
      FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(
        worktreeDeltaPaths = listOf("unrelated/SiblingAgentWrote.kt"),
        phaseManifestPaths = emptyList(),
      ),
    )
  }

  @Test
  fun `review exclusions widen the baseline to every untracked path the run does not own`() {
    val exclusions = FeatureTaskRuntimeCheckpointScope.reviewUntrackedExclusions(
      baselineUntrackedPaths = listOf("pre/Existing.kt"),
      currentUntrackedPaths = listOf(
        "pre/Existing.kt",
        "src/Owned.kt",
        "unrelated/AppearedSince.kt",
        ".feature-specs/OTHER-999-concurrent/spec.md",
      ),
      ownedPaths = listOf("src/Owned.kt"),
    )

    assertEquals(
      listOf(".feature-specs/OTHER-999-concurrent/spec.md", "pre/Existing.kt", "unrelated/AppearedSince.kt"),
      exclusions,
    )
    assertFalse("src/Owned.kt" in exclusions, "an owned path must stay inside the review scope")
  }

  @Test
  fun `commit messages distinguish initial implementation from audit repair and review remediation`() {
    val initial = FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      branch = BRANCH,
      identity = FeatureTaskRuntimeCheckpointIdentity(phaseId = "audit", loopId = null, generation = 0),
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
    )
    val auditRepair = FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      branch = BRANCH,
      identity = FeatureTaskRuntimeCheckpointIdentity(phaseId = "audit", loopId = "audit_gap", generation = 1),
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
    )
    val reviewRemediation = FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      branch = BRANCH,
      identity = FeatureTaskRuntimeCheckpointIdentity(phaseId = "review", loopId = "review_fix", generation = 2),
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
    )

    assertEquals(3, setOf(initial, auditRepair, reviewRemediation).size)
    listOf(initial, auditRepair, reviewRemediation).forEach { message ->
      assertContains(message, ISSUE)
      assertContains(message, BRANCH)
    }
    assertContains(auditRepair, "loop=audit_gap")
    assertContains(auditRepair, "generation=1")
    assertContains(reviewRemediation, "loop=review_fix")
    assertContains(reviewRemediation, "generation=2")
  }

  // The delta defaults to the owned inventory, so each case names only what it is about.
  private fun decide(
    ownedPaths: List<String>,
    worktreeDeltaPaths: List<String> = ownedPaths,
    foreignStagedPaths: List<String> = emptyList(),
    concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
  ): FeatureTaskRuntimeCheckpointDecision = FeatureTaskRuntimeCheckpointScope.decide(
    FeatureTaskRuntimeCheckpointScopeInput(
      issueKey = ISSUE,
      ownedPaths = ownedPaths,
      worktreeDeltaPaths = worktreeDeltaPaths,
      foreignStagedPaths = foreignStagedPaths,
      concurrentlyModifiedOwnedPaths = concurrentlyModifiedOwnedPaths,
    ),
  )

  private companion object {
    const val ISSUE = "SKILL-150"
    const val BRANCH = "feat/SKILL-150-scoped-checkpoint"
  }
}
