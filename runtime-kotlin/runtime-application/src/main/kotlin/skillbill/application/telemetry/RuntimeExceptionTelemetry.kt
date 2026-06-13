package skillbill.application.telemetry

import skillbill.contracts.JsonSupport
import skillbill.ports.persistence.TelemetryOutboxRepository

const val RUNTIME_EXCEPTION_EVENT = "skillbill_runtime_exception"

private const val MAX_STACK_FRAMES = 12
private const val MAX_MESSAGE_LENGTH = 512

fun enqueueRuntimeException(outbox: TelemetryOutboxRepository, workflowPhase: String, error: Exception) {
  val payload = mapOf(
    "workflow_phase" to workflowPhase,
    "error_type" to (error.javaClass.name.substringAfterLast('.')),
    "error_message" to (error.message.orEmpty().take(MAX_MESSAGE_LENGTH)),
    "stack_trace" to redactedStackTrace(error),
  )
  outbox.enqueue(RUNTIME_EXCEPTION_EVENT, JsonSupport.mapToJsonString(payload))
}

private fun redactedStackTrace(error: Exception): String = error.stackTrace
  .take(MAX_STACK_FRAMES)
  .joinToString("\n") { frame ->
    "${frame.className}.${frame.methodName}:${frame.lineNumber}"
  }
