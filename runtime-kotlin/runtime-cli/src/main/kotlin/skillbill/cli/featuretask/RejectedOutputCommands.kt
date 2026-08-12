package skillbill.cli.featuretask

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
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
  val repairTurn: Int? = null,
)

data class RejectedOutputCleanupRequest(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
  val repairTurn: Int? = null,
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
          "raw output requires a selector resolving to exactly one diagnostic; " +
            "an attempt that ran a validation-gate repair cycle holds one per repair turn, " +
            "so add --repair-turn (the metadata listing prints each turn)",
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
  private val repairTurn by option(
    "--repair-turn",
    help = "Optional validation-gate repair-turn selector within one attempt; 0 is an ordinary attempt.",
  ).int()
  private val rawOutput by option(
    "--raw-output",
    help = "Write the exact stored response bytes; the selector must resolve to one record.",
  ).flag(default = false)

  override fun run() {
    database.selfManagedWrite(state.dbOverride) { unitOfWork ->
      RejectedOutputInspectCommand(unitOfWork.diagnosticService(metadataValidator)).execute(
        RejectedOutputInspectRequest(workflowId, phaseId, attempt, rawOutput, repairTurn),
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
  private val repairTurn by option(
    "--repair-turn",
    help = "Optional validation-gate repair-turn selector within one attempt; 0 is an ordinary attempt.",
  ).int()

  override fun run() {
    val deleted = database.transaction(state.dbOverride) { unitOfWork ->
      RejectedOutputCleanupCommand(unitOfWork.diagnosticService(metadataValidator)).execute(
        RejectedOutputCleanupRequest(workflowId, phaseId, attempt, repairTurn),
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

private fun RejectedOutputInspectRequest.selector() =
  RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt, repairTurn)

private fun RejectedOutputCleanupRequest.selector() =
  RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt, repairTurn)

private fun RejectedOutputDiagnostic.safeLine(): String = listOf(
  "identity=${identity.safeField()}",
  "workflow=${workflowId.safeField()}",
  "phase=${phaseId.safeField()}",
  "attempt=$attempt",
  "repair_turn=$repairTurn",
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
