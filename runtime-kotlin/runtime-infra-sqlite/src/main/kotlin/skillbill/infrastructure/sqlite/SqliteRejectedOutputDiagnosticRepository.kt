package skillbill.infrastructure.sqlite

import skillbill.error.InvalidProducerOutputEvidenceSchemaError
import skillbill.ports.persistence.ProducerOutputEvidence
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

class SqliteRejectedOutputDiagnosticRepository(
  private val connection: Connection,
) : RejectedOutputDiagnosticRepository {
  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord {
    val existing = persistence("insert-read-existing") { find(record.metadata.identity) }
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
        statement.setBytes(index, record.payload)
        statement.executeUpdate()
      }
      return record
    } catch (error: SQLException) {
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

  override fun read(identity: String): RejectedOutputDiagnosticRecord = persistence("read") {
    find(identity) ?: throw RejectedOutputDiagnosticError.Absent(identity)
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

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    persistence("retain-producer-output") {
      connection.prepareStatement(
        """
        INSERT OR IGNORE INTO producer_output_evidence
        (workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at, byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use {
        var index = 1
        it.setString(index++, evidence.workflowId)
        it.setString(index++, evidence.phaseId)
        it.setInt(index++, evidence.generation)
        it.setInt(index++, evidence.attempt)
        it.setString(index++, evidence.agentId)
        it.setString(index++, evidence.model)
        it.setString(index++, evidence.recordedAt.toString())
        it.setLong(index++, evidence.byteSize)
        it.setString(index++, evidence.sha256)
        it.setBytes(index, evidence.payload)
        it.executeUpdate()
      }
      val retained = connection.queryProducerEvidence(
        workflowId = evidence.workflowId,
        phaseId = evidence.phaseId,
        attempt = evidence.attempt,
        generation = evidence.generation,
        exactGeneration = true,
      ) ?: throw RejectedOutputDiagnosticError.Persistence("retain-producer-output-readback")
      if (retained.sha256 != evidence.sha256 || retained.byteSize != evidence.byteSize ||
        !payloadsEqual(retained.payload, evidence.payload)
      ) {
        throw RejectedOutputDiagnosticError.Conflict(evidence.evidenceKey())
      }
    }
  }

  override fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    generation: Int,
  ): ProducerOutputEvidence? = persistence("read-producer-output") {
    connection.queryProducerEvidence(
      workflowId = workflowId,
      phaseId = phaseId,
      attempt = attempt,
      generation = generation,
      exactGeneration = false,
    )
  }

  override fun deleteProducerOutputsBefore(before: Instant): Int = persistence("delete-producer-outputs") {
    connection.prepareStatement("DELETE FROM producer_output_evidence WHERE recorded_at < ?").use {
      it.setString(1, before.toString())
      it.executeUpdate()
    }
  }

  private fun find(identity: String): RejectedOutputDiagnosticRecord? = connection.prepareStatement(
    "${selectColumns()} WHERE identity = ?",
  ).use { statement ->
    statement.setString(1, identity)
    statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
  }

  private fun selectColumns(): String = """
    SELECT identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
           recorded_at, byte_size, sha256, lifecycle, payload
    FROM rejected_output_diagnostics
  """.trimIndent()
}

private inline fun <T> persistence(operation: String, block: () -> T): T = try {
  block()
} catch (error: RejectedOutputDiagnosticError) {
  throw error
} catch (error: SQLException) {
  throw RejectedOutputDiagnosticError.Persistence(operation, error)
}

private fun ResultSet.toRecord(): RejectedOutputDiagnosticRecord {
  val identity = try {
    getString("identity")
  } catch (error: SQLException) {
    corruptRecord("<unreadable>", error)
  }
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
  } catch (error: SQLException) {
    corruptRecord(identity, error)
  } catch (error: DateTimeParseException) {
    corruptRecord(identity, error)
  } catch (error: IllegalArgumentException) {
    corruptRecord(identity, error)
  }
}

private fun corruptRecord(identity: String, error: Throwable): Nothing =
  throw RejectedOutputDiagnosticError.Corrupt(identity, error)

private fun RejectedOutputDiagnosticRecord.sameImmutableEvidence(other: RejectedOutputDiagnosticRecord): Boolean =
  metadata.copy(recordedAt = other.metadata.recordedAt) == other.metadata &&
    (
      (payload == null && other.payload == null) || (
        payload != null && other.payload != null && payload.contentEquals(
          other.payload,
        )
        )
      )

private fun payloadsEqual(left: ByteArray?, right: ByteArray?): Boolean =
  (left == null && right == null) || (left != null && right != null && left.contentEquals(right))

private fun ProducerOutputEvidence.evidenceKey(): String = "$workflowId:$phaseId:$generation:$attempt"

private fun Connection.queryProducerEvidence(
  workflowId: String,
  phaseId: String,
  attempt: Int,
  generation: Int,
  exactGeneration: Boolean,
): ProducerOutputEvidence? {
  val generationClause =
    if (exactGeneration) "generation = ?" else "generation <= ? ORDER BY generation DESC LIMIT 1"
  return prepareStatement(
    """
    SELECT * FROM producer_output_evidence
    WHERE workflow_id = ? AND phase_id = ? AND attempt = ? AND $generationClause
    """.trimIndent(),
  ).use {
    var index = 1
    it.setString(index++, workflowId)
    it.setString(index++, phaseId)
    it.setInt(index++, attempt)
    it.setInt(index, generation)
    it.executeQuery().use { row -> if (row.next()) row.toProducerEvidence() else null }
  }
}

private fun ResultSet.toProducerEvidence(): ProducerOutputEvidence {
  val workflowId = getString("workflow_id")
  val phaseId = getString("phase_id")
  val attempt = getInt("attempt")
  val generation = getInt("generation")
  if (getObject("generation") == null || generation < 0) {
    throw InvalidProducerOutputEvidenceSchemaError(
      "Producer output evidence '$workflowId:$phaseId:$attempt' carries an unusable generation.",
    )
  }
  return ProducerOutputEvidence(
    workflowId = workflowId,
    phaseId = phaseId,
    attempt = attempt,
    agentId = getString("agent_id"),
    model = getString("model"),
    recordedAt = Instant.parse(getString("recorded_at")),
    byteSize = getLong("byte_size"),
    sha256 = getString("sha256"),
    payload = getBytes("payload"),
    generation = generation,
  )
}
