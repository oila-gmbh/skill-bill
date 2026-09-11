package skillbill.infrastructure.sqlite.workflow

import java.sql.PreparedStatement

internal fun Set<String>.workflowSqlPlaceholders(): String = joinToString(",") { "?" }

internal fun PreparedStatement.bindWorkflowIdsForQuery(workflowIds: Set<String>, startIndex: Int = 1) {
  workflowIds.forEachIndexed { index, workflowId -> setString(startIndex + index, workflowId) }
}
