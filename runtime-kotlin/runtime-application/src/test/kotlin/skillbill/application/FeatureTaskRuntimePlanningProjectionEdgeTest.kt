@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionEdgeTest {
  @Test
  fun `plan receives only the bounded preplanning digest fields, never the complete preplan envelope`() {
    val briefing = assemble(
      consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.preplanningDigestDeclaration(phasePlan)),
      recordedOutputs = listOf(phaseOutput(phasePreplan, preplanDigestPayload())),
      runInvariants = runInvariants(),
    )

    assertContains(briefing.briefingText, "affected_boundaries:")
    assertContains(briefing.briefingText, "risks:")
    assertContains(briefing.briefingText, "validation_strategy:")
    assertFalse(
      briefing.briefingText.contains("complete_envelope_secret"),
      "complete preplan envelope must not survive projection",
    )
    assertFalse(
      briefing.briefingText.contains("progress_diagnostics"),
      "progress diagnostics must not survive projection",
    )
  }

  @Test
  fun `implement receives only the executable plan projection, never the preplan digest`() {
    val declaration = FeatureTaskRuntimePhaseDeclaration(
      phaseId = phaseImplement,
      projectionDeclarations = listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.executablePlanDeclaration(phaseImplement),
      ),
      derivedContextKeys = emptyList(),
    )
    // implement's declared source set excludes preplan entirely.
    assertFalse(declaration.consumedUpstreamPhaseIds.contains(phasePreplan))

    val briefing = assemble(
      consumer = phaseImplement,
      declarations = declaration.projectionDeclarations,
      recordedOutputs = listOf(phaseOutput(phasePlan, executablePlanPayload())),
      runInvariants = runInvariants(),
    )

    assertContains(briefing.briefingText, "mode: direct")
    assertContains(briefing.briefingText, "tasks:")
    assertFalse(
      briefing.briefingText.contains("planning_narration"),
      "planning narration must not survive projection",
    )
    assertFalse(
      briefing.briefingText.contains("decomposition_subtask_count"),
      "decomposition data must not reach implement under DIRECT mode",
    )
  }

  @Test
  fun `audit receives a bounded commitment and receipt, never the complete plan or implement envelopes`() {
    val briefing = assemble(
      consumer = phaseAudit,
      declarations = listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.planCommitmentDeclaration(phaseAudit),
        FeatureTaskRuntimePhaseWorkflowDefinition.implementationReceiptDeclaration(phaseAudit),
      ),
      recordedOutputs = listOf(
        phaseOutput(phasePlan, executablePlanPayload()),
        phaseOutput(phaseImplement, implementationReceiptPayload()),
      ),
      runInvariants = runInvariants(),
    )

    assertContains(briefing.briefingText, "task_commitments:")
    assertContains(briefing.briefingText, "changed_paths:")
    assertContains(briefing.briefingText, "tests_executed:")
    assertContains(briefing.briefingText, "reconciliation_evidence:")
    assertFalse(
      briefing.briefingText.contains("complete_plan_envelope_secret"),
      "complete plan envelope must not reach audit",
    )
    assertFalse(
      briefing.briefingText.contains("complete_implement_envelope_secret"),
      "complete implement envelope must not reach audit",
    )
    assertFalse(
      briefing.briefingText.contains("planning_narration"),
      "plan narration must not reach audit through the commitment",
    )
  }

  @Test
  fun `a DECOMPOSE-mode executable plan is never forwarded to the implement projection as decomposition data`() {
    val briefing = assemble(
      consumer = phaseImplement,
      declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.executablePlanDeclaration(phaseImplement)),
      recordedOutputs = listOf(phaseOutput(phasePlan, executablePlanPayload(mode = "decompose"))),
      runInvariants = runInvariants(),
    )
    // The executable_plan projection renders only mode/tasks/validation_strategy; decomposition fields
    // stay private to the preparation/goal boundary.
    assertFalse(briefing.briefingText.contains("decomposition_manifest_ref_value"))
    assertContains(briefing.briefingText, "mode: decompose")
  }

  @Test
  fun `a free-form producer payload loud-fails rather than being forwarded as a coarse receipt`() {
    var threw = false
    try {
      assemble(
        consumer = phasePlan,
        declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.preplanningDigestDeclaration(phasePlan)),
        recordedOutputs = listOf(phaseOutput(phasePreplan, """{"free":"form","narration":"whole envelope"}""")),
        runInvariants = runInvariants(),
      )
    } catch (error: skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
      threw = true
      assertTrue(error.message!!.contains("planning projection"))
    }
    assertTrue(threw, "a free-form payload under a planning contract must loud-fail")
  }

  @Test
  fun `a receipt checkpoint that no longer matches the tree is refreshed, not silently audited`() {
    val briefing = assemble(
      consumer = phaseAudit,
      declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.implementationReceiptDeclaration(phaseAudit)),
      recordedOutputs = listOf(phaseOutput(phaseImplement, implementationReceiptPayload(checkpoint = "stale-abc"))),
      runInvariants = runInvariants(),
    )

    assertContains(
      briefing.briefingText,
      "fixture-checkpoint-1${FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_REFRESHED_FROM_SEPARATOR}" +
        "stale-abc",
    )
    // The refresh re-derives repository evidence only; the producer's own claims survive intact.
    assertContains(briefing.briefingText, "task-01")
    assertContains(briefing.briefingText, "runtime-domain/model/X.kt")
    assertContains(briefing.briefingText, "files at target")
  }

  @Test
  fun `a receipt checkpoint that still matches the resolved tree is delivered unchanged`() {
    val briefing = assemble(
      consumer = phaseAudit,
      declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.implementationReceiptDeclaration(phaseAudit)),
      recordedOutputs = listOf(
        phaseOutput(phaseImplement, implementationReceiptPayload(checkpoint = "fixture-checkpoint-1")),
      ),
      runInvariants = runInvariants(),
    )

    assertContains(briefing.briefingText, "repository_checkpoint: repository_checkpoint=fixture-checkpoint-1\n")
    assertFalse(
      briefing.briefingText.contains(
        FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_REFRESHED_FROM_SEPARATOR,
      ),
      "a matching checkpoint must not be marked as refreshed",
    )
  }

  @Test
  fun `audit receives the resolved repository checkpoint as scoped comparison context`() {
    val briefing = assemble(
      consumer = phaseAudit,
      declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.implementationReceiptDeclaration(phaseAudit)),
      recordedOutputs = listOf(phaseOutput(phaseImplement, implementationReceiptPayload())),
      runInvariants = runInvariants(),
      checkpoint = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = "fixture-checkpoint-1",
        baseRef = "0".repeat(40),
        headRef = "feat/SKILL-137",
        workingTreeOwnedPaths = listOf("runtime-domain/model/X.kt"),
      ),
    )

    assertContains(briefing.briefingText, "## Repository checkpoint (layer 2, resolved)")
    assertContains(briefing.briefingText, "fingerprint: fixture-checkpoint-1")
    assertContains(briefing.briefingText, "base_ref: ${"0".repeat(40)}")
    assertContains(briefing.briefingText, "head_ref: feat/SKILL-137")
    assertContains(briefing.briefingText, "scoped_owned_paths:\n  - runtime-domain/model/X.kt")
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE),
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.getValue(phaseAudit).derivedContextKeys,
    )
  }

  @Test
  fun `a phase whose declarations require no checkpoint renders no checkpoint section`() {
    val briefing = assemble(
      consumer = phasePlan,
      declarations = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.preplanningDigestDeclaration(phasePlan)),
      recordedOutputs = listOf(phaseOutput(phasePreplan, preplanDigestPayload())),
      runInvariants = runInvariants(),
    )

    assertFalse(briefing.briefingText.contains("## Repository checkpoint"))
  }

  private fun assemble(
    consumer: String,
    declarations: List<skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration>,
    recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    // audit's receipt edge refreshes from a resolved checkpoint (AC-012).
    checkpoint: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint =
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = "fixture-checkpoint-1",
      ),
  ) = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseDeclaration(consumer, declarations, emptyList()),
      runInvariants = runInvariants,
      recordedOutputs = recordedOutputs,
      repositoryCheckpoint = checkpoint,
    ),
  )

  private fun phaseOutput(phaseId: String, payload: String) =
    FeatureTaskRuntimePhaseOutput(phaseId = phaseId, iteration = 1, payload = payload)

  private fun runInvariants() = FeatureTaskRuntimeRunInvariants(
    specReference = "spec.md",
    acceptanceCriteria = listOf("AC-001"),
    mandatesAndOverrides = emptyList(),
  )

  private fun preplanDigestPayload(): String = """
    {"produced_outputs":{
      "projection_kind":"preplanning_digest",
      "contract_version":"0.1",
      "affected_boundaries":["runtime-domain/model"],
      "risks":["producer may omit fields"],
      "rollout":{"flag_required":false,"notes":"no flag needed"},
      "validation_strategy":["snapshot projection tests"],
      "complete_envelope_secret":"MUST NOT SURVIVE",
      "progress_diagnostics":"MUST NOT SURVIVE"
    }}
  """.trimIndent()

  private fun executablePlanPayload(mode: String = "direct"): String {
    val decomposition = if (mode == "decompose") {
      ""","decomposition_subtask_count":2,"decomposition_manifest_ref":"decomposition_manifest_ref_value""""
    } else {
      ""
    }
    return """
      {"produced_outputs":{
        "projection_kind":"executable_plan",
        "contract_version":"0.1",
        "mode":"$mode",
        "tasks":[{"task_id":"task-01","depends_on":[],"description":"add contract",
        "criterion_refs":["AC-005"],"test_obligations":["parity"],"constraints":["d"]}],
        "validation_strategy":["focused gradle"],
        "complete_plan_envelope_secret":"MUST NOT SURVIVE",
        "planning_narration":"MUST NOT SURVIVE"$decomposition
      }}
    """.trimIndent()
  }

  private fun implementationReceiptPayload(checkpoint: String = "abc123"): String = """
    {"produced_outputs":{
      "projection_kind":"implementation_receipt",
      "contract_version":"0.1",
      "completed_task_ids":["task-01"],
      "changed_paths":["runtime-domain/model/X.kt"],
      "tests_executed":[{"name":"XTest.kt","outcome":"passed"}],
      "reconciliation_evidence":{"reconciled":true,"evidence":"files at target"},
      "repository_checkpoint":{"fingerprint":"$checkpoint"},
      "complete_implement_envelope_secret":"MUST NOT SURVIVE"
    }}
  """.trimIndent()

  private val phasePreplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  private val phasePlan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  private val phaseImplement = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
  private val phaseAudit = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
}
