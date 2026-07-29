package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.RejectedOutputDiagnosticService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.io.OutputStream

private const val CONTROL_CHARACTER_LIMIT: Int = 0x20
private const val DELETE_CHARACTER_CODE: Int = 0x7f
private const val HEX_RADIX: Int = 16
private const val UNICODE_ESCAPE_WIDTH: Int = 4

data class RejectedOutputInspectRequest(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
  val rawOutput: Boolean = false,
  val offset: Long = 0,
  val length: Long? = null,
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
      service.streamRaw(matches.single().identity, output, request.offset, request.length)
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
  fun execute(request: RejectedOutputCleanupRequest): Int = service.delete(request.selector())
}

@Inject
class RejectedOutputInspectCliCommand(
  private val database: DatabaseSessionFactory,
  private val state: CliRunState,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
) : DocumentedCliCommand(
  "rejected-output",
  "Inspect rejected phase output metadata, or emit one exact stored body with --raw-output.",
) {
  private val workflowId by option("--workflow", help = "Workflow identifier.").required()
  private val phaseId by option("--phase", help = "Optional phase selector.")
  private val attempt by option("--attempt", help = "Optional attempt selector.").int()
  private val rawOutput by option(
    "--raw-output",
    help = "Write the exact stored response bytes; the selector must resolve to one record.",
  ).flag(default = false)
  private val offset by option("--offset", help = "Zero-based byte offset for --raw-output.").long()
  private val length by option("--length", help = "Maximum bytes to stream for --raw-output.").long()

  override fun run() {
    if (!rawOutput && (offset != null || length != null)) {
      throw UsageError("--offset and --length require --raw-output.")
    }
    if (offset != null && requireNotNull(offset) < 0) throw UsageError("--offset must be non-negative.")
    if (length != null && requireNotNull(length) < 0) throw UsageError("--length must be non-negative.")
    database.read(state.dbOverride) { unitOfWork ->
      RejectedOutputInspectCommand(unitOfWork.diagnosticService(metadataValidator)).execute(
        RejectedOutputInspectRequest(
          workflowId,
          phaseId,
          attempt,
          rawOutput,
          offset ?: 0,
          length,
        ),
        System.out,
      )
    }
  }
}

@Inject
class RejectedOutputCleanupCliCommand(
  private val database: DatabaseSessionFactory,
  private val state: CliRunState,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
) : DocumentedCliCommand(
  "rejected-output-cleanup",
  "Delete rejected-output diagnostics selected within one workflow.",
) {
  private val workflowId by option("--workflow", help = "Workflow identifier.").required()
  private val phaseId by option("--phase", help = "Optional phase selector.")
  private val attempt by option("--attempt", help = "Optional attempt selector.").int()

  override fun run() {
    val deleted = database.transaction(state.dbOverride) { unitOfWork ->
      RejectedOutputCleanupCommand(unitOfWork.diagnosticService(metadataValidator)).execute(
        RejectedOutputCleanupRequest(workflowId, phaseId, attempt),
      )
    }
    echo("deleted=$deleted")
  }
}

private fun skillbill.ports.persistence.UnitOfWork.diagnosticService(
  metadataValidator: RejectedOutputDiagnosticMetadataValidator,
): RejectedOutputDiagnosticService = RejectedOutputDiagnosticService(
  rejectedOutputDiagnostics ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable"),
  rejectedOutputDiagnosticPermissions ?: throw RejectedOutputDiagnosticError.Permission("permissions-unavailable"),
  metadataValidator,
)

private fun RejectedOutputInspectRequest.selector() = RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt)

private fun RejectedOutputCleanupRequest.selector() = RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt)

private fun RejectedOutputDiagnostic.safeLine(): String = listOf(
  "identity=${identity.safeField()}",
  "workflow=${workflowId.safeField()}",
  "phase=${phaseId.safeField()}",
  "attempt=$attempt",
  "rule=${rule.safeField()}",
  "path=${path.safeField()}",
  "reason=${reason.safeField()}",
  "agent=${agentId.safeField()}",
  "model=${model.safeField()}",
  "recorded_at=$recordedAt",
  "byte_size=$byteSize",
  "sha256=$sha256",
  "lifecycle=${lifecycle.name.lowercase()}",
).joinToString(" ")

private fun String.safeField(): String = buildString {
  append('"')
  this@safeField.forEach { character ->
    when (character) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (character.code < CONTROL_CHARACTER_LIMIT || character.code == DELETE_CHARACTER_CODE) {
        append("\\u")
        append(character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
      } else {
        append(character)
      }
    }
  }
  append('"')
}
