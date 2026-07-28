package skillbill.cli.featuretask

import skillbill.application.featuretask.RejectedOutputDiagnosticService
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import java.io.OutputStream

data class RejectedOutputInspectRequest(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
  val rawOutput: Boolean = false,
)

data class RejectedOutputCleanupRequest(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
)

class RejectedOutputInspectCommand(
  private val service: RejectedOutputDiagnosticService,
) {
  fun execute(request: RejectedOutputInspectRequest, output: OutputStream) {
    val matches = service.inspect(request.selector())
    if (matches.isEmpty()) throw RejectedOutputDiagnosticError.Absent(request.workflowId)
    if (request.rawOutput) {
      if (matches.size != 1) {
        throw RejectedOutputDiagnosticError.Retrieval(
          "raw output requires a selector resolving to exactly one diagnostic",
        )
      }
      output.write(service.readRaw(matches.single().identity))
      return
    }
    matches.forEach { metadata ->
      output.write((metadata.safeLine() + "\n").encodeToByteArray())
    }
  }
}

class RejectedOutputCleanupCommand(
  private val service: RejectedOutputDiagnosticService,
) {
  fun execute(request: RejectedOutputCleanupRequest): Int =
    service.delete(request.selector())
}

private fun RejectedOutputInspectRequest.selector() =
  RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt)

private fun RejectedOutputCleanupRequest.selector() =
  RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt)

private fun RejectedOutputDiagnostic.safeLine(): String = listOf(
  "identity=$identity",
  "workflow=$workflowId",
  "phase=$phaseId",
  "attempt=$attempt",
  "rule=$rule",
  "path=$path",
  "reason=$reason",
  "agent=$agentId",
  "model=$model",
  "recorded_at=$recordedAt",
  "byte_size=$byteSize",
  "sha256=$sha256",
  "lifecycle=${lifecycle.name.lowercase()}",
).joinToString(" ")
