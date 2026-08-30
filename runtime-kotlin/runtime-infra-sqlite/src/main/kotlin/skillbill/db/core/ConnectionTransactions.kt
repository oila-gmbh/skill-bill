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

internal inline fun <T> Connection.inImmediateTransaction(block: Connection.() -> T): T {
  createStatement().use { it.execute("BEGIN IMMEDIATE") }
  var committed = false
  return try {
    val result = block()
    createStatement().use { it.execute("COMMIT") }
    committed = true
    result
  } finally {
    if (!committed) {
      rollbackImmediateTransaction()
    }
  }
}

private fun Connection.rollbackImmediateTransaction() {
  runCatching { createStatement().use { it.execute("ROLLBACK") } }
}
