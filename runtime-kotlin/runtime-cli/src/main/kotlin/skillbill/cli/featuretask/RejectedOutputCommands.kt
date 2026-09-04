package skillbill.cli.featuretask

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.UnitOfWork
import java.io.OutputStream
import java.time.Clock

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
  private val inputs: CliRunInputs,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val clock: Clock,
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
    database.selfManagedWrite(inputs.dbPathOverride) { unitOfWork ->
      RejectedOutputInspectCommand(unitOfWork.diagnosticService(metadataValidator, clock)).execute(
        RejectedOutputInspectRequest(workflowId, phaseId, attempt, rawOutput, repairTurn),
        System.out,
      )
    }
  }
}

@Inject
class RejectedOutputCleanupCliCommand(
  private val database: DatabaseSessionFactory,
  private val inputs: CliRunInputs,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val clock: Clock,
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
    val deleted = database.transaction(inputs.dbPathOverride) { unitOfWork ->
      RejectedOutputCleanupCommand(unitOfWork.diagnosticService(metadataValidator, clock)).execute(
        RejectedOutputCleanupRequest(workflowId, phaseId, attempt, repairTurn),
      )
    }
    echo("deleted=$deleted")
  }
}

private fun UnitOfWork.diagnosticService(
  metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  clock: Clock,
): RejectedOutputDiagnosticService = RejectedOutputDiagnosticService(
  rejectedOutputDiagnostics ?: throw RejectedOutputDiagnosticError.Persistence("repository-unavailable"),
  rejectedOutputDiagnosticPermissions ?: throw RejectedOutputDiagnosticError.Permission("permissions-unavailable"),
  metadataValidator,
  clock = clock,
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
