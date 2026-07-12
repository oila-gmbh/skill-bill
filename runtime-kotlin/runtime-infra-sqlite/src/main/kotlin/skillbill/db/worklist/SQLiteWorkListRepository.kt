package skillbill.db.worklist

import skillbill.error.InvalidWorkListRowError
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class SQLiteWorkListRepository(
  private val connection: Connection,
) : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> {
    require(limit == null || limit > 0) { "Work-list limit must be positive." }
    return connection.prepareStatement(query()).use { statement ->
      statement.setInt(1, limit ?: -1)
      statement.executeQuery().use { resultSet ->
        buildList<WorkItem> {
          while (resultSet.next()) {
            add(resultSet.toWorkItem())
          }
        }
      }
    }
  }
}

private fun query(): String =
  """
  SELECT issue_key, workflow_kind, workflow_id, started_at, current_state,
         state_entered_at, state_entered_at_estimated
  FROM (
    SELECT issue_key,
           CASE mode
             WHEN 'prose' THEN 'feature-task-prose'
             WHEN 'runtime' THEN 'feature-task-runtime'
             ELSE mode
           END AS workflow_kind,
           workflow_id,
           started_at,
           workflow_status AS current_state,
           state_entered_at,
           state_entered_at_estimated
    FROM feature_task_workflows
    UNION ALL
    SELECT issue_key,
           'feature-verify' AS workflow_kind,
           workflow_id,
           started_at,
           workflow_status AS current_state,
           state_entered_at,
           state_entered_at_estimated
    FROM feature_verify_workflows
    UNION ALL
    SELECT issue_key,
           'feature-goal' AS workflow_kind,
           parent_workflow_id AS workflow_id,
           first_started_at AS started_at,
           status AS current_state,
           state_entered_at,
           state_entered_at_estimated
    FROM goal_issue_progress
  )
  ORDER BY unixepoch(started_at) DESC, ${fractionalSecondsSql("started_at")} DESC, workflow_id DESC
  LIMIT ?
  """.trimIndent()

private fun fractionalSecondsSql(columnName: String): String =
  """
  CASE
    WHEN instr($columnName, '.') = 0 THEN 0
    ELSE CAST('0.' || substr(
      substr($columnName, instr($columnName, '.') + 1),
      1,
      CASE
        WHEN instr(substr($columnName, instr($columnName, '.') + 1), 'Z') > 0 THEN
          instr(substr($columnName, instr($columnName, '.') + 1), 'Z') - 1
        WHEN instr(substr($columnName, instr($columnName, '.') + 1), '+') > 0 THEN
          instr(substr($columnName, instr($columnName, '.') + 1), '+') - 1
        WHEN instr(substr($columnName, instr($columnName, '.') + 1), '-') > 0 THEN
          instr(substr($columnName, instr($columnName, '.') + 1), '-') - 1
        ELSE length(substr($columnName, instr($columnName, '.') + 1))
      END
    ) AS REAL)
  END
  """.trimIndent()

private fun java.sql.ResultSet.toWorkItem(): WorkItem {
  val workflowId = required("workflow_id")
  val kindValue = required("workflow_kind")
  val kind = WorkItemKind.entries.firstOrNull { it.wireValue == kindValue }
    ?: invalid(workflowId, "unknown workflow kind '$kindValue'")
  val estimatedValue = getObject("state_entered_at_estimated")
    ?: invalid(workflowId, "missing state_entered_at_estimated")
  val estimated = when ((estimatedValue as? Number)?.toInt()) {
    0 -> false
    1 -> true
    else -> invalid(workflowId, "invalid state_entered_at_estimated '$estimatedValue'")
  }
  return WorkItem(
    issueKey = getString("issue_key")?.trim()?.takeIf(String::isNotEmpty),
    workflowKind = kind,
    workflowId = workflowId,
    startedAt = parseInstant(required("started_at"), workflowId, "started_at"),
    currentState = required("current_state").also { state ->
      if (state !in validWorkStates) invalid(workflowId, "unknown current state '$state'")
    },
    stateEnteredAt = parseInstant(required("state_entered_at"), workflowId, "state_entered_at"),
    stateEnteredAtEstimated = estimated,
  )
}

private val validWorkStates = setOf("pending", "running", "blocked", "completed", "failed", "abandoned")

private fun java.sql.ResultSet.required(column: String): String = getString(column)?.trim()?.takeIf(String::isNotEmpty)
  ?: invalid(getString("workflow_id").orEmpty(), "missing $column")

private fun parseInstant(value: String, workflowId: String, column: String): Instant =
  runCatching { Instant.parse(value) }
    .recoverCatching { OffsetDateTime.parse(value).toInstant() }
    .recoverCatching {
      LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
    }
    .getOrElse { error ->
      if (error is DateTimeParseException) invalid(workflowId, "invalid $column '$value'", error)
      throw error
    }

private fun invalid(workflowId: String, detail: String, cause: Throwable? = null): Nothing {
  val label = if (workflowId.isBlank()) "<unknown>" else workflowId
  throw InvalidWorkListRowError("Work-list row '$label' $detail.", cause)
}
