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
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt", "src/AlsoOwned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt"),
      foreignStagedPaths = listOf("unrelated/Foreign.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/AlsoOwned.kt", "src/Owned.kt"), stage.ownedPaths)
  }

  // AC-001: ownership is the durable inventory, never "whatever is dirty since the baseline".
  @Test
  fun `a dirty path outside the durable inventory is never staged by virtue of being dirty`() {
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt"),
      worktreeDeltaPaths = listOf("src/Owned.kt", "unrelated/AppearedSince.kt"),
    )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt"), stage.ownedPaths)
  }

  // AC-002: an owned inventory with nothing left to stage skips rather than committing foreign dirt.
  @Test
  fun `an inventory with no working-tree delta produces no checkpoint`() {
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = emptyList(),
      worktreeDeltaPaths = listOf("unrelated/ForeignOnly.kt"),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Skip>(decision)
  }

  @Test
  fun `foreign dirt alone produces no checkpoint rather than committing someone else's work`() {
    val decision = decide(
      ownedPaths = emptyList(),
      phaseIntroducedPaths = emptyList(),
      foreignStagedPaths = listOf("unrelated/Foreign.kt", "unrelated/AlsoForeign.kt"),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Skip>(decision)
  }

  // AC-003: the block is reachable because the inventory no longer contains the phase delta.
  @Test
  fun `a path introduced outside the owned inventory blocks and names the exact path`() {
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt", "src/Smuggled.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'src/Smuggled.kt'")
    assertContains(block.reason, ISSUE)
    assertTrue(block.reason.startsWith("needs_human: "), "block reason must carry a documented telemetry prefix")
  }

  @Test
  fun `a concurrently prepared foreign feature spec blocks and never enters the inventory`() {
    val decision = decide(
      ownedPaths = listOf(".feature-specs/$ISSUE-scoped/spec.md"),
      phaseIntroducedPaths = listOf(
        ".feature-specs/$ISSUE-scoped/spec.md",
        ".feature-specs/OTHER-999-concurrent/spec.md",
      ),
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
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("src/Owned.kt"),
      foreignStagedPaths = listOf(".feature-specs/OTHER-999-concurrent/spec.md"),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
  }

  @Test
  fun `an owned path that is also foreign-staged blocks with the path and recovery guidance`() {
    val decision = decide(
      ownedPaths = listOf("src/Contested.kt"),
      phaseIntroducedPaths = listOf("src/Contested.kt"),
      foreignStagedPaths = listOf("src/Contested.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'src/Contested.kt'")
    assertContains(block.reason, "git restore --staged")
    assertTrue(block.reason.startsWith("git: "), "block reason must carry a documented telemetry prefix")
  }

  // AC-005: the unstaged half — an owned file whose content changed after the phase wrote it.
  @Test
  fun `an owned path modified concurrently without staging blocks with the path and recovery guidance`() {
    val decision = decide(
      ownedPaths = listOf("src/Contested.kt", "src/Owned.kt"),
      phaseIntroducedPaths = listOf("src/Contested.kt", "src/Owned.kt"),
      concurrentlyModifiedOwnedPaths = listOf("src/Contested.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'src/Contested.kt'")
    assertContains(block.reason, "git diff -- <path>")
    assertFalse(block.reason.contains("'src/Owned.kt'"), "only the contested path is reported")
    assertTrue(block.reason.startsWith("git: "), "block reason must carry a documented telemetry prefix")
  }

  @Test
  fun `case-aliased and separator-aliased overlaps are detected rather than treated as distinct paths`() {
    val decision = decide(
      ownedPaths = listOf("Src/Contested.kt"),
      phaseIntroducedPaths = listOf("Src/Contested.kt"),
      foreignStagedPaths = listOf("src/contested.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'Src/Contested.kt'")
  }

  @Test
  fun `a case-aliased concurrent modification is detected rather than treated as a distinct path`() {
    val decision = decide(
      ownedPaths = listOf("Src/Contested.kt"),
      phaseIntroducedPaths = listOf("Src/Contested.kt"),
      concurrentlyModifiedOwnedPaths = listOf("src/contested.kt"),
    )

    val block = assertIs<FeatureTaskRuntimeCheckpointDecision.Block>(decision)
    assertContains(block.reason, "'Src/Contested.kt'")
  }

  @Test
  fun `an alias of an owned path is not reported as introduced outside the inventory`() {
    val decision = decide(
      ownedPaths = listOf("src/Owned.kt"),
      phaseIntroducedPaths = listOf("SRC/OWNED.KT"),
    )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
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

  // The delta defaults to everything owned or introduced, so each case names only what it is about.
  private fun decide(
    ownedPaths: List<String>,
    phaseIntroducedPaths: List<String>,
    worktreeDeltaPaths: List<String> = (ownedPaths + phaseIntroducedPaths).distinct(),
    foreignStagedPaths: List<String> = emptyList(),
    concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
  ): FeatureTaskRuntimeCheckpointDecision = FeatureTaskRuntimeCheckpointScope.decide(
    FeatureTaskRuntimeCheckpointScopeInput(
      issueKey = ISSUE,
      ownedPaths = ownedPaths,
      phaseIntroducedPaths = phaseIntroducedPaths,
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
