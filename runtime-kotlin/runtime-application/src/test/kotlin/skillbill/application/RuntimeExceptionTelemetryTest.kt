package skillbill.application

import skillbill.application.telemetry.RUNTIME_EXCEPTION_EVENT
import skillbill.application.telemetry.enqueueRuntimeException
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RuntimeExceptionTelemetryTest {
  @Test
  fun `enqueueRuntimeException enqueues exactly one exception event`() {
    val captured = mutableListOf<Pair<String, String>>()
    val outbox = capturingOutbox(captured)

    enqueueRuntimeException(outbox, "my_tool", RuntimeException("something went wrong"))

    assertEquals(1, captured.size)
    assertEquals(RUNTIME_EXCEPTION_EVENT, captured[0].first)
  }

  @Test
  fun `enqueueRuntimeException includes workflow_phase error_type and error_message`() {
    val captured = mutableListOf<Pair<String, String>>()
    val outbox = capturingOutbox(captured)
    enqueueRuntimeException(outbox, "feature_implement_workflow_open", IllegalStateException("bad state"))

    val payload = captured[0].second
    assert(payload.contains("feature_implement_workflow_open")) { "payload must contain workflow_phase" }
    assert(payload.contains("IllegalStateException")) { "payload must contain error_type" }
    assert(payload.contains("bad state")) { "payload must contain error_message" }
  }

  @Test
  fun `enqueueRuntimeException does not include file paths or home directory in stack trace`() {
    val captured = mutableListOf<Pair<String, String>>()
    enqueueRuntimeException(capturingOutbox(captured), "test_tool", RuntimeException("fail"))

    val payload = captured[0].second
    assertFalse(payload.contains("/home/"), "stack trace must not contain /home/ paths")
    assertFalse(payload.contains("\\Users\\"), "stack trace must not contain \\Users\\ paths")
  }

  private fun capturingOutbox(captured: MutableList<Pair<String, String>>): TelemetryOutboxRepository =
    object : TelemetryOutboxRepository {
      override fun enqueue(eventName: String, payloadJson: String): Long {
        captured.add(eventName to payloadJson)
        return captured.size.toLong()
      }

      override fun listPending(limit: Int?): List<TelemetryOutboxRecord> = emptyList()
      override fun pendingCount(): Int = 0
      override fun latestError(): String? = null
      override fun markSynced(id: Long, syncedAt: String) = Unit
      override fun markSynced(eventIds: List<Long>) = Unit
      override fun markFailed(id: Long, lastError: String) = Unit
      override fun markFailed(eventIds: List<Long>, lastError: String) = Unit
      override fun clear(): Int = 0
    }
}
