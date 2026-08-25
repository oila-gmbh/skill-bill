@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.phaseDeclaration
import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.application.featuretask.FeatureTaskRuntimeRunState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeProseCentricPhaseIoTest {
  @Test
  fun `implement output the old producer gate rejected advances with verbatim text intact`() {
    val deviantOutput = IMPLEMENT_DEVIATIONS_AS_STRINGS
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") deviantOutput else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val report = harness.runner.run(harness.request())

    if (report is FeatureTaskRuntimeRunReport.Blocked && report.lastIncompletePhase == "implement") {
      assertFalse(
        report.blockedReason.contains("producer-projection"),
        "shape-only implement rejection must not block at implement",
      )
    }
    val implementRecord = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"]
    if (implementRecord?.status == "completed") {
      assertEquals(deviantOutput, implementRecord.outputText ?: implementRecord.outputArtifact)
    }
  }

  @Test
  fun `consumer launch accepts producer prose without declared-field rejection`() {
    val legacyPlan = """{"contract_version":"0.2","phase_id":"plan","status":"completed",""" +
      """"summary":"Legacy plan.","produced_outputs":{"steps":["do the thing"]}}"""
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = phaseDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-208/spec.md",
        acceptanceCriteria = listOf("AC-1"),
        mandatesAndOverrides = emptyList(),
      ),
      recordedOutputs = listOf(
        FeatureTaskRuntimePhaseOutput("preplan", 1, validJsonOutput("preplan")),
        FeatureTaskRuntimePhaseOutput("plan", 1, legacyPlan),
      ),
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    assertTrue(briefing.briefingText.isNotBlank())
  }

  @Test
  fun `feature-task path does not quarantine legacy plan prose for RECORD_REJECTED regeneration`() {
    val legacyPlan = """{"contract_version":"0.2","phase_id":"plan","status":"completed",""" +
      """"summary":"Legacy plan.","produced_outputs":{"steps":["do the thing"]}}"""
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)

    harness.runner.run(harness.request())

    assertNull(
      harness.recorder.loadQuarantinedRecords(WORKFLOW_ID)?.firstOrNull { it.producingPhaseId == "plan" },
      "prose-shaped plan output must not enter quarantine for shape-only rejection",
    )
  }

  @Test
  fun `goal planning sweep still rejects malformed bounded projections through producer gate`() {
    val envelope = mapOf<String, Any?>(
      "produced_outputs" to mapOf(
        "projection_kind" to "executable_plan",
        "contract_version" to "0.1",
        "mode" to "direct",
        "tasks" to listOf(mapOf("task_id" to "task-1", "depends_on" to listOf("missing-task"))),
        "validation_strategy" to listOf("tests"),
      ),
    )
    assertNotNull(
      producerProjectionGateReason("plan", envelope, realPlanningProjectionValidator),
      "GoalPlanningSweep's producer gate must still reject malformed bounded projections",
    )
  }

  @Test
  fun `composeAgentPhaseInput and framed prompt carry non-blank fields without JSON output contract`() {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = phaseDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-208/spec.md",
        acceptanceCriteria = listOf("AC-1"),
        mandatesAndOverrides = emptyList(),
      ),
      recordedOutputs = listOf(
        FeatureTaskRuntimePhaseOutput("plan", 1, "Plan upstream prose."),
      ),
    )
    val briefing = FeatureTaskRuntimePhaseLaunchBriefing(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      specReference = ".feature-specs/SKILL-208/spec.md",
      featureSize = "M",
      acceptanceCriteria = listOf("AC-1"),
      mandatesAndOverrides = emptyList(),
      handoffEnvelope = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff).handoffEnvelope,
      derivedContextKeys = emptyList(),
      briefingText = "Fix briefing with upstream prose.",
    )
    val input = FeatureTaskRuntimePhasePromptComposer.composeAgentPhaseInput(
      briefing = briefing,
      carriedFindingIds = setOf("F-001"),
    )
    assertTrue(input.input.isNotBlank())
    assertTrue(input.requestedAction.isNotBlank())
    assertContains(input.requestedAction, "F-001")
    val framed = FeatureTaskRuntimePhasePromptComposer.frameAgentPhaseLaunchPrompt(
      phaseInput = input,
      directiveSections = FeatureTaskRuntimePhasePromptComposer.compose(
        issueKey = "SKILL-208",
        briefing = briefing,
      ),
    )
    assertContains(framed, "Phase input:")
    assertContains(framed, "Requested action:")
    assertFalse(framed.contains("Required final output"))
    assertFalse(framed.contains("produced_outputs"))
    assertFalse(framed.contains("\"contract_version\""))
  }

  @Test
  fun `every runtime stepId requestedAction is prose-only`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phaseId ->
      val input = FeatureTaskRuntimePhasePromptComposer.composeAgentPhaseInput(
        briefing = minimalBriefing(phaseId),
      )
      assertTrue(input.input.isNotBlank(), "input blank for $phaseId")
      assertTrue(input.requestedAction.isNotBlank(), "requestedAction blank for $phaseId")
      assertFalse(input.requestedAction.contains("produced_outputs"), "produced_outputs in $phaseId action")
      assertFalse(input.requestedAction.contains("contract_version"), "contract_version in $phaseId action")
      assertFalse(input.requestedAction.contains("Required final output"), "JSON gate in $phaseId action")
    }
  }

  @Test
  fun `resume derives audit verdict from prose outputText without schema gate`() {
    val prose = "status: completed. Audit verdict: gaps_found on AC-001."
    val state = FeatureTaskRuntimeRunState(
      initialRecords = mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to FeatureTaskRuntimePhaseRecord(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          status = "completed",
          attemptCount = 1,
          startedAt = "2026-01-01T00:00:00Z",
          resolvedAgentId = "auditor",
          outputText = prose,
          outputArtifact = prose,
        ),
      ),
      transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
      outputValidator = RejectingPhaseOutputValidator,
    )
    assertEquals(FeatureTaskRuntimeVerdict.GAPS_FOUND, state.verdictFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT))
  }
}

private fun minimalBriefing(phaseId: String): FeatureTaskRuntimePhaseLaunchBriefing =
  FeatureTaskRuntimePhaseLaunchBriefing(
    phaseId = phaseId,
    specReference = ".feature-specs/SKILL-208/spec.md",
    featureSize = "M",
    acceptanceCriteria = listOf("AC-001"),
    mandatesAndOverrides = emptyList(),
    handoffEnvelope = emptyMap(),
    derivedContextKeys = emptyList(),
    briefingText = "Upstream context for $phaseId.",
  )

private object RejectingPhaseOutputValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult =
    error("schema gate must not run for completed prose resume")

  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult =
    error("schema gate must not run for completed prose resume")

  override fun normalizePhaseOutput(phaseOutputText: String, sourceLabel: String) =
    error("schema gate must not run for completed prose resume")
}

private val IMPLEMENT_DEVIATIONS_AS_STRINGS: String =
  """{"contract_version":"0.4","phase_id":"implement","status":"completed",""" +
    """"summary":"Phase produced a validated output.",""" +
    """"produced_outputs":{"projection_kind":"implementation_receipt","contract_version":"0.1",""" +
    """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
    """"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"deviations":["free-text deviation instead of a ref and note object"],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Tree at target."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}}"""
