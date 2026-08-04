package skillbill.application.featuretask

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeCheckpointScopeTest {
  @Test
  fun `stages exactly the owned inventory when the tree also carries foreign dirt`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf("src/Owned.kt", "src/AlsoOwned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt"),
      foreignStagedPaths = listOf("unrelated/Foreign.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/AlsoOwned.kt", "src/Owned.kt"), stage.ownedPaths)
  }

  @Test
  fun `foreign dirt alone produces no checkpoint rather than committing someone else's work`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = emptyList(),
      phaseIntroducedPaths = emptyList(),
      foreignStagedPaths = listOf("unrelated/Foreign.kt", "unrelated/AlsoForeign.kt"),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Skip>(decision)
  }

  @Test
  fun `a path introduced outside the owned inventory blocks and names the exact path`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt", "src/Smuggled.kt"),
      foreignStagedPaths = emptyList(),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'src/Smuggled.kt'")
    assertContains(block.reason, ISSUE)
    assertTrue(block.reason.startsWith("needs_human: "), "block reason must carry a documented telemetry prefix")
  }

  @Test
  fun `a concurrently prepared foreign feature spec blocks and never enters the inventory`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf(".feature-specs/$ISSUE-scoped/spec.md"),
      phaseIntroducedPaths = listOf(
        ".feature-specs/$ISSUE-scoped/spec.md",
        ".feature-specs/OTHER-999-concurrent/spec.md",
      ),
      foreignStagedPaths = emptyList(),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'.feature-specs/OTHER-999-concurrent/spec.md'")
    assertFalse(
      block.reason.contains("$ISSUE-scoped/spec.md"),
      "the active issue's own governed spec is never reported as foreign",
    )
  }

  @Test
  fun `a foreign feature spec that is merely present and untouched produces no false block`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt"),
      foreignStagedPaths = listOf(".feature-specs/OTHER-999-concurrent/spec.md"),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
  }

  @Test
  fun `an owned path that is also foreign-staged blocks with the path and recovery guidance`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf("src/Contested.kt"),
      phaseIntroducedPaths = listOf("src/Contested.kt"),
      foreignStagedPaths = listOf("src/Contested.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'src/Contested.kt'")
    assertContains(block.reason, "git restore --staged")
    assertTrue(block.reason.startsWith("git: "), "block reason must carry a documented telemetry prefix")
  }

  @Test
  fun `case-aliased and separator-aliased overlaps are detected rather than treated as distinct paths`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf("Src/Contested.kt"),
      phaseIntroducedPaths = listOf("Src/Contested.kt"),
      foreignStagedPaths = listOf("src/contested.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'Src/Contested.kt'")
  }

  @Test
  fun `an alias of an owned path is not reported as introduced outside the inventory`() {
    val decision = FeatureTaskRuntimeCheckpointScope.decide(
      issueKey = ISSUE,
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("SRC/OWNED.KT"),
      foreignStagedPaths = emptyList(),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
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
  fun `foreign governed spec detection accepts every directory spelling of the active issue`() {
    assertFalse(FeatureTaskRuntimeCheckpointScope.isForeignGovernedSpecPath(".feature-specs/$ISSUE/spec.md", ISSUE))
    assertFalse(
      FeatureTaskRuntimeCheckpointScope.isForeignGovernedSpecPath(".feature-specs/$ISSUE-slug/spec.md", ISSUE),
    )
    assertTrue(
      FeatureTaskRuntimeCheckpointScope.isForeignGovernedSpecPath(".feature-specs/OTHER-1/spec.md", ISSUE),
    )
    // A different issue whose key merely starts with the same characters is still foreign.
    assertTrue(
      FeatureTaskRuntimeCheckpointScope.isForeignGovernedSpecPath(".feature-specs/SKILL-1500/spec.md", "SKILL-150"),
    )
    assertFalse(FeatureTaskRuntimeCheckpointScope.isForeignGovernedSpecPath("src/Owned.kt", ISSUE))
  }

  @Test
  fun `commit messages distinguish initial implementation from audit repair and review remediation`() {
    val initial = FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      branch = BRANCH,
      phaseId = "audit",
      loopId = null,
      generation = 0,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
    )
    val auditRepair = FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      branch = BRANCH,
      phaseId = "audit",
      loopId = "audit_gap",
      generation = 1,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
    )
    val reviewRemediation = FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      branch = BRANCH,
      phaseId = "review",
      loopId = "review_fix",
      generation = 2,
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

  private companion object {
    const val ISSUE = "SKILL-150"
    const val BRANCH = "feat/SKILL-150-scoped-checkpoint"
  }
}
