package skillbill.db.core

import java.sql.Connection
import java.sql.SQLException

internal inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
  val previousAutoCommit = autoCommit
  autoCommit = false
  return try {
    val result = block()
    commit()
    result
  } catch (error: SQLException) {
    rollback()
    throw error
  } catch (error: IllegalArgumentException) {
    rollback()
    throw error
  } finally {
    autoCommit = previousAutoCommit
  }
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> Connection.inImmediateTransaction(block: Connection.() -> T): T {
  createStatement().use { it.execute("BEGIN IMMEDIATE") }
  return try {
    val result = block()
    createStatement().use { it.execute("COMMIT") }
    result
  } catch (error: Exception) {
    rollbackImmediateTransaction()
    throw error
  }
}

private fun Connection.rollbackImmediateTransaction() {
  runCatching { createStatement().use { it.execute("ROLLBACK") } }
}
