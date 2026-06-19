package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerTest {
  @Test
  fun `composes header briefing and output contract for every runtime phase`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phaseId ->
      val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor(phaseId))

      assertContains(prompt, ISSUE_KEY, false, "issue key for $phaseId")
      assertContains(prompt, "Phase: $phaseId", false, "phase header for $phaseId")
      assertContains(prompt, "# Feature-task-runtime phase briefing", false, "briefing body for $phaseId")
      assertContains(prompt, "feature_size: MEDIUM", false, "feature size for $phaseId")
      assertContains(prompt, "Scaling changes scope and verbosity only", false, "gate integrity for $phaseId")
      assertContains(prompt, SPEC_REFERENCE, false, "spec reference for $phaseId")
      assertContains(prompt, "Required final output", false, "output contract for $phaseId")
      assertContains(prompt, "\"phase_id\": must be \"$phaseId\"", false, "pinned phase id for $phaseId")
      assertContains(
        prompt,
        "\"contract_version\": must be exactly " +
          "\"${FeatureTaskRuntimePhaseWorkflowDefinition.definition.contractVersion}\"",
        false,
        "contract version for $phaseId",
      )
      assertContains(prompt, "\"completed\", \"blocked\", \"failed\"", false, "status enum for $phaseId")
      assertContains(prompt, "produced_outputs", false, "produced_outputs for $phaseId")
    }
  }

  @Test
  fun `each phase carries its own task directive`() {
    val preplanPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("preplan"))
    val planPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("plan"))
    val implementPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("implement"))
    val historyPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("write_history"))
    val commitPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("commit_push"))
    val prPrompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("pr"))

    assertContains(preplanPrompt, "scaled pre-planning digest")
    assertContains(preplanPrompt, "full preplan covering boundaries")
    assertContains(preplanPrompt, "Do not modify repository files during this phase.")
    assertContains(preplanPrompt, "schema-valid produced_outputs")
    assertContains(planPrompt, "Do not modify repository files during this phase.")
    assertContains(planPrompt, "upstream preplan digest")
    assertContains(implementPrompt, "Reconcile the repository to the intended state")
    assertTrue(
      !implementPrompt.contains("Do not modify repository files during this phase."),
      "implement must not carry the plan directive",
    )
    // The mutating-phase idempotency directive + reconciliation-report output requirement are emitted
    // only for mutating phases; non-mutating phases must not carry them.
    assertContains(implementPrompt, "Mutating-phase idempotency contract")
    assertContains(implementPrompt, "reconciliation report")
    assertTrue(
      !planPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating plan phase must not carry the idempotency directive",
    )
    assertTrue(
      !historyPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating write_history phase must not carry the idempotency directive",
    )
    assertContains(historyPrompt, "bill-boundary-history")
    assertContains(historyPrompt, "history_result")
    assertContains(commitPrompt, "commit_push_result")
    assertContains(commitPrompt, "terminal success signal")
    assertContains(prPrompt, "bill-pr-description")
    assertContains(prPrompt, "create or reuse the open")
    assertContains(prPrompt, "pr_result")
  }

  @Test
  fun `small prompts encode lighter ceremony and current unit review scope without skipping gates`() {
    val preplanPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("preplan", FeatureTaskRuntimeFeatureSize.SMALL),
    )
    val reviewPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("review", FeatureTaskRuntimeFeatureSize.SMALL),
    )
    val auditPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("audit", FeatureTaskRuntimeFeatureSize.SMALL),
    )

    assertContains(preplanPrompt, "feature_size: SMALL")
    assertContains(preplanPrompt, "preplan_ceremony: light")
    assertContains(reviewPrompt, "review_scope: current_unit_of_work")
    assertContains(reviewPrompt, "current-unit-of-work review scope")
    assertContains(auditPrompt, "audit_ceremony: light")
    assertContains(auditPrompt, "must not skip or weaken review, audit, validation")
  }

  @Test
  fun `upstream outputs flow into the prompt through the briefing text`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("implement"))

    assertContains(prompt, "### from: plan")
    assertContains(prompt, PLAN_OUTPUT)
  }

  @Test
  fun `does not instruct the goal-continuation activation flow`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("plan"))

    assertTrue(!prompt.contains("goal-continuation mode"))
    assertTrue(!prompt.contains("First execute this exact command"))
    assertContains(prompt, "do not call `skill-bill workflow continue`")
  }

  @Test
  fun `linear commit_push prompt carries the spec-exclusion directive`() {
    val prompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LINEAR,
    )

    assertContains(prompt, "Linear-mode commit exclusion")
    assertContains(prompt, ".feature-specs/$ISSUE_KEY/")
    assertContains(prompt, "never run `git add -A`")
    assertContains(prompt, "decomposition-manifest.yaml")
  }

  @Test
  fun `local commit_push prompt omits the spec-exclusion directive and matches the default`() {
    val linear = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LINEAR,
    )
    val local = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("commit_push"),
      specSource = SpecSource.LOCAL,
    )
    val default = FeatureTaskRuntimePhasePromptComposer.compose(ISSUE_KEY, briefingFor("commit_push"))

    assertEquals(default, local, "the spec_source default must be LOCAL (byte-for-byte unchanged)")
    assertTrue(!local.contains("Linear-mode commit exclusion"), "local mode must not carry the exclusion")
    assertTrue(local != linear, "linear mode must add the exclusion section")
  }

  @Test
  fun `linear spec-exclusion is absent on non-commit phases`() {
    val implementPrompt = FeatureTaskRuntimePhasePromptComposer.compose(
      ISSUE_KEY,
      briefingFor("implement"),
      specSource = SpecSource.LINEAR,
    )

    assertTrue(!implementPrompt.contains("Linear-mode commit exclusion"))
  }

  @Test
  fun `a blank issue key loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhasePromptComposer.compose(" ", briefingFor("plan"))
    }
  }
}

private const val ISSUE_KEY = "SKILL-66"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-66/spec.md"
private const val PREPLAN_OUTPUT = """{"preplan_digest":"scope-boundaries-risks-rollout"}"""
private const val PLAN_OUTPUT = """{"plan":"ordered-steps"}"""

private fun briefingFor(
  phaseId: String,
  featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
) = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
  FeatureTaskRuntimeHandoffContract.assembleHandoff(
    declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(phaseId, featureSize),
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = SPEC_REFERENCE,
      featureSize = featureSize,
      acceptanceCriteria = listOf("AC-1"),
      mandatesAndOverrides = emptyList(),
    ),
    recordedOutputs = listOf(
      FeatureTaskRuntimePhaseOutput("preplan", 1, PREPLAN_OUTPUT),
      FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
      FeatureTaskRuntimePhaseOutput("implement", 1, """{"implement":"done"}"""),
      FeatureTaskRuntimePhaseOutput("review", 1, """{"review":"ok"}"""),
    ),
  ),
)
