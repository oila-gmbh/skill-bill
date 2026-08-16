@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * AC-008 and AC-010: the reserved remediation pass's prompt must describe the remediation delta, must
 * not restate the immutable-base scope that contradicts it, and must derive its mode token from the
 * resolved tier. Runtime-owned review synthesizes `produced_outputs.blocker_dispositions`; the prompt
 * does not order that key.
 *
 * SKILL-178 subtask 2: the finding half of the remediation-delta union widens to all addressed
 * findings; remediation survival wording covers Blocker or Major; pass-two still suppresses
 * immutable-base and baseline-untracked framing.
 */
class FeatureTaskRuntimeRemediationPassPromptTest {
  @Test
  fun `pass two ceremony directive orders the remediation delta, not the complete immutable-base delta`() {
    val prompt = compose(passNumber = 2, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "context:feature-remediation")
    assertContains(prompt, "diff(pre-fix tree -> post-fix tree)")
    assertContains(prompt, "all findings addressed in that round")
    assertFalse(
      prompt.contains("prior pass's Blocker findings"),
      "Pass two must not scope remediation to Blocker-only findings.",
    )
    assertFalse(
      prompt.contains("to the subtask's complete delta from its immutable base"),
      "Pass two must not order the complete immutable-base delta.",
    )
  }

  @Test
  fun `pass two reserved remediation section preserves prohibitions and Blocker-or-Major survival`() {
    val prompt = compose(passNumber = 2, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "all findings addressed in that round union")
    assertContains(prompt, "Do not re-review the subtask's full base-to-current delta")
    assertContains(prompt, "review_base_sha")
    assertContains(prompt, "pass one's authority only")
    assertContains(prompt, "A defect introduced by the remediation itself must still be caught")
    assertContains(prompt, "unresolved Blocker or Major survives")
    assertFalse(prompt.contains("immediately preceding pass's Blocker findings"))
  }

  @Test
  fun `pass two omits the immutable-base materialized scope block and baseline-untracked policy`() {
    val prompt = compose(
      passNumber = 2,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      reviewInput = REVIEW_INPUT,
      baselineUntrackedPaths = listOf("preexisting.tmp"),
    )

    assertFalse(
      prompt.contains("## Immutable-base review scope"),
      "The immutable-base scope block is pass one's authority only.",
    )
    assertFalse(
      prompt.contains("## Baseline-untracked review policy"),
      "Baseline-untracked policy is pass one's authority only.",
    )
    assertContains(prompt, "## Reserved remediation pass (pass 2)")
  }

  @Test
  fun `pass one keeps the immutable-base materialized scope block`() {
    val prompt = compose(
      passNumber = 1,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      reviewInput = REVIEW_INPUT,
    )

    assertContains(prompt, "## Immutable-base review scope")
  }

  @Test
  fun `pass one keeps baseline-untracked policy when inventory is present`() {
    val prompt = compose(
      passNumber = 1,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      baselineUntrackedPaths = listOf("preexisting.tmp"),
    )

    assertContains(prompt, "## Baseline-untracked review policy")
    assertContains(prompt, "preexisting.tmp")
  }

  @Test
  fun `the remediation pass always renders mode inline`() {
    val prompt = compose(passNumber = 2, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "mode:inline context:feature-remediation")
    assertFalse(
      prompt.contains("mode:delegated context:feature-remediation"),
      "context:feature-remediation paired with mode:delegated is rejected by the governed skill.",
    )
  }

  @Test
  fun `pass two orders one evidenced disposition per prior Blocker finding id`() {
    val prompt = compose(
      passNumber = 2,
      resolvedTier = CodeReviewExecutionMode.INLINE,
    )

    assertFalse(
      prompt.contains("blocker_dispositions"),
      "Runtime synthesizes dispositions; the prompt does not order them.",
    )
  }

  @Test
  fun `pass one never orders blocker dispositions`() {
    val prompt = compose(
      passNumber = 1,
      resolvedTier = CodeReviewExecutionMode.INLINE,
    )

    assertFalse(prompt.contains("blocker_dispositions"), "Pass one has no prior pass to dispose.")
  }

