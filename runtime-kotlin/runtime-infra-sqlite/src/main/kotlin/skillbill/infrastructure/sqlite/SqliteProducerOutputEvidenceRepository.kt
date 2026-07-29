package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.ProducerOutputEvidenceRepository
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant

internal class SqliteProducerOutputEvidenceRepository(
  private val connection: Connection,
) : ProducerOutputEvidenceRepository {
  override fun retain(evidence: ProducerOutputEvidence) {
    retainRecord(evidence) { statement, index -> statement.setBytes(index, evidence.payload) }
    val retained = read(evidence.workflowId, evidence.phaseId, evidence.attempt)
      ?: throw RejectedOutputDiagnosticError.Persistence("retain-producer-output-readback")
    if (retained.sha256 != evidence.sha256 || retained.byteSize != evidence.byteSize ||
      !payloadsEqual(retained.payload, evidence.payload)
    ) {
      conflict(evidence)
    }
  }

  override fun retain(evidence: ProducerOutputEvidence, payloadPath: Path) {
    Files.newInputStream(payloadPath).use { input ->
      retainRecord(evidence) { statement, index ->
        statement.setBinaryStream(index, input, evidence.byteSize)
      }
    }
    val retained = findMetadata(evidence.workflowId, evidence.phaseId, evidence.attempt)
      ?: throw RejectedOutputDiagnosticError.Persistence("retain-producer-output-readback")
    if (retained.first != evidence.byteSize || retained.second != evidence.sha256) conflict(evidence)
  }

  private fun retainRecord(evidence: ProducerOutputEvidence, bindPayload: (PreparedStatement, Int) -> Unit) {
    producerPersistence("retain-producer-output") {
      connection.prepareStatement(
        """
        INSERT OR IGNORE INTO producer_output_evidence
        (workflow_id, phase_id, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use {
        var index = 1
        it.setString(index++, evidence.workflowId)
        it.setString(index++, evidence.phaseId)
        it.setInt(index++, evidence.attempt)
        it.setString(index++, evidence.agentId)
        it.setString(index++, evidence.model)
        it.setString(index++, evidence.recordedAt.toString())
        it.setLong(index++, evidence.byteSize)
        it.setString(index++, evidence.sha256)
        bindPayload(it, index)
        it.executeUpdate()
      }
    }
  }

  override fun read(workflowId: String, phaseId: String, attempt: Int): ProducerOutputEvidence? =
    producerPersistence("read-producer-output") {
      connection.prepareStatement(
        "SELECT * FROM producer_output_evidence WHERE workflow_id = ? AND phase_id = ? AND attempt = ?",
      ).use {
        it.setString(1, workflowId)
        it.setString(2, phaseId)
        it.setInt(ATTEMPT_PARAMETER_INDEX, attempt)
        it.executeQuery().use { row ->
          if (!row.next()) {
            null
          } else {
            ProducerOutputEvidence(
              row.getString("workflow_id"),
              row.getString("phase_id"),
              row.getInt("attempt"),
              row.getString("agent_id"),
              row.getString("model"),
              Instant.parse(row.getString("recorded_at")),
              row.getLong("byte_size"),
              row.getString("sha256"),
              row.getBytes("payload"),
            )
          }
        }
      }
    }

  override fun latestAttempt(workflowId: String, phaseId: String): Int =
    producerPersistence("latest-producer-output-attempt") {
      connection.prepareStatement(
        "SELECT COALESCE(MAX(attempt), 0) FROM producer_output_evidence WHERE workflow_id = ? AND phase_id = ?",
      ).use {
        it.setString(1, workflowId)
        it.setString(2, phaseId)
        it.executeQuery().use { row -> if (row.next()) row.getInt(1) else 0 }
      }
    }

  override fun deleteBefore(before: Instant): Int = producerPersistence("delete-producer-outputs") {
    connection.prepareStatement("DELETE FROM producer_output_evidence WHERE recorded_at < ?").use {
      it.setString(1, before.toString())
      it.executeUpdate()
    }
  }

  private fun findMetadata(workflowId: String, phaseId: String, attempt: Int): Pair<Long, String>? =
    connection.prepareStatement(
      "SELECT byte_size, sha256 FROM producer_output_evidence WHERE workflow_id = ? AND phase_id = ? AND attempt = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, phaseId)
      statement.setInt(ATTEMPT_PARAMETER_INDEX, attempt)
      statement.executeQuery().use { rows ->
        if (rows.next()) rows.getLong("byte_size") to rows.getString("sha256") else null
      }
    }
}

private inline fun <T> producerPersistence(operation: String, block: () -> T): T = try {
  block()
} catch (error: RejectedOutputDiagnosticError) {
  throw error
} catch (error: SQLException) {
  throw RejectedOutputDiagnosticError.Persistence(operation, error)
}

private fun payloadsEqual(left: ByteArray?, right: ByteArray?): Boolean =
  (left == null && right == null) || (left != null && right != null && left.contentEquals(right))

private fun conflict(evidence: ProducerOutputEvidence): Nothing =
  throw RejectedOutputDiagnosticError.Conflict("${evidence.workflowId}:${evidence.phaseId}:${evidence.attempt}")

private const val ATTEMPT_PARAMETER_INDEX = 3
