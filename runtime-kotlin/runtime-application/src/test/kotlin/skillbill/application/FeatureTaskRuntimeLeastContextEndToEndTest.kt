package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
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
    assertEquals(
      forwardPhases,
      launchedPhases,
      "the integrated matrix must cover every forward consumer in dependency order",
    )

    val briefings = assertNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID))
    val deliveredRecords = assertNotNull(harness.recorder.loadDeliveredProjections(WORKFLOW_ID))
    assertEquals(launchedPhases.toSet(), briefings.keys)
    assertEquals(launchedPhases.toSet(), deliveredRecords.keys)

    launchedPhases.forEach { phaseId ->
      val declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.getValue(phaseId)
      val briefing = briefings.getValue(phaseId)
      val delivered = deliveredRecords.getValue(phaseId)
      val envelope = delivered.envelope

      assertEquals(phaseId, briefing.phaseId)
      assertEquals(phaseId, envelope.consumerPhaseId)
      assertEquals(briefing.handoffEnvelope, envelope)
      assertEquals(
        declaration.projectionDeclarations.map { it.projectionName },
        envelope.projections.map { it.projectionName },
        "$phaseId received a projection outside its closed declaration or missed a required projection",
      )
      declaration.projectionDeclarations.zip(envelope.projections).forEach { (declared, actual) ->
        assertEquals(declared.sourceRef, actual.sourceRef, "${actual.projectionName} changed source")
        assertEquals(
          declared.projectionContractId to declared.projectionContractVersion,
          actual.projectionContractId to actual.projectionContractVersion,
          "${actual.projectionName} changed contract identity",
        )
        assertEquals(
          declared.declaredFieldNames,
          actual.fields.map { it.name },
          "${actual.projectionName} changed its required-field shape",
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

    val privatePhaseRecords = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals(launchedPhases.toSet(), privatePhaseRecords.keys)
    launchedPhases.forEach { phaseId ->
      val privateOutput = assertNotNull(privatePhaseRecords.getValue(phaseId).outputArtifact)
      val deliveredWire = deliveredRecords.getValue(phaseId).toArtifactMap().toString()
      assertNotEquals(
        privateOutput,
        deliveredWire,
        "$phaseId merged private producer evidence into its delivered projection record",
      )
    }
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
