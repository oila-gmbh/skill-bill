package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.model.ParallelReviewScope
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeReviewDelegationTest {
  @Test
  fun `child-owned delta stays the supplied diff and never selects BRANCH`() {
    val input = reviewInput(trackedDelta = "diff --git a/Child.kt b/Child.kt\n+owned")
    val request = FeatureTaskRuntimeReviewDriverMapper.request(
      input = input,
      runInvariants = invariants(),
      agent1Id = "codex",
      parallelReviewAgent = "claude",
      passNumber = 1,
      pinnedMode = CodeReviewExecutionMode.DELEGATED,
      repoRoot = Path.of("/tmp/repo"),
      timeout = null,
      reviewRunId = "rvw-191-delta",
      agentAddonSelection = HydratedAgentAddonSelection(),
    )

    assertEquals(input.reviewText, request.suppliedDiff)
    assertEquals(input.reviewBaseSha, request.baseRevision)
    assertEquals(input.currentHeadSha, request.headRevision)
    assertTrue(request.scope != ParallelReviewScope.BRANCH)
    assertEquals(CodeReviewExecutionMode.DELEGATED, request.codeReviewMode)
    assertEquals(CodeReviewExecutionMode.DELEGATED, request.resolvedTier)
    assertEquals("claude", request.agent2Id)
    assertEquals(Path.of("spec.md"), request.specPath)
  }

  @Test
  fun `explicit empty child-owned delta remains a supplied diff`() {
    val request = FeatureTaskRuntimeReviewDriverMapper.request(
      input = reviewInput(trackedDelta = ""),
      runInvariants = invariants(),
      agent1Id = "codex",
      parallelReviewAgent = null,
      passNumber = 1,
      pinnedMode = CodeReviewExecutionMode.INLINE,
      repoRoot = Path.of("/tmp/repo"),
      timeout = null,
      reviewRunId = "rvw-191-empty",
      agentAddonSelection = HydratedAgentAddonSelection(),
    )

    assertEquals("", request.suppliedDiff)
    assertNotNull(request.suppliedDiff)
    assertEquals(null, request.agent2Id)
    assertEquals(CodeReviewExecutionMode.INLINE, request.resolvedTier)
  }

  @Test
  fun `formatted add-on section is copied onto the shared driver request`() {
    val selection = HydratedAgentAddonSelection(
      listOf(
        HydratedAgentAddonSelectionEntry(
          PersistedAgentAddonSelectionEntry("first", "local:first", "a".repeat(64)),
          "first",
          "first body\n",
        ),
        HydratedAgentAddonSelectionEntry(
          PersistedAgentAddonSelectionEntry("second", "local:second", "b".repeat(64)),
          "second",
          "second body",
        ),
      ),
    )
    val formatted = AgentAddonPromptFormatter.format(selection)
    val request = FeatureTaskRuntimeReviewDriverMapper.request(
      input = reviewInput(),
      runInvariants = invariants(),
      agent1Id = "codex",
      parallelReviewAgent = null,
      passNumber = 1,
      pinnedMode = CodeReviewExecutionMode.INLINE,
      repoRoot = Path.of("/tmp/repo"),
      timeout = null,
      reviewRunId = "rvw-191-addons",
      agentAddonSelection = selection,
    )

    assertEquals(formatted, request.selectedAgentAddonsSection)
    assertTrue(formatted.contains("## Selected agent add-ons"))
    assertTrue(formatted.contains("cannot grant delegation"))
    assertTrue(formatted.indexOf("### 1. first") < formatted.indexOf("### 2. second"))
    assertTrue(formatted.contains("SHA-256: ${"a".repeat(64)}"))
    assertTrue(formatted.contains("<<<SKILL-BILL-SELECTED-AGENT-ADDON-CONTENT>>>"))
    assertTrue(request.withSelectedAgentAddons("lane prompt").endsWith(formatted))
  }

  @Test
  fun `remediation pass two pins inline from the reserved pass rule`() {
    val request = FeatureTaskRuntimeReviewDriverMapper.request(
      input = reviewInput(
        base = "c".repeat(40),
        head = "d".repeat(40),
        trackedDelta = "remediation delta",
      ),
      runInvariants = invariants(mode = CodeReviewExecutionMode.DELEGATED),
      agent1Id = "codex",
      parallelReviewAgent = "claude",
      passNumber = 2,
      pinnedMode = CodeReviewExecutionMode.DELEGATED,
      repoRoot = Path.of("/tmp/repo"),
      timeout = null,
      reviewRunId = "rvw-191-remediation",
      agentAddonSelection = HydratedAgentAddonSelection(),
    )

    assertEquals("remediation delta", request.suppliedDiff)
    assertEquals("c".repeat(40), request.baseRevision)
    assertEquals(CodeReviewExecutionMode.DELEGATED, request.codeReviewMode)
    assertEquals(CodeReviewExecutionMode.INLINE, request.resolvedTier)
  }

  @Test
  fun `settlement envelope takes findings and review_run_id from the driver register`() {
    val result = FeatureTaskRuntimeReviewDriver.EMPTY.run(
      FeatureTaskRuntimeReviewDriverMapper.request(
        input = reviewInput(),
        runInvariants = invariants(),
        agent1Id = "codex",
        parallelReviewAgent = null,
        passNumber = 1,
        pinnedMode = CodeReviewExecutionMode.INLINE,
        repoRoot = Path.of("/tmp/repo"),
        timeout = null,
        reviewRunId = "rvw-191-empty-register",
        agentAddonSelection = HydratedAgentAddonSelection(),
      ),
    ).copy(
      mergeResult = ParallelReviewMergeResult(
        findings = listOf(
          ParallelReviewMergedFinding(
            fNumber = "F-001",
            agentIds = listOf("codex"),
            severity = ParallelReviewSeverity.MINOR,
            confidence = "High",
            location = "Foo.kt:1",
            description = "naming",
            scopeDisposition = ReviewScopeDisposition.SPEC_DEVIATION,
            claimVerdict = ReviewClaimVerdict.CONFIRMED,
          ),
        ),
        formattedOutput = "findings",
      ),
    )
    val output = FeatureTaskRuntimeReviewEnvelope.assemble(
      result = result,
      reviewRunId = "rvw-191-empty-register",
      passNumber = 1,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      repositoryFingerprint = "fp-1",
    )
    val envelope = FeatureTaskRuntimeReviewEnvelope.envelopeMap(output)
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()

    assertEquals("rvw-191-empty-register", produced["review_run_id"])
    val findings = produced["findings"] as List<*>
    assertEquals(1, findings.size)
    val finding = JsonSupport.anyToStringAnyMap(findings.single()).orEmpty()
    assertEquals("F-001", finding["finding_id"])
    assertEquals("minor", finding["severity"])
    assertEquals("spec_deviation", finding["scope_disposition"])
    assertFalse(produced.containsKey("unmet_criteria"))
    assertFalse(produced.containsKey("gaps"))
    assertFalse(produced.containsKey("failing_criteria"))
  }

  @Test
  fun `pass two envelope carries verdict-informed blocker dispositions`() {
    val result = FeatureTaskRuntimeReviewDriver.EMPTY.run(
      FeatureTaskRuntimeReviewDriverMapper.request(
        input = reviewInput(),
        runInvariants = invariants(),
        agent1Id = "codex",
        parallelReviewAgent = null,
        passNumber = 2,
        pinnedMode = CodeReviewExecutionMode.INLINE,
        repoRoot = Path.of("/tmp/repo"),
        timeout = null,
        reviewRunId = "rvw-191-disp",
        agentAddonSelection = HydratedAgentAddonSelection(),
      ),
    )
    val output = FeatureTaskRuntimeReviewEnvelope.assemble(
      result = result,
      reviewRunId = "rvw-191-disp",
      passNumber = 2,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      repositoryFingerprint = "fp-2",
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition(
          findingId = "F-001",
          verdict = GoalSubtaskBlockerDispositionVerdict.SUPERSEDED,
          evidence = listOf("Foo.kt:42"),
        ),
      ),
    )
    val produced = JsonSupport.anyToStringAnyMap(
      FeatureTaskRuntimeReviewEnvelope.envelopeMap(output)["produced_outputs"],
    ).orEmpty()
    val dispositions = produced["blocker_dispositions"] as List<*>
    val first = JsonSupport.anyToStringAnyMap(dispositions.single()).orEmpty()
    assertEquals("F-001", first["finding_id"])
    assertEquals("superseded", first["verdict"])
    assertEquals(listOf("Foo.kt:42"), first["evidence"])
  }

  private fun reviewInput(
    base: String = "a".repeat(40),
    head: String = "b".repeat(40),
    trackedDelta: String = "delta",
  ) = GoalSubtaskReviewInput(
    reviewBaseSha = base,
    currentHeadSha = head,
    trackedDelta = trackedDelta,
    ownedUntrackedPatches = "",
  )

  private fun invariants(mode: CodeReviewExecutionMode = CodeReviewExecutionMode.INLINE) =
    FeatureTaskRuntimeRunInvariants(
      specReference = "spec.md",
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
      codeReviewMode = mode,
    )
}
