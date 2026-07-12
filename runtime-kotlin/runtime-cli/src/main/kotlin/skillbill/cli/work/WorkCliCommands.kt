package skillbill.cli.work

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.work.WorkListResult
import skillbill.application.work.WorkListService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.model.CliFormat
import skillbill.ports.persistence.model.WorkItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Inject
class WorkTopLevelCommands(
  list: WorkListCommand,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand("work", "Inspect all persisted feature work.") {}
      .subcommands(list)
}

@Inject
class WorkListCommand(
  private val service: WorkListService,
  private val state: CliRunState,
) : DocumentedCliCommand("list", "List persisted feature-task, feature-verify, and feature-goal work.") {
  private val format by option("--format", help = "Output format.").default("table")
  private val limit by option("--limit", help = "Maximum number of rows.").int().validate {
    require(it > 0) { "--limit must be a positive integer." }
  }

  override fun run() {
    require(format == "table" || format == "json") { "--format must be one of: table, json." }
    val result = service.list(limit = limit, dbOverride = state.dbOverride)
    val payload = result.toPayload()
    if (format == "json") {
      state.complete(payload, CliFormat.JSON)
    } else {
      state.completeText(result.toTable(), payload)
    }
  }
}

private fun WorkListResult.toPayload(): Map<String, Any?> = mapOf(
  "work" to work.map(WorkItem::toPayload),
)

private fun WorkItem.toPayload(): Map<String, Any?> = linkedMapOf(
  "issue_key" to issueKey,
  "workflow_kind" to workflowKind.wireValue,
  "workflow_id" to workflowId,
  "started_at" to startedAt.toString(),
  "current_state" to currentState,
  "state_entered_at" to stateEnteredAt.toString(),
  "state_entered_at_estimated" to stateEnteredAtEstimated,
)

private fun WorkListResult.toTable(): String {
  val headers = listOf("ISSUE", "KIND", "WORKFLOW", "STARTED", "STATE", "STATE SINCE")
  val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
  val rows = work.map { item ->
    listOf(
      item.issueKey ?: "-",
      item.workflowKind.wireValue,
      item.workflowId,
      formatter.format(item.startedAt),
      item.currentState,
      formatter.format(item.stateEnteredAt) + if (item.stateEnteredAtEstimated) "~" else "",
    )
  }
  val widths = headers.indices.map { index -> maxOf(headers[index].length, rows.maxOfOrNull { it[index].length } ?: 0) }
  fun render(values: List<String>): String = values.indices.joinToString("  ") { index -> values[index].padEnd(widths[index]) }
  return buildString {
    appendLine(render(headers))
    rows.forEach { appendLine(render(it)) }
    if (work.any { it.stateEnteredAtEstimated }) appendLine("~ estimated")
  }
}
