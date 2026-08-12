package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RecordingLifecycleTelemetryRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticFailureClass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-185. A validation-gate repair cycle re-runs an agent inside one phase attempt, so its turns
 * used to address the identical producer-evidence key and the second turn killed the process with an
 * uncaught `Conflict`. These cover both halves of the fix: turns are independently addressable, and a
 * diagnostic-persistence failure degrades instead of terminating the run.
 */
class FeatureTaskRuntimeDiagnosticDegradationTest {
  @Test
  fun `three repair turns of one attempt each retain their own evidence row`() {
    val database = database()
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")

    (1..3).forEach { turn ->
      recorder.retainProducerOutput(evidence("turn-$turn".encodeToByteArray(), repairTurn = turn))
    }

    assertEquals(
      listOf(1, 2, 3),
      database.retainedProducerEvidence().filter { it.phaseId == "validate" }.map { it.repairTurn },
    )
    // The consumer-facing read resolves the newest turn without knowing how many turns ran.
    val found = recorder.producerOutput(WORKFLOW_ID, "validate", 1, "cursor")
    assertIs<FeatureTaskRuntimeProducerOutputRead.Found>(found)
    assertContentEquals("turn-3".encodeToByteArray(), found.evidence.payload)
    val absent = recorder.producerOutput(WORKFLOW_ID, "validate", 99, "cursor")
    assertIs<FeatureTaskRuntimeProducerOutputRead.Absent>(absent)
    assertTrue(recorder.loadDiagnosticSignals(WORKFLOW_ID).isEmpty())
  }

  @Test
  fun `two consecutively rejected repair turns each record a diagnostic`() {
    val database = database()
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")

    recorder.recordRejectedOutput(rejection("turn-1".encodeToByteArray(), repairTurn = 1))
    recorder.recordRejectedOutput(rejection("turn-2".encodeToByteArray(), repairTurn = 2))

    assertEquals(listOf(1, 2), database.rejectedDiagnostics().map { it.metadata.repairTurn })
    assertEquals(2, database.rejectedDiagnostics().map { it.metadata.identity }.distinct().size)
    assertTrue(recorder.loadDiagnosticSignals(WORKFLOW_ID).isEmpty())
  }

  @Test
  fun `a forced evidence conflict degrades to a durable payload-free signal instead of failing the run`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = database(lifecycle)
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")
    val retained = "first-bytes".encodeToByteArray()
    recorder.retainProducerOutput(evidence(retained, repairTurn = 1))

    // Same key, different bytes: the store's immutability guard raises Conflict. Before SKILL-185 this
    // escaped the run loop uncaught and exited the process with status 1.
    recorder.retainProducerOutput(evidence("divergent-bytes".encodeToByteArray(), repairTurn = 1))

