package skillbill.cli.work

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.model.WorkListItem
import skillbill.application.model.WorkListResult
import skillbill.application.work.WorkListService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.model.CliFormat
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
  "work" to work.map(WorkListItem::toPayload),
)

private fun WorkListItem.toPayload(): Map<String, Any?> = linkedMapOf(
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
      item.issueKey?.toTerminalSafeIssueKey() ?: "-",
      item.workflowKind.wireValue,
      item.workflowId.toTerminalSafeText(),
      formatter.format(item.startedAt),
      item.currentState,
      formatter.format(item.stateEnteredAt) + if (item.stateEnteredAtEstimated) "~" else "",
    )
  }
  val widths = headers.indices.map { index ->
    maxOf(terminalDisplayWidth(headers[index]), rows.maxOfOrNull { terminalDisplayWidth(it[index]) } ?: 0)
  }
  fun render(values: List<String>): String = values.indices.joinToString("  ") { index ->
    values[index].padTerminalEnd(widths[index])
  }
  return buildString {
    appendLine(render(headers))
    rows.forEach { appendLine(render(it)) }
    if (work.any { it.stateEnteredAtEstimated }) appendLine("~ estimated")
  }
}

internal fun String.toTerminalSafeIssueKey(): String {
  return toTerminalSafeText().truncateTerminalDisplayWidth(MAX_TABLE_ISSUE_KEY_DISPLAY_WIDTH)
}

internal fun String.toTerminalSafeText(): String {
  val sanitized = buildString(length) {
    this@toTerminalSafeText.codePoints().forEach { codePoint ->
      appendCodePoint(if (Character.isISOControl(codePoint)) REPLACEMENT_CODE_POINT else codePoint)
    }
  }
  return sanitized
}

internal fun terminalDisplayWidth(value: String): Int = value.codePoints().toArray().sumOf(::terminalDisplayWidth)

internal fun String.truncateTerminalDisplayWidth(maxWidth: Int): String {
  require(maxWidth > 0) { "maxWidth must be positive." }
  if (terminalDisplayWidth(this) <= maxWidth) {
    return this
  }
  val markerWidth = terminalDisplayWidth(TRUNCATION_MARKER)
  val contentWidth = (maxWidth - markerWidth).coerceAtLeast(0)
  val truncated = StringBuilder()
  var usedWidth = 0
  for (codePoint in codePoints().toArray()) {
    val codePointWidth = terminalDisplayWidth(codePoint)
    if (codePointWidth > contentWidth - usedWidth) {
      break
    }
    truncated.appendCodePoint(codePoint)
    usedWidth += codePointWidth
  }
  return truncated.append(TRUNCATION_MARKER).toString()
}

internal fun String.padTerminalEnd(width: Int): String =
  this + " ".repeat((width - terminalDisplayWidth(this)).coerceAtLeast(0))

private fun terminalDisplayWidth(codePoint: Int): Int = when {
  Character.isISOControl(codePoint) -> 1
  Character.getType(codePoint) in ZERO_WIDTH_CHARACTER_TYPES -> 0
  WIDE_CODE_POINT_RANGES.any { codePoint in it } -> 2
  else -> 1
}

private val ZERO_WIDTH_CHARACTER_TYPES: Set<Int> = setOf(
  Character.NON_SPACING_MARK.toInt(),
  Character.COMBINING_SPACING_MARK.toInt(),
  Character.ENCLOSING_MARK.toInt(),
  Character.FORMAT.toInt(),
)

private val WIDE_CODE_POINT_RANGES: List<IntRange> = listOf(
  "1100..115F",
  "2329..232A",
  "2E80..A4CF",
  "AC00..D7A3",
  "F900..FAFF",
  "FE10..FE19",
  "FE30..FE6F",
  "FF00..FF60",
  "FFE0..FFE6",
  "1B000..1B12F",
  "1B170..1B2FF",
  "1F200..1F251",
  "1F300..1FAFF",
  "1FC00..1FFFD",
  "20000..3FFFD",
).map(::parseHexadecimalRange)

private fun parseHexadecimalRange(value: String): IntRange {
  val (start, end) = value.split("..")
  return start.toInt(HEXADECIMAL_RADIX)..end.toInt(HEXADECIMAL_RADIX)
}

private const val HEXADECIMAL_RADIX = 16
private const val REPLACEMENT_CODE_POINT = 0xFFFD
private const val MAX_TABLE_ISSUE_KEY_DISPLAY_WIDTH = 128
private const val TRUNCATION_MARKER = "…"
