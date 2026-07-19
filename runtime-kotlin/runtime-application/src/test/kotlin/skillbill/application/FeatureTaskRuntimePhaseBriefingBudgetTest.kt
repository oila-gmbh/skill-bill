package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
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
import kotlin.test.assertTrue

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
      contractVersion = "0.1",
      gaps = listOf(
        FeatureTaskRuntimeAuditGap(
          gapId = "ac-004-gap-2",
          acceptanceCriterionRef = "AC-004",
          acceptanceCriterionText = "Durable state is strict.",
          failureEvidence = "Missing fields were accepted.",
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
  fun `assembler bounds an unbounded upstream payload so the briefing stays within the documented budget`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = (1..11).map { "AC-$it: ${"criterion-detail ".repeat(20)}" },
      mandatesAndOverrides = (1..6).map { "mandate-$it: ${"override-detail ".repeat(20)}" },
    )
    val oversizedBytes = 400_000
    val recordedOutputs = listOf(
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        iteration = 1,
        payload = """{"plan":"${"p".repeat(oversizedBytes)}"}""",
      ),
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = """{"implement":"${"i".repeat(oversizedBytes)}"}""",
      ),
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        iteration = 1,
        payload = """{"review":"${"r".repeat(oversizedBytes)}"}""",
      ),
    )

    val auditDeclaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
      .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = auditDeclaration,
      runInvariants = invariants,
      recordedOutputs = recordedOutputs,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    val byteSize = briefing.briefingText.toByteArray(Charsets.UTF_8).size
    assertTrue(
      byteSize < CEILING,
      "Assembled per-phase briefing was $byteSize bytes, exceeding the $CEILING ceiling; " +
        "the assembler stopped bounding an unbounded upstream payload inlined into a single phase briefing.",
    )
    assertContains(briefing.briefingText, FeatureTaskRuntimePhaseBriefingAssembler.UPSTREAM_PAYLOAD_TRUNCATION_MARKER)
    assertContains(briefing.briefingText, "### from: ${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN}")
    assertContains(briefing.briefingText, "### from: ${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT}")
    assertContains(briefing.briefingText, "### from: ${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW}")
    assertContains(briefing.briefingText, """{"plan":"pppp""")
    assertContains(briefing.briefingText, """{"implement":"iiii""")
    assertContains(briefing.briefingText, """{"review":"rrrr""")
    assertFalse(
      briefing.briefingText.contains("p".repeat(oversizedBytes)),
      "the unbounded plan payload was inlined verbatim; the runtime bound was not applied",
    )
    assertEquals(
      """{"plan":"${"p".repeat(oversizedBytes)}"}""",
      briefing.upstreamOutputsByPhaseId.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    )
  }

  @Test
  fun `normal-size upstream payloads are inlined verbatim and within budget`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = (1..11).map { "AC-$it: ${"criterion-detail ".repeat(20)}" },
      mandatesAndOverrides = (1..6).map { "mandate-$it: ${"override-detail ".repeat(20)}" },
    )
    val planBody = "p".repeat(4000)
    val implementBody = "i".repeat(4000)
    val reviewBody = "r".repeat(4000)
    val recordedOutputs = listOf(
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        iteration = 1,
        payload = """{"plan":"$planBody"}""",
      ),
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = """{"implement":"$implementBody"}""",
      ),
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        iteration = 1,
        payload = """{"review":"$reviewBody"}""",
      ),
    )

    val auditDeclaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
      .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = auditDeclaration,
      runInvariants = invariants,
      recordedOutputs = recordedOutputs,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    val byteSize = briefing.briefingText.toByteArray(Charsets.UTF_8).size
    assertTrue(byteSize < CEILING, "normal-size briefing was $byteSize bytes, over the $CEILING ceiling")
    assertFalse(
      briefing.briefingText.contains(FeatureTaskRuntimePhaseBriefingAssembler.UPSTREAM_PAYLOAD_TRUNCATION_MARKER),
      "normal-size payloads must not be truncated",
    )
    assertContains(briefing.briefingText, """{"plan":"$planBody"}""")
    assertContains(briefing.briefingText, """{"implement":"$implementBody"}""")
    assertContains(briefing.briefingText, """{"review":"$reviewBody"}""")
  }

  @Test
  fun `large-but-feasible layer-1 is counted in the total budget and the briefing stays within the ceiling`() {
    val largeCriterion = "AC-big: ${"contract-detail ".repeat(3_000)}"
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = listOf("AC-1: small", largeCriterion),
      mandatesAndOverrides = listOf("mandate-1: ${"override-detail ".repeat(20)}"),
    )
    val recordedOutputs = listOf(
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        iteration = 1,
        payload = """{"plan":"${"p".repeat(20_000)}"}""",
      ),
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = """{"implement":"${"i".repeat(20_000)}"}""",
      ),
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        iteration = 1,
        payload = """{"review":"${"r".repeat(20_000)}"}""",
      ),
    )
    val auditDeclaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
      .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = auditDeclaration,
      runInvariants = invariants,
      recordedOutputs = recordedOutputs,
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    val byteSize = briefing.briefingText.toByteArray(Charsets.UTF_8).size
    assertTrue(
      byteSize <= CEILING,
      "briefing with large layer-1 was $byteSize bytes, exceeding the $CEILING ceiling; " +
        "layer-1 was not counted against the total budget (only layer-2 was bounded).",
    )
    assertContains(briefing.briefingText, largeCriterion)
    assertContains(briefing.briefingText, FeatureTaskRuntimePhaseBriefingAssembler.UPSTREAM_PAYLOAD_TRUNCATION_MARKER)
  }

  @Test
  fun `pathologically large layer-1 loud-fails instead of emitting an over-budget or truncated-contract briefing`() {
    val pathologicalCriterion = "AC-huge: ${"x".repeat(CEILING + 10_000)}"
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = listOf(pathologicalCriterion),
      mandatesAndOverrides = emptyList(),
    )
    val handoff = FeatureTaskRuntimePhaseHandoff(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      runInvariants = invariants,
      upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
      derivedContextKeys = emptyList(),
    )
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    }
  }

  @Test
  fun `more than three large upstream outputs still stay within the total budget`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = listOf("AC-1: small contract"),
      mandatesAndOverrides = emptyList(),
    )
    val upstreamCount = 6
    val outputsByPhaseId = (1..upstreamCount).associate { index ->
      val phaseId = "upstream-$index"
      phaseId to FeatureTaskRuntimePhaseOutput(
        phaseId = phaseId,
        iteration = 1,
        payload = """{"out":"${"u".repeat(50_000)}"}""",
      )
    }
    val handoff = FeatureTaskRuntimePhaseHandoff(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      runInvariants = invariants,
      upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(outputsByPhaseId),
      derivedContextKeys = emptyList(),
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    val byteSize = briefing.briefingText.toByteArray(Charsets.UTF_8).size
    assertTrue(
      byteSize <= CEILING,
      "briefing with $upstreamCount large upstreams was $byteSize bytes, exceeding the $CEILING ceiling; " +
        "the budget is not upstream-count-independent (a fixed per-upstream cap was reintroduced).",
    )
    (1..upstreamCount).forEach { index ->
      assertContains(briefing.briefingText, "### from: upstream-$index")
    }
    assertContains(briefing.briefingText, FeatureTaskRuntimePhaseBriefingAssembler.UPSTREAM_PAYLOAD_TRUNCATION_MARKER)
  }

  @Test
  fun `genuine replacement chars at the truncation boundary survive (a trimEnd regression would strip them)`() {
    // A decoder-based prefix must keep genuine U+FFFD that survives within the byte
    // budget; only a blanket `trimEnd('�')` would strip them along with the partial
    // tail. The payload places the U+FFFD run (3 UTF-8 bytes each) right at the cut
    // boundary so the surviving prefix deterministically ends in genuine U+FFFD.
    val replacementChar = '�'
    val head = "head-boundary|"
    val payload = head + replacementChar.toString().repeat(100_000)
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = listOf("AC-1: small contract"),
      mandatesAndOverrides = emptyList(),
    )
    val handoff = FeatureTaskRuntimePhaseHandoff(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      runInvariants = invariants,
      upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(
        mapOf(
          "upstream-1" to FeatureTaskRuntimePhaseOutput(phaseId = "upstream-1", iteration = 1, payload = payload),
        ),
      ),
      derivedContextKeys = emptyList(),
    )
    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    val text = briefing.briefingText

    assertContains(text, head)
    assertContains(text, FeatureTaskRuntimePhaseBriefingAssembler.UPSTREAM_PAYLOAD_TRUNCATION_MARKER)

    val markerIndex = text.indexOf(FeatureTaskRuntimePhaseBriefingAssembler.UPSTREAM_PAYLOAD_TRUNCATION_MARKER)
    assertTrue(markerIndex > 0, "expected the truncation marker to follow a non-empty bounded prefix")
    assertEquals(
      replacementChar,
      text[markerIndex - 1],
      "the bounded prefix must end in a genuine U+FFFD from the payload; a trimEnd('�') regression stripped it",
    )

    val survivingGenuineReplacements = text.count { it == replacementChar }
    assertTrue(
      survivingGenuineReplacements > 1_000,
      "expected the bounded prefix to preserve the genuine U+FFFD run (got $survivingGenuineReplacements); " +
        "a trimEnd('�') regression would strip the trailing genuine replacement chars down toward zero",
    )

    val bodyStart = text.indexOf(head)
    val survivingBody = text.substring(bodyStart, markerIndex)
    assertEquals(
      head + replacementChar.toString().repeat(survivingBody.length - head.length),
      survivingBody,
      "the surviving bounded body must be exactly head + genuine U+FFFD run with no truncation-artifact leakage",
    )
  }
}
