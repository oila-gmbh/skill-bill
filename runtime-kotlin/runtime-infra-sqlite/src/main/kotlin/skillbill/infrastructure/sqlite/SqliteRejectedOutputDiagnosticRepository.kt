package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.ProducerOutputEvidenceRepository
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputFilePayloadRepository
import skillbill.ports.persistence.RejectedOutputPayloadReader
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant

class SqliteRejectedOutputDiagnosticRepository(
  private val connection: Connection,
) : RejectedOutputDiagnosticRepository {
  override val payloadReader: RejectedOutputPayloadReader =
    SqliteRejectedOutputPayloadReader(connection)
  override val filePayloads: RejectedOutputFilePayloadRepository = object : RejectedOutputFilePayloadRepository {
    override fun insert(record: RejectedOutputDiagnosticRecord, payloadPath: Path): RejectedOutputDiagnosticRecord =
      insertFile(record, payloadPath)

    override fun release(payloadPath: Path) {
      Files.deleteIfExists(payloadPath)
    }
  }
  override val producerOutputs: ProducerOutputEvidenceRepository =
    SqliteProducerOutputEvidenceRepository(connection)

  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord =
    insertRecord(record, existingMatches = { existing -> existing.sameImmutableEvidence(record) }) {
        statement, index ->
      statement.setBytes(index, record.payload)
    }

  private fun insertFile(record: RejectedOutputDiagnosticRecord, payloadPath: Path): RejectedOutputDiagnosticRecord =
    Files.newInputStream(payloadPath.also { validateRejectedOutputPayloadFile(it, record.metadata) }).use { input ->
      insertRecord(record, existingMatches = { existing -> existing.matchesMetadataAndPayloadDigest(record) }) {
          statement, index ->
        statement.setBinaryStream(index, input, record.metadata.byteSize)
      }
    }

  private fun insertRecord(
    record: RejectedOutputDiagnosticRecord,
    existingMatches: (RejectedOutputDiagnosticRecord) -> Boolean,
    bindPayload: (PreparedStatement, Int) -> Unit,
  ): RejectedOutputDiagnosticRecord {
    val existing = persistence("insert-read-existing") {
      connection.findRejectedOutputDiagnostic(record.metadata.identity)
    }
    if (existing != null) {
      if (!existingMatches(existing)) {
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
        var index = 1
        statement.setString(index++, metadata.identity)
        statement.setString(index++, metadata.workflowId)
        statement.setString(index++, metadata.phaseId)
        statement.setInt(index++, metadata.attempt)
        statement.setString(index++, metadata.rule)
        statement.setString(index++, metadata.path)
        statement.setString(index++, metadata.reason)
        statement.setString(index++, metadata.agentId)
        statement.setString(index++, metadata.model)
        statement.setString(index++, metadata.recordedAt.toString())
        statement.setLong(index++, metadata.byteSize)
        statement.setString(index++, metadata.sha256)
        statement.setString(index++, metadata.lifecycle.name.lowercase())
        bindPayload(statement, index)
        statement.executeUpdate()
      }
      return record.copy(payload = null)
    } catch (error: SQLException) {
      val raced = persistence("insert-read-raced") {
        connection.findRejectedOutputDiagnostic(record.metadata.identity)
      }
      if (raced != null && existingMatches(raced)) return raced
      throw RejectedOutputDiagnosticError.Persistence("insert", error)
    }
  }

  override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> {
    return persistence("select") {
      val clauses = mutableListOf("workflow_id = ?")
      if (selector.phaseId != null) clauses += "phase_id = ?"
      if (selector.attempt != null) clauses += "attempt = ?"
      connection.prepareStatement(
        "$REJECTED_OUTPUT_METADATA_COLUMNS WHERE ${clauses.joinToString(" AND ")} ORDER BY phase_id, attempt",
      ).use { statement ->
        var index = 1
        statement.setString(index++, selector.workflowId)
        selector.phaseId?.let { statement.setString(index++, it) }
        selector.attempt?.let { statement.setInt(index, it) }
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(rows.toMetadata()) }
        }
      }
    }
  }

  override fun read(identity: String): RejectedOutputDiagnosticRecord = persistence("read") {
    connection.findRejectedOutputDiagnostic(identity) ?: throw RejectedOutputDiagnosticError.Absent(identity)
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
}
