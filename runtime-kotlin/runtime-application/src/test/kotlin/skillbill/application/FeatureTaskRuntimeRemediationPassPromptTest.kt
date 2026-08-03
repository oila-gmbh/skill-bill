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
import kotlin.test.assertTrue

/**
 * AC-008 and AC-010: the reserved remediation pass's prompt must describe the remediation delta, must
 * not restate the immutable-base scope that contradicts it, must derive its mode token from the
 * resolved tier, and must be the seam that orders `produced_outputs.blocker_dispositions`.
 */
class FeatureTaskRuntimeRemediationPassPromptTest {
  @Test
  fun `pass two ceremony directive orders the remediation delta, not the complete immutable-base delta`() {
    val prompt = compose(passNumber = 2, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "context:feature-remediation")
    assertContains(prompt, "diff(pre-fix tree -> post-fix tree)")
    assertFalse(
      prompt.contains("to the subtask's complete delta from its immutable base"),
      "Pass two must not order the complete immutable-base delta.",
    )
  }

  @Test
  fun `pass two omits the immutable-base materialized scope block`() {
    val prompt = compose(passNumber = 2, resolvedTier = CodeReviewExecutionMode.INLINE, withReviewInput = true)

    assertFalse(
      prompt.contains("## Immutable-base review scope"),
      "The immutable-base scope block is pass one's authority only.",
    )
    assertContains(prompt, "## Reserved remediation pass (pass 2)")
  }

  @Test
  fun `pass one keeps the immutable-base materialized scope block`() {
    val prompt = compose(passNumber = 1, resolvedTier = CodeReviewExecutionMode.INLINE, withReviewInput = true)

    assertContains(prompt, "## Immutable-base review scope")
  }

  @Test
  fun `the remediation pass always renders mode inline`() {
    val prompt = compose(passNumber = 2, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "bill-code-review mode:inline context:feature-remediation")
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
      priorBlockerFindingIds = listOf("pass1-blocker-1", "pass1-blocker-2"),
    )

    assertContains(prompt, "blocker_dispositions")
    assertContains(prompt, "pass1-blocker-1, pass1-blocker-2")
    assertContains(prompt, "resolved, unresolved, superseded")
    assertTrue(
      prompt.contains("no more and no fewer"),
      "The pass must require a disposition for every prior Blocker, not a short list.",
    )
  }

  @Test
  fun `pass one never orders blocker dispositions`() {
    val prompt = compose(
      passNumber = 1,
      resolvedTier = CodeReviewExecutionMode.INLINE,
      priorBlockerFindingIds = listOf("pass1-blocker-1"),
    )

    assertFalse(prompt.contains("blocker_dispositions"), "Pass one has no prior pass to dispose.")
  }

  private fun compose(
    passNumber: Int,
    resolvedTier: CodeReviewExecutionMode,
    priorBlockerFindingIds: List<String> = emptyList(),
    withReviewInput: Boolean = false,
  ): String = FeatureTaskRuntimePhasePromptComposer.compose(
    issueKey = "SKILL-142",
    briefing = reviewBriefing(),
    codeReviewMode = resolvedTier,
    reviewPassNumber = passNumber,
    goalSubtaskReviewInput = if (withReviewInput) REVIEW_INPUT else null,
    resolvedReviewTier = resolvedTier,
    reviewDecidingRule = "auto_mode_by_pass_number:pass_n_inline",
    priorBlockerFindingIds = priorBlockerFindingIds,
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
