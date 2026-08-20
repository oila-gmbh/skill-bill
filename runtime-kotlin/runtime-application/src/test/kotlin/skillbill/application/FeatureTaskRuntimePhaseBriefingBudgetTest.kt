package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val CEILING = FeatureTaskRuntimePhaseBriefingAssembler.FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING

class FeatureTaskRuntimePhaseBriefingBudgetTest {

  @Test
  fun `an oversized upstream projection is rejected, never truncated into the briefing`() {
    val oversizedBytes = 400_000
    val checkpoint = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(
      "fixture-checkpoint",
    )
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = multiUpstreamOutputs(oversizedBytes),
      repositoryCheckpoint = checkpoint,
      expectedRepositoryCheckpoint = checkpoint,
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
  fun `normal-size review repair projection excludes unrelated upstream bodies`() {
    val planBody = "p".repeat(4000)
    val implementBody = "i".repeat(4000)
    val reviewBody = "r".repeat(4000)
    val checkpoint = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(
      "fixture-checkpoint",
    )
    val recordedOutputs = listOf(
      phaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        """{"produced_outputs":{"finding_dispositions":[{"finding_id":"F-1","disposition":"verified",""" +
          """"reason":"Matches spec intent.","severity":"blocker","location":"x","message":"fix"}]}}""",
      ),
    )
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = recordedOutputs,
      repositoryCheckpoint = checkpoint,
      expectedRepositoryCheckpoint = checkpoint,
    )

    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    assertFalse(briefing.briefingText.contains(planBody))
    assertFalse(briefing.briefingText.contains(implementBody))
    assertFalse(briefing.briefingText.contains(reviewBody))
    assertContains(briefing.briefingText, "unresolved_blocker_findings")
    assertContains(briefing.briefingText, "repository_checkpoint")
  }

  @Test
  fun `an undeclared recorded output is never delivered, even when it is present in state`() {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      // `implement` declares only `plan`; the recorded `review` output has no declaration.
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = listOf(
        phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, planProjectionOutput()),
        phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, """{"review":"undeclared"}"""),
      ),
    )

    val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)

    // `implement` receives plan as the bounded executable-plan projection, not a coarse receipt.
    assertTrue(briefing.briefingText.contains("Fixture task."))
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

    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff, workflowId = "wftr-1")
    }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
    assertEquals("wftr-1", error.workflowId)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, error.consumerPhaseId)
    assertEquals("run_invariant_acceptance_criteria", error.projectionName)
    assertContains(error.reason, "bytes")
    assertContains(error.reason, "budget")
    assertFalse(
      error.message.orEmpty().contains("x".repeat(64)),
      "a projection rejection must name the measured size, never echo the contract content it refused to deliver",
    )
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
    assertContains(
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).briefingText,
      "acceptance_criteria:",
    )
  }

  @Test
  fun `the rendered briefing carries no forbidden raw-context field name`() {
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      runInvariants = multiUpstreamInvariants(),
      recordedOutputs = listOf(
        phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, planProjectionOutput()),
      ),
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

  @Test
  fun `shared evidence projection size is independent of branch diff size for review and audit`() {
    fun evidence(hunksPerFile: Int) =
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference(
        storePath = ".skill-bill/run-evidence/wf/fp",
        checkpointFingerprint = "fp",
        baseRef = "base",
        headRef = "head",
        fileHunkIndex = (1..8).map { "modified f$it.kt hunks=$hunksPerFile" },
      )

    fun projectionBytes(phaseId: String, hunksPerFile: Int): Int {
      val declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.getValue(phaseId)
      val handoff = FeatureTaskRuntimePhaseHandoff(
        phaseId = phaseId,
        runInvariants = FeatureTaskRuntimeRunInvariants(
          specReference = ".feature-specs/SKILL-164/spec.md",
          acceptanceCriteria = listOf("AC-002"),
          mandatesAndOverrides = emptyList(),
        ),
        upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
        derivedContextKeys = declaration.derivedContextKeys,
        projectionDeclarations = listOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.sharedReviewEvidenceDeclaration(phaseId),
        ),
        repositoryCheckpoint = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(
          fingerprint = "fp",
        ),
      )
      val briefing = FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
        sharedReviewEvidence = evidence(hunksPerFile),
      )
      assertFalse(briefing.briefingText.contains("@@"), "diff hunk bodies must not reach the briefing")
      assertFalse(briefing.briefingText.contains("+val "), "diff bytes must not reach the briefing")
      val marker = "file_hunk_index:"
      val start = briefing.briefingText.indexOf(marker)
      assertTrue(start >= 0, "shared evidence projection must render for $phaseId")
      return briefing.briefingText.substring(start).toByteArray(Charsets.UTF_8).size
    }

    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    ).forEach { phaseId ->
      val small = projectionBytes(phaseId, hunksPerFile = 1)
      val large = projectionBytes(phaseId, hunksPerFile = 400)
      // Digits of the hunk counter may grow slightly; the payload must not scale with hunk bodies.
      assertTrue(
        large - small <= 8 * 2,
        "shared evidence briefing size for $phaseId grew from $small to $large across hunk counts",
      )
    }
  }

  private fun multiUpstreamInvariants() = FeatureTaskRuntimeRunInvariants(
    specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
    acceptanceCriteria = (1..11).map { "AC-$it: ${"criterion-detail ".repeat(20)}" },
    mandatesAndOverrides = (1..6).map { "mandate-$it: ${"override-detail ".repeat(20)}" },
  )

  private fun multiUpstreamOutputs(bodyBytes: Int) = listOf(
    phaseOutput(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      """{"produced_outputs":{"finding_dispositions":[{"finding_id":"F-1","disposition":"verified",""" +
        """"reason":"Matches spec intent.","severity":"blocker","location":"x","message":"${"r".repeat(
          bodyBytes,
        )}"}]}}""",
    ),
  )

  private fun phaseOutput(phaseId: String, payload: String) =
    FeatureTaskRuntimePhaseOutput(phaseId = phaseId, iteration = 1, payload = payload)

  // `plan` feeds implement's bounded executable-plan projection, so its recorded output is a full
  // envelope carrying the declared projection body.
  private fun planProjectionOutput(): String = """{"contract_version":"0.2","phase_id":"plan","status":"completed",""" +
    """"summary":"Phase produced a validated output.","produced_outputs":""" +
    PlanningProjectionFixtures.EXECUTABLE_PLAN + "}"
}