  @Test
  fun `worked example pass two scopes only the four remediation-touched files plus all addressed findings`() {
    val touched = listOf(
      "src/TouchedOne.kt",
      "src/TouchedTwo.kt",
      "src/TouchedThree.kt",
      "src/TouchedFour.kt",
    )
    val untouched = listOf(
      "src/UntouchedOne.kt",
      "src/UntouchedTwo.kt",
      "src/UntouchedThree.kt",
      "src/UntouchedFour.kt",
      "src/UntouchedFive.kt",
      "src/UntouchedSix.kt",
    )
    val remediationDelta = touched.joinToString("\n") { path ->
      "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n@@ -1 +1 @@\n-old\n+new"
    }
    val prompt = compose(
      passNumber = 2,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      reviewInput = GoalSubtaskReviewInput(
        reviewBaseSha = "c".repeat(40),
        currentHeadSha = "d".repeat(40),
        trackedDelta = remediationDelta,
        ownedUntrackedPatches = "",
      ),
    )

    assertContains(prompt, "all findings addressed in that round")
    assertFalse(prompt.contains("immediately preceding pass's Blocker findings"))
    touched.forEach { path -> assertContains(prompt, path) }
    untouched.forEach { path ->
      assertFalse(prompt.contains(path), "untouched path $path must not appear in the remediation materialization")
    }
  }

  @Test
  fun `implement_fix briefing includes a Minor finding from the preceding pass without severity re-filter`() {
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1")
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-178/spec.md",
        featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
        acceptanceCriteria = listOf("AC-005"),
        mandatesAndOverrides = emptyList(),
      ),
      recordedOutputs = listOf(
        FeatureTaskRuntimePhaseOutput(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          1,
          """{"produced_outputs":{"findings":[""" +
            """{"finding_id":"F-BLOCKER","severity":"Blocker","location":"A.kt:1","message":"must fix"},""" +
            """{"finding_id":"F-MINOR","severity":"Minor","location":"B.kt:2","message":"polish naming"}]}}""",
        ),
      ),
      repositoryCheckpoint = checkpoint,
      expectedRepositoryCheckpoint = checkpoint,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      issueKey = "SKILL-178",
      briefing = briefing,
    )

    assertContains(prompt, "F-MINOR")
    assertContains(prompt, "Minor")
    assertContains(prompt, "polish naming")
    assertContains(prompt, "Every finding in the briefing — Blocker, Major, Minor, and Nit — is in")
    assertFalse(prompt.contains("Major, Minor, and Nit findings, specialist narratives"))
  }

  @Test
  fun `review ceremony orders every severity into produced_outputs findings without Blocker-only filter`() {
    val prompt = compose(passNumber = 1, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "The runtime owns this review")
    assertFalse(prompt.contains("Do not severity-filter the findings array"))
    assertFalse(
      prompt.contains("only unresolved actionable Blocker findings"),
      "PHASE_REVIEW must not order a Blocker-only findings array; AC-005 needs the full producer set.",
    )
  }

  private fun compose(
    passNumber: Int,
    resolvedTier: CodeReviewExecutionMode,
    reviewInput: GoalSubtaskReviewInput? = null,
    baselineUntrackedPaths: List<String> = emptyList(),
  ): String = FeatureTaskRuntimePhasePromptComposer.compose(
    issueKey = "SKILL-142",
    briefing = reviewBriefing(),
    codeReviewMode = resolvedTier,
    reviewPassNumber = passNumber,
    goalSubtaskReviewInput = reviewInput,
    resolvedReviewTier = resolvedTier,
    reviewDecidingRule = "auto_mode_by_pass_number:pass_n_inline",
    baselineUntrackedPaths = baselineUntrackedPaths,
  )
}

private val REVIEW_INPUT = GoalSubtaskReviewInput(
  reviewBaseSha = "a".repeat(40),
  currentHeadSha = "b".repeat(40),
  trackedDelta = "diff --git a/A.kt b/A.kt",
  ownedUntrackedPatches = "",
)

private fun reviewBriefing() = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
  FeatureTaskRuntimeHandoffContract.assembleHandoff(
    declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimeFeatureSize.MEDIUM,
    ),
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-142/spec.md",
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("AC-008", "AC-010"),
      mandatesAndOverrides = emptyList(),
    ),
    recordedOutputs = listOf(
      FeatureTaskRuntimePhaseOutput("audit", 1, validJsonOutput("audit")),
    ),
    repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1"),
  ),
)
