package skillbill.db

import skillbill.contracts.JsonSupport
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

fun Boolean.toSqlInt(): Int = if (this) 1 else 0

fun listJson(items: List<Any?>): String = JsonSupport.mapToJsonString(mapOf("items" to items)).itemsArrayJson()

fun rowExists(connection: Connection, tableName: String, sessionId: String): Boolean =
  connection.prepareStatement("SELECT 1 FROM $tableName WHERE session_id = ?").use { statement ->
    statement.bind(sessionId)
    statement.executeQuery().use(ResultSet::next)
  }

fun lifecycleRow(connection: Connection, tableName: String, sessionId: String): Map<String, Any?>? =
  connection.prepareStatement("SELECT * FROM $tableName WHERE session_id = ?").use { statement ->
    statement.bind(sessionId)
    statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toMap() else null }
  }

fun markLifecycleEmitted(connection: Connection, tableName: String, columnName: String, sessionId: String) {
  connection.prepareStatement(
    """
    UPDATE $tableName
    SET $columnName = CURRENT_TIMESTAMP
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(sessionId)
    statement.executeUpdate()
  }
}

fun PreparedStatement.bind(vararg values: Any?) {
  values.toList().forEachIndexed { index, value -> setObject(index + 1, value) }
}

fun PreparedStatement.bind(values: List<Any?>) {
  values.forEachIndexed { index, value -> setObject(index + 1, value) }
}

private fun String.itemsArrayJson(): String = JsonSupport.parseObjectOrNull(this)
  ?.get("items")
  ?.let { JsonSupport.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }
  ?: "[]"

private fun ResultSet.toMap(): Map<String, Any?> {
  val metadata = metaData
  return buildMap {
    for (index in 1..metadata.columnCount) {
      put(metadata.getColumnName(index), getObject(index))
    }
  }
}
