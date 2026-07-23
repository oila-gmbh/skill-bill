package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private val CEILING = FeatureTaskRuntimePhaseBriefingAssembler.FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING

class FeatureTaskRuntimePhaseBriefingBudgetTest {
  @Test
  fun `audit remediation briefing exposes an ordered checklist without cumulative historical payloads`() {
    val repairItem = FeatureTaskRuntimeRepairItem(
      repairItemId = "ac-004-gap-2-item-1",
      intendedOutcome = "Strict durable state",
      implementationActions = listOf("Implement strict decoding"),
      affectedPathsOrSymbols = listOf("FeatureTaskRuntimeAuditRepairWireMapper"),
      requiredVerification = listOf("Run focused tests"),
      dependsOn = emptyList(),
    )
    val plan = FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = AUDIT_REPAIR_CONTRACT_VERSION,
      gaps = listOf(
        FeatureTaskRuntimeAuditGap(
          gapId = "ac-004-gap-2",
          acceptanceCriterionRef = "AC-004",
          acceptanceCriterionText = "Durable state is strict.",
          failureEvidence = FeatureTaskRuntimeEvidence(
            FeatureTaskRuntimeEvidence.Observation.STATE_MISMATCH,
            "FeatureTaskRuntimeAuditRepairWireMapper",
            "AC-004",
          ),
          diagnosis = "Tighten the read seam.",
          affectedBoundary = "runtime application",
          repairItems = listOf(repairItem),
        ),
      ),
    )
    val state = FeatureTaskRuntimeAuditRepairState(
      acceptedPlans = listOf(plan),
      repairItemResults = emptyList(),
      priorGapDispositions = emptyList(),
      unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(
        listOf(FeatureTaskRuntimeUnresolvedGap("ac-004-gap-2", "AC-004", 2)),
      ),
      repositoryFingerprint = "digest",
      progress = FeatureTaskRuntimeAuditRepairProgress(false, 0, 1, 0, 0, 1),
    )
    val handoff = FeatureTaskRuntimePhaseHandoff(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-131/spec.md",
        acceptanceCriteria = listOf("AC-004"),
        mandatesAndOverrides = emptyList(),
      ),
      upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
      derivedContextKeys = emptyList(),
      auditRepairPlan = plan,
      auditRepairState = state,
    )

    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    assertEquals(listOf("ac-004-gap-2-item-1"), briefing.auditRepairItemIds)
    assertContains(briefing.briefingText, "exhaustive execution checklist")
    assertContains(briefing.briefingText, "audit_remediation_context")
    assertContains(briefing.briefingText, "prior_terminal_result_count")
    assertFalse(briefing.briefingText.contains("execution_history"))
    assertFalse(briefing.briefingText.contains("accepted_plans"))
  }

  @Test
  fun `an oversized upstream projection is rejected, never truncated into the briefing`() {
    val oversizedBytes = 400_000
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = multiUpstreamOutputs(oversizedBytes),
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff, workflowId = "wftr-1")
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX, error.consumerPhaseId)
    assertEquals("wftr-1", error.workflowId)
    assertContains(error.message.orEmpty(), "wftr-1")
    assertContains(error.message.orEmpty(), error.projectionName)
    assertContains(error.message.orEmpty(), error.projectionContractId)
    assertFalse(
      error.message.orEmpty().contains("p".repeat(64)),
      "the rejection echoed the oversized payload body; typed errors must name identifiers, not content",
    )
  }

  @Test
  fun `normal-size upstream projections are delivered verbatim and within the framing ceiling`() {
    val planBody = "p".repeat(4000)
    val implementBody = "i".repeat(4000)
    val reviewBody = "r".repeat(4000)
    val recordedOutputs = listOf(
      phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, """{"plan":"$planBody"}"""),
      phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT, """{"implement":"$implementBody"}"""),
      phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, """{"review":"$reviewBody"}"""),
    )
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = recordedOutputs,
    )

    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    assertContains(briefing.briefingText, """{"plan":"$planBody"}""")
    assertContains(briefing.briefingText, """{"implement":"$implementBody"}""")
    assertContains(briefing.briefingText, """{"review":"$reviewBody"}""")
    assertContains(briefing.briefingText, "### from: ${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN}")
    assertEquals("""{"plan":"$planBody"}""", briefing.requireUpstreamReceipt("plan"))
  }

  @Test
  fun `an undeclared recorded output is never delivered, even when it is present in state`() {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      // `implement` declares only `plan`; the recorded `review` output has no declaration.
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = listOf(
        phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, """{"plan":"ok"}"""),
        phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, """{"review":"undeclared"}"""),
      ),
    )

    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    assertEquals("""{"plan":"ok"}""", briefing.requireUpstreamReceipt("plan"))
    assertFalse(briefing.hasUpstreamReceipt("review"))
    assertFalse(briefing.briefingText.contains("undeclared"))
  }

  @Test
  fun `pathologically large layer-1 loud-fails instead of emitting an over-budget or truncated-contract briefing`() {
    val pathologicalCriterion = "AC-huge: ${"x".repeat(CEILING + 10_000)}"
    val handoff = FeatureTaskRuntimePhaseHandoff(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
        acceptanceCriteria = listOf(pathologicalCriterion),
        mandatesAndOverrides = emptyList(),
      ),
      upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
      derivedContextKeys = emptyList(),
    )

    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff) }
  }

  @Test
  fun `a finalization phase omits the acceptance contract but still receives the operator mandates`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-137/spec.md",
      acceptanceCriteria = listOf("AC-1: the acceptance contract text"),
      mandatesAndOverrides = listOf("mandate-1: the policy text"),
    )
    fun briefingFor(phaseId: String) = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      FeatureTaskRuntimePhaseHandoff(
        phaseId = phaseId,
        runInvariants = invariants,
        upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
        derivedContextKeys = emptyList(),
      ),
    )

    val implementText = briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT).briefingText
    assertContains(implementText, "acceptance_criteria:")
    assertContains(implementText, "mandates_and_overrides:")

    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    ).forEach { phaseId ->
      val briefing = briefingFor(phaseId)
      assertFalse(
        briefing.briefingText.contains("acceptance_criteria:"),
        "finalization phase '$phaseId' rendered the acceptance contract; the allowlist did not apply",
      )
      assertContains(
        briefing.briefingText,
        "the policy text",
        message = "finalization phase '$phaseId' lost the operator mandates; " +
          "the allowlist is their only delivery path",
      )
      // Identity stays durable state on the briefing even when it is not prompt-rendered.
      assertEquals(invariants.acceptanceCriteria, briefing.acceptanceCriteria)
      assertContains(briefing.briefingText, "spec_reference:")
    }
  }

  @Test
  fun `the rendered briefing carries no forbidden raw-context field name`() {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = listOf(phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, """{"plan":"ok"}""")),
    )

    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    val serialized = JsonSupport.mapToJsonString(briefing.toArtifactMap())

    FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES.forEach { forbidden ->
      assertFalse(
        serialized.contains("\"$forbidden\""),
        "the persisted briefing carries forbidden raw-context field '$forbidden'",
      )
      assertFalse(
        briefing.briefingText.contains("$forbidden:"),
        "the rendered briefing carries forbidden raw-context field '$forbidden'",
      )
    }
  }

  private fun multiUpstreamInvariants() = FeatureTaskRuntimeRunInvariants(
    specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
    acceptanceCriteria = (1..11).map { "AC-$it: ${"criterion-detail ".repeat(20)}" },
    mandatesAndOverrides = (1..6).map { "mandate-$it: ${"override-detail ".repeat(20)}" },
  )

  private fun multiUpstreamOutputs(bodyBytes: Int) = listOf(
    phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, """{"plan":"${"p".repeat(bodyBytes)}"}"""),
    phaseOutput(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      """{"implement":"${"i".repeat(bodyBytes)}"}""",
    ),
    phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, """{"review":"${"r".repeat(bodyBytes)}"}"""),
  )

  private fun phaseOutput(phaseId: String, payload: String) =
    FeatureTaskRuntimePhaseOutput(phaseId = phaseId, iteration = 1, payload = payload)
}
