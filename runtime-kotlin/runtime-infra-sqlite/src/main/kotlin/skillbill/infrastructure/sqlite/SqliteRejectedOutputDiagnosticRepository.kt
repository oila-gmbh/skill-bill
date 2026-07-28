package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

class SqliteRejectedOutputDiagnosticRepository(
  private val connection: Connection,
) : RejectedOutputDiagnosticRepository {
  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord {
    val existing = try {
      find(record.metadata.identity)
    } catch (error: RejectedOutputDiagnosticError) {
      throw error
    } catch (error: Exception) {
      throw RejectedOutputDiagnosticError.Persistence("insert-read-existing", error)
    }
    if (existing != null) {
      if (!existing.sameImmutableEvidence(record)) {
        throw RejectedOutputDiagnosticError.Conflict(record.metadata.identity)
      }
      return existing
    }
    try {
      connection.prepareStatement(
        """
        INSERT INTO rejected_output_diagnostics (
          identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
          recorded_at, byte_size, sha256, lifecycle, payload
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        val metadata = record.metadata
        statement.setString(1, metadata.identity)
        statement.setString(2, metadata.workflowId)
        statement.setString(3, metadata.phaseId)
        statement.setInt(4, metadata.attempt)
        statement.setString(5, metadata.rule)
        statement.setString(6, metadata.path)
        statement.setString(7, metadata.reason)
        statement.setString(8, metadata.agentId)
        statement.setString(9, metadata.model)
        statement.setString(10, metadata.recordedAt.toString())
        statement.setLong(11, metadata.byteSize)
        statement.setString(12, metadata.sha256)
        statement.setString(13, metadata.lifecycle.name.lowercase())
        statement.setBytes(14, record.payload)
        statement.executeUpdate()
      }
      return record
    } catch (error: RejectedOutputDiagnosticError) {
      throw error
    } catch (error: Exception) {
      val raced = persistence("insert-read-raced") { find(record.metadata.identity) }
      if (raced != null && raced.sameImmutableEvidence(record)) return raced
      throw RejectedOutputDiagnosticError.Persistence("insert", error)
    }
  }

  override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> {
    return persistence("select") {
      val clauses = mutableListOf("workflow_id = ?")
      if (selector.phaseId != null) clauses += "phase_id = ?"
      if (selector.attempt != null) clauses += "attempt = ?"
      connection.prepareStatement(
        "${selectColumns()} WHERE ${clauses.joinToString(" AND ")} ORDER BY phase_id, attempt",
      ).use { statement ->
        var index = 1
        statement.setString(index++, selector.workflowId)
        selector.phaseId?.let { statement.setString(index++, it) }
        selector.attempt?.let { statement.setInt(index, it) }
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(rows.toRecord().metadata) }
        }
      }
    }
  }

  override fun read(identity: String): RejectedOutputDiagnosticRecord =
    try {
      find(identity) ?: throw RejectedOutputDiagnosticError.Absent(identity)
    } catch (error: RejectedOutputDiagnosticError) {
      throw error
    } catch (error: Exception) {
      throw RejectedOutputDiagnosticError.Corrupt(identity)
    }

  override fun markExpired(before: Instant): Int = persistence("mark-expired") {
    connection.prepareStatement(
      """
      UPDATE rejected_output_diagnostics
      SET lifecycle = 'expired', payload = NULL
      WHERE lifecycle = 'stored' AND recorded_at < ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, before.toString())
      statement.executeUpdate()
    }
  }

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int = persistence("delete") {
    val clauses = mutableListOf("workflow_id = ?")
    if (selector.phaseId != null) clauses += "phase_id = ?"
    if (selector.attempt != null) clauses += "attempt = ?"
    connection.prepareStatement(
      "DELETE FROM rejected_output_diagnostics WHERE ${clauses.joinToString(" AND ")}",
    ).use { statement ->
      var index = 1
      statement.setString(index++, selector.workflowId)
      selector.phaseId?.let { statement.setString(index++, it) }
      selector.attempt?.let { statement.setInt(index, it) }
      statement.executeUpdate()
    }
  }

  private fun find(identity: String): RejectedOutputDiagnosticRecord? = connection.prepareStatement(
    "${selectColumns()} WHERE identity = ?",
  ).use { statement ->
    statement.setString(1, identity)
    statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
  }

  private fun selectColumns(): String =
    """
    SELECT identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
           recorded_at, byte_size, sha256, lifecycle, payload
    FROM rejected_output_diagnostics
    """.trimIndent()
}

private inline fun <T> persistence(operation: String, block: () -> T): T =
  try {
    block()
  } catch (error: RejectedOutputDiagnosticError) {
    throw error
  } catch (error: Exception) {
    throw RejectedOutputDiagnosticError.Persistence(operation, error)
  }

private fun ResultSet.toRecord(): RejectedOutputDiagnosticRecord {
  val identity = runCatching { getString("identity") }.getOrNull() ?: "<invalid>"
  return try {
    RejectedOutputDiagnosticRecord(
      metadata = RejectedOutputDiagnostic(
        identity = identity,
        workflowId = getString("workflow_id"),
        phaseId = getString("phase_id"),
        attempt = getInt("attempt"),
        rule = getString("rule"),
        path = getString("rejection_path"),
        reason = getString("reason"),
        agentId = getString("agent_id"),
        model = getString("model"),
        recordedAt = Instant.parse(getString("recorded_at")),
        byteSize = getLong("byte_size"),
        sha256 = getString("sha256"),
        lifecycle = RejectedOutputLifecycle.valueOf(getString("lifecycle").uppercase()),
      ),
      payload = getBytes("payload"),
    )
  } catch (error: Exception) {
    throw RejectedOutputDiagnosticError.Corrupt(identity)
  }
}

private fun RejectedOutputDiagnosticRecord.sameImmutableEvidence(other: RejectedOutputDiagnosticRecord): Boolean =
  metadata.copy(recordedAt = other.metadata.recordedAt) == other.metadata &&
    ((payload == null && other.payload == null) || (payload != null && other.payload != null && payload.contentEquals(other.payload)))