    val signal = recorder.loadDiagnosticSignals(WORKFLOW_ID).single()
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT, signal.failureClass)
    assertEquals("validate", signal.phaseId)
    assertEquals(1, signal.attempt)
    assertEquals(1, signal.repairTurn)
    assertContains(signal.conflictingKey, "$WORKFLOW_ID:validate:0:1:1:cursor")
    val measurement = lifecycle.diagnosticDegradationMeasurements.single()
    assertEquals(signal.phaseId, measurement.phaseId)
    assertEquals(signal.attempt, measurement.attempt)
    assertEquals(signal.repairTurn, measurement.repairTurn)
    assertEquals(signal.generation, measurement.generation)
    assertEquals("retain-producer-output", measurement.operation)
    assertEquals(signal.failureClass, measurement.failureClass)
    assertEquals(signal.conflictingKey, measurement.conflictingKey)
    assertTrue(
      measurement.toTelemetryMap().values.none { it is String && it.contains("divergent-bytes") },
      "the measurement must not carry the divergent agent bytes",
    )
    // The committed evidence is never overwritten by the divergent write.
    val found = recorder.producerOutput(WORKFLOW_ID, "validate", 1, "cursor")
    assertIs<FeatureTaskRuntimeProducerOutputRead.Found>(found)
    assertContentEquals(retained, found.evidence.payload)
  }

  @Test
  fun `a throwing telemetry sink leaves the durable signal and the null return intact`() {
    val lifecycle = RecordingLifecycleTelemetryRepository(throwOnDiagnosticDegradation = true)
    val database = database(lifecycle)
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")
    recorder.retainProducerOutput(evidence("first-bytes".encodeToByteArray(), repairTurn = 1))

    recorder.retainProducerOutput(evidence("divergent-bytes".encodeToByteArray(), repairTurn = 1))

    val signal = recorder.loadDiagnosticSignals(WORKFLOW_ID).single()
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT, signal.failureClass)
    assertTrue(lifecycle.diagnosticDegradationMeasurements.isEmpty())
  }

  @Test
  fun `a missing rejected-output port is Unreadable persistence rather than Absent`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = RuntimeFakeDatabaseSessionFactory(
      InMemoryRuntimeWorkflowRepository(),
      lifecycle,
      rejectedOutputDiagnosticsAvailable = false,
    )
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")

    val read = recorder.producerOutput(WORKFLOW_ID, "validate", 1, "cursor")

    val unreadable = assertIs<FeatureTaskRuntimeProducerOutputRead.Unreadable>(read)
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE, unreadable.failureClass)
    val signal = recorder.loadDiagnosticSignals(WORKFLOW_ID).single()
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE, signal.failureClass)
    assertEquals(1, lifecycle.diagnosticDegradationMeasurements.size)
  }

  @Test
  fun `a caller-construction defect still fails loudly instead of degrading`() {
    val database = database()
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")

    // A blank agent id is a defect in the calling seam, not an environmental store fault. Degrading
    // it would let a run write evidence attributable to nobody and walk past the bug.
    assertFailsWith<RejectedOutputDiagnosticError.InvalidRequest> {
      recorder.recordRejectedOutput(rejection(byteArrayOf(1), repairTurn = 1).copy(agentId = ""))
    }
    assertTrue(recorder.loadDiagnosticSignals(WORKFLOW_ID).isEmpty())
  }

  @Test
  fun `a degraded signal never carries agent bytes`() {
    val database = database()
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")
    recorder.retainProducerOutput(evidence("secret-agent-output".encodeToByteArray(), repairTurn = 1))

    recorder.retainProducerOutput(evidence("other-secret-output".encodeToByteArray(), repairTurn = 1))

    val summary = recorder.loadDiagnosticSignals(WORKFLOW_ID).single().operatorSummary()
    assertTrue("secret-agent-output" !in summary)
    assertTrue("other-secret-output" !in summary)
    assertContains(summary, "attempt 1")
    assertContains(summary, "repair turn 1")
  }

  @Test
  fun `rejected audit sentinel stays private when diagnostic persistence degrades`() {
    // SKILL-187 AC-008/AC-011: degradation must not change privacy — operator summary stays payload-free
    // while the first rejected row still retains the synthetic bytes.
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = database(lifecycle)
    val recorder = recorder(database)
    recorder.ensureWorkflowOpen(WORKFLOW_ID, "session-1")
    val sentinel = "SKILL187-DEGRADE-SENTINEL".encodeToByteArray()
    recorder.recordRejectedOutput(
      rejection(sentinel, repairTurn = 1).copy(
        phaseId = "audit",
        reason = "observation: does not have a value in the enumeration — offending value: blast_radius_inspected",
      ),
    )
    // Force a conflict on the same repair-turn evidence key so degradation emits a payload-free signal.
    recorder.retainProducerOutput(evidence(sentinel, repairTurn = 1).copy(phaseId = "audit"))
    recorder.retainProducerOutput(
      evidence("divergent-bytes".encodeToByteArray(), repairTurn = 1).copy(phaseId = "audit"),
    )

    val diagnostic = database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
    assertContentEquals(sentinel, diagnostic.payload)
    assertContains(diagnostic.metadata.reason, "blast_radius_inspected")
    val summary = recorder.loadDiagnosticSignals(WORKFLOW_ID).single().operatorSummary()
    assertTrue("SKILL187-DEGRADE-SENTINEL" !in summary)
    assertTrue("blast_radius_inspected" !in summary)
    assertContains(summary, "audit")
    val measurement = lifecycle.diagnosticDegradationMeasurements.single().toTelemetryMap()
    assertTrue(measurement.values.none { it is String && "SKILL187-DEGRADE-SENTINEL" in it })
    assertTrue(measurement.values.none { it is String && "blast_radius_inspected" in it })
  }

  private fun database(
    lifecycle: RecordingLifecycleTelemetryRepository = RecordingLifecycleTelemetryRepository(),
  ) = RuntimeFakeDatabaseSessionFactory(
    InMemoryRuntimeWorkflowRepository(),
    lifecycle,
  )

  private fun recorder(database: RuntimeFakeDatabaseSessionFactory) = FeatureTaskRuntimePhaseRecorder(
    database,
    NoopSnapshotValidator,
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  )

  private fun evidence(payload: ByteArray, repairTurn: Int) = ProducerOutputEvidence(
    workflowId = WORKFLOW_ID,
    phaseId = "validate",
    attempt = 1,
    agentId = "cursor",
    model = "gpt",
    recordedAt = Instant.parse("2026-08-11T21:07:48Z"),
    byteSize = payload.size.toLong(),
    sha256 = RejectedOutputDiagnosticService.sha256(payload),
    payload = payload,
    repairTurn = repairTurn,
  )

  private fun rejection(payload: ByteArray, repairTurn: Int) = RejectedOutputDiagnosticRequest(
    workflowId = WORKFLOW_ID,
    phaseId = "validate",
    attempt = 1,
    rule = "phase-output-schema",
    path = "/status",
    reason = "rejected",
    agentId = "cursor",
    model = "gpt",
    rawResponse = payload,
    repairTurn = repairTurn,
  )

  private object NoopSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }

  private companion object {
    const val WORKFLOW_ID: String = "wftr-skill-185"
  }
}
