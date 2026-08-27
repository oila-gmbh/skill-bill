package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.application.featuretask.phaseDeclaration
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrated closed-world proof over a complete runtime launch. Narrow suites exercise retries,
 * remediation, continuation, goal children, providers, and persistence failure modes; this matrix
 * pins the common boundary they all use so those surfaces cannot drift to different context shapes.
 */
class FeatureTaskRuntimeLeastContextEndToEndTest {
  @Test
  fun `every forward consumer receives exactly its declared bounded projection and no private evidence`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launchedPhases = harness.launchedPromptPhaseOrder()
    val forwardPhases = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.run {
      forwardPhaseIds.filterNot(loopOnlyPhaseIds::contains)
    }
    val agentPhases = forwardPhases.filterNot { it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW }
    assertEquals(
      agentPhases,
      launchedPhases,
      "agent-launched phases omit runtime-owned review and cover every other forward consumer",
    )

    val briefings = assertNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID))
    val deliveredRecords = assertNotNull(harness.recorder.loadDeliveredProjections(WORKFLOW_ID))
    assertEquals(forwardPhases.toSet(), briefings.keys)
    assertEquals(forwardPhases.toSet(), deliveredRecords.keys)

    forwardPhases.forEach { phaseId ->
      assertConsumerDelivery(phaseId, briefings.getValue(phaseId), deliveredRecords.getValue(phaseId))
    }

    val privatePhaseRecords = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals(forwardPhases.toSet(), privatePhaseRecords.keys)
    forwardPhases.forEach { phaseId ->
      val privateOutput = assertNotNull(privatePhaseRecords.getValue(phaseId).outputArtifact)
      val deliveredWire = deliveredRecords.getValue(phaseId).toArtifactMap().toString()
      assertNotEquals(
        privateOutput,
        deliveredWire,
        "$phaseId merged private producer evidence into its delivered projection record",
      )
    }
  }

  @Suppress("LongMethod")
  private fun assertConsumerDelivery(
    phaseId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    delivered: FeatureTaskRuntimeDeliveredProjectionRecord,
  ) {
    val declaration = phaseDeclaration(
      phaseId,
      FeatureTaskRuntimeFeatureSize.valueOf(briefing.featureSize),
    )
    // required=false declarations (shared_review_evidence) may omit when the resolver has no
    // store path; the closed world still forbids undeclared projections and requires every
    // required declaration plus all run-invariant projections.
    val declared = declaration.projectionDeclarations + invariantDeclarations(phaseId)
    val deliveredNames = delivered.envelope.projections.map { it.projectionName }.toSet()
    val expectedDeclarations = declared.filter { it.required || it.projectionName in deliveredNames }
    val envelope = delivered.envelope

    assertEquals(phaseId, briefing.phaseId)
    assertEquals(phaseId, envelope.consumerPhaseId)
    assertEquals(briefing.handoffEnvelope, envelope)
    assertEquals(
      expectedDeclarations.map { it.projectionName },
      envelope.projections.map { it.projectionName },
      "$phaseId received a projection outside its closed declaration or missed a required projection",
    )
    declared.filter { it.required }.forEach { required ->
      assertTrue(
        required.projectionName in deliveredNames,
        "$phaseId missed required projection ${required.projectionName}",
      )
    }
    expectedDeclarations.zip(envelope.projections).forEach { (declaredProjection, actual) ->
      assertEquals(declaredProjection.sourceRef, actual.sourceRef, "${actual.projectionName} changed source")
      assertEquals(
        declaredProjection.projectionContractId to declaredProjection.projectionContractVersion,
        actual.projectionContractId to actual.projectionContractVersion,
        "${actual.projectionName} changed contract identity",
      )
      val deliveredNames = actual.fields.map { it.name }
      assertTrue(
        deliveredNames.all { it in declaredProjection.declaredFieldNames },
        "${actual.projectionName} delivered undeclared fields: " +
          "${deliveredNames - declaredProjection.declaredFieldNames.toSet()}",
      )
      val requiredNames = declaredProjection.declaredFieldNames.filterNot { name ->
        declaredProjection.projectionContractId ==
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PREPLAN_PROSE &&
          name == "directive"
      }
      assertTrue(
        requiredNames.all { it in deliveredNames },
        "${actual.projectionName} missing required fields: ${requiredNames - deliveredNames.toSet()}",
      )
    }
    assertEquals(declaration.derivedContextKeys, briefing.derivedContextKeys)
    assertTrue(
      envelope.projections.all {
        it.promptVisibility == FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE
      },
      "$phaseId persisted a private-evidence projection in the delivered tier",
    )
    assertEquals(
      envelope.projections.map { it.producerIteration }.distinct(),
      delivered.sourceProducerIterations,
      "$phaseId lost the exact producer iteration identity",
    )
    assertEquals(
      envelope.repositoryCheckpoint?.fingerprint ?: "not_required:$phaseId",
      delivered.repositoryCheckpointFingerprint,
      "$phaseId delivered record and envelope disagree on repository identity",
    )

    val deliveredWire = delivered.toArtifactMap()
    assertNoForbiddenStructuralField(deliveredWire, phaseId)
    assertFalse(
      deliveredWire.containsKey("output_artifact"),
      "$phaseId delivered record exposed a complete private phase artifact",
    )
  }

  private fun invariantDeclarations(phaseId: String): List<PhaseHandoffProjectionDeclaration> =
    FeatureTaskRuntimeRunInvariantPromptAllowlist.forPhase(phaseId).map { field ->
      val ceremonyScaling = field == FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING
      PhaseHandoffProjectionDeclaration(
        consumerPhaseId = phaseId,
        sourceRef = if (ceremonyScaling) {
          FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling
        } else {
          FeatureTaskRuntimeHandoffSourceRef.RunInvariantField(field)
        },
        projectionName = "run_invariant_${field.wireValue}",
        projectionContractId = "feature_task_runtime.run_invariant",
        projectionContractVersion = "0.1",
        promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
        budget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
        declaredFieldNames = listOf(
          if (ceremonyScaling) {
            FeatureTaskRuntimeHandoffProjectionValidator.CEREMONY_SCALING_FIELD
          } else {
            field.wireValue
          },
        ),
      )
    }

  private fun assertNoForbiddenStructuralField(value: Any?, consumerPhaseId: String) {
    when (value) {
      is Map<*, *> -> {
        val keys = value.keys.filterIsInstance<String>().toSet()
        val forbidden = keys intersect FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
        assertTrue(
          forbidden.isEmpty(),
          "$consumerPhaseId delivered forbidden structural fields: ${forbidden.sorted()}",
        )
        value.values.forEach { assertNoForbiddenStructuralField(it, consumerPhaseId) }
      }
      is Iterable<*> -> value.forEach { assertNoForbiddenStructuralField(it, consumerPhaseId) }
    }
  }
}
