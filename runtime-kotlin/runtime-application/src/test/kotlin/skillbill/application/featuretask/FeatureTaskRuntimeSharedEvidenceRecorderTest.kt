package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RecordingLifecycleTelemetryRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeSharedEvidenceRecorderTest {
  @Test
  fun `exactly one derivation and N-1 reuse events are emitted for N consumers at an unchanged fingerprint`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val recorder = recorder(lifecycle)
    recorder.ensureWorkflowOpen("wf-shared", "session-1")
    val fingerprint = "fp-stable"
    val consumers = listOf("audit", "review", "review_lane_architecture", "review_lane_testing")

    consumers.forEachIndexed { index, phaseId ->
      val outcome = if (index == 0) {
        FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION
      } else {
        FeatureTaskRuntimeSharedEvidenceOutcome.REUSE
      }
      recorder.recordPhaseBriefing(
        workflowId = "wf-shared",
        briefing = emptyBriefing(phaseId),
        sharedEvidenceMeasurement = measurement(phaseId, fingerprint, outcome),
      )
    }

    val outcomes = lifecycle.sharedEvidenceMeasurements.map { it.outcome }
    assertEquals(1, outcomes.count { it == FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION })
    assertEquals(consumers.size - 1, outcomes.count { it == FeatureTaskRuntimeSharedEvidenceOutcome.REUSE })
    assertEquals(consumers, lifecycle.sharedEvidenceMeasurements.map { it.consumerPhaseId })
  }

  @Test
  fun `a changed checkpoint fingerprint emits a re-derivation event with checkpoint-change attribution`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val recorder = recorder(lifecycle)
    recorder.ensureWorkflowOpen("wf-shared", "session-1")

    recorder.recordPhaseBriefing(
      workflowId = "wf-shared",
      briefing = emptyBriefing("audit"),
      sharedEvidenceMeasurement = measurement(
        "audit",
        "fp-before",
        FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION,
      ),
    )
    recorder.recordPhaseBriefing(
      workflowId = "wf-shared",
      briefing = emptyBriefing("audit"),
      sharedEvidenceMeasurement = measurement(
        "audit",
        "fp-after",
        FeatureTaskRuntimeSharedEvidenceOutcome.CHECKPOINT_CHANGE_REDERIVATION,
      ),
    )

    assertEquals(
      listOf(
        FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION,
        FeatureTaskRuntimeSharedEvidenceOutcome.CHECKPOINT_CHANGE_REDERIVATION,
      ),
      lifecycle.sharedEvidenceMeasurements.map { it.outcome },
    )
    assertEquals(
      listOf("fp-before", "fp-after"),
      lifecycle.sharedEvidenceMeasurements.map { it.checkpointFingerprint },
    )
  }

  private fun recorder(lifecycle: RecordingLifecycleTelemetryRepository) = FeatureTaskRuntimePhaseRecorder(
    RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), lifecycle),
    NoopWorkflowSnapshotValidator,
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  )

  private fun emptyBriefing(phaseId: String) = FeatureTaskRuntimePhaseLaunchBriefing(
    phaseId = phaseId,
    specReference = "spec.md",
    featureSize = "MEDIUM",
    acceptanceCriteria = listOf("AC-001"),
    mandatesAndOverrides = emptyList(),
    handoffEnvelope = FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = phaseId,
      projections = emptyList(),
    ),
    derivedContextKeys = emptyList(),
    briefingText = "briefing",
  )

  private fun measurement(phaseId: String, fingerprint: String, outcome: FeatureTaskRuntimeSharedEvidenceOutcome) =
    FeatureTaskRuntimeSharedEvidenceMeasurement(
      workflowId = "wf-shared",
      checkpointFingerprint = fingerprint,
      consumerPhaseId = phaseId,
      outcome = outcome,
      fileIndexCount = 1,
      hunkIndexCount = 1,
    )

  private object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }
}
