package skillbill.desktop.core.database

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types

internal class JdbcSqliteDriver : SQLiteDriver {
  override val hasConnectionPool: Boolean = false

  override fun open(fileName: String): SQLiteConnection {
    val connection = DriverManager.getConnection("jdbc:sqlite:$fileName")
    return JdbcSqliteConnection(connection)
  }

  private companion object {
    init {
      Class.forName("org.sqlite.JDBC")
    }
  }
}

private class JdbcSqliteConnection(private val connection: Connection) : SQLiteConnection {
  override fun inTransaction(): Boolean = !connection.autoCommit

  override fun prepare(sql: String): SQLiteStatement = JdbcSqliteStatement(
    preparedStatement = connection.prepareStatement(sql),
  )

  override fun close() {
    connection.close()
  }
}

private class JdbcSqliteStatement(private val preparedStatement: PreparedStatement) : SQLiteStatement {
  private var resultSet: ResultSet? = null
  private var executed = false

  override fun bindBlob(index: Int, value: ByteArray) {
    preparedStatement.setBytes(index, value)
  }

  override fun bindDouble(index: Int, value: Double) {
    preparedStatement.setDouble(index, value)
  }

  override fun bindLong(index: Int, value: Long) {
    preparedStatement.setLong(index, value)
  }

  override fun bindText(index: Int, value: String) {
    preparedStatement.setString(index, value)
  }

  override fun bindNull(index: Int) {
    preparedStatement.setObject(index, null)
  }

  override fun getBlob(index: Int): ByteArray = requireResultSet().getBytes(index + 1)

  override fun getDouble(index: Int): Double = requireResultSet().getDouble(index + 1)

  override fun getLong(index: Int): Long = requireResultSet().getLong(index + 1)

  override fun getText(index: Int): String = requireResultSet().getString(index + 1)

  override fun isNull(index: Int): Boolean = requireResultSet().getObject(index + 1) == null

  override fun getColumnCount(): Int = currentMetaData().columnCount

  override fun getColumnName(index: Int): String = currentMetaData().getColumnName(index + 1)

  override fun getColumnType(index: Int): Int = when (currentMetaData().getColumnType(index + 1)) {
    Types.BIGINT, Types.BIT, Types.BOOLEAN, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
      SQLITE_DATA_INTEGER
    }

    Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.NUMERIC, Types.REAL -> SQLITE_DATA_FLOAT
    Types.BINARY, Types.LONGVARBINARY, Types.VARBINARY -> SQLITE_DATA_BLOB
    Types.NULL -> SQLITE_DATA_NULL
    else -> SQLITE_DATA_TEXT
  }

  override fun step(): Boolean {
    if (!executed) {
      executed = true
      val hasResultSet = preparedStatement.execute()
      if (!hasResultSet) {
        return false
      }
      resultSet = preparedStatement.resultSet
    }

    return requireResultSet().next()
  }

  override fun reset() {
    resultSet?.close()
    resultSet = null
    executed = false
  }

  override fun clearBindings() {
    preparedStatement.clearParameters()
  }

  override fun close() {
    resultSet?.close()
    preparedStatement.close()
  }

  private fun requireResultSet(): ResultSet = requireNotNull(resultSet) {
    "No current result set. Call step() before reading columns."
  }

  private fun currentMetaData(): ResultSetMetaData {
    resultSet?.let { return it.metaData }
    if (!executed) {
      return executeForMetadata()
    }
    return requireNotNull(preparedStatement.metaData) {
      "No column metadata available for this statement."
    }
  }

  private fun executeForMetadata(): ResultSetMetaData {
    check(!executed) { "No column metadata available for this statement." }
    executed = true
    check(preparedStatement.execute()) { "No column metadata available for this statement." }
    resultSet = preparedStatement.resultSet
    return requireResultSet().metaData
  }
}
