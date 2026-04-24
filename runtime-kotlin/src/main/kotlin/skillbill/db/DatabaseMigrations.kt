package skillbill.db

import java.sql.Connection
import java.sql.SQLException

internal object DatabaseMigrations {
  fun apply(connection: Connection) {
    DatabaseColumnMigrations.apply(connection)
    FeedbackEventMigration.apply(connection)
  }
}

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
