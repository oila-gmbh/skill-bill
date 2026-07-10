package skillbill.db.telemetry

import java.sql.ResultSet

fun ResultSet.toRowMap(): Map<String, Any?> {
  val metadata = metaData
  return buildMap {
    for (index in 1..metadata.columnCount) {
      put(metadata.getColumnName(index), getObject(index))
    }
  }
}
