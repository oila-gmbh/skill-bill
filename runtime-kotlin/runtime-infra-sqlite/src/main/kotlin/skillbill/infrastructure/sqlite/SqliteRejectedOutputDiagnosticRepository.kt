package skillbill.infrastructure.sqlite

import skillbill.error.InvalidProducerOutputEvidenceSchemaError
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import skillbill.ports.diagnostics.model.evidenceKey
import java.sql.Connection
import java.sql.PreparedStatement
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
          identity, workflow_id, phase_id, attempt, repair_turn, rule, rejection_path, reason, agent_id, model,
          recorded_at, byte_size, sha256, lifecycle, payload
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        val metadata = record.metadata
        var index = 1
        statement.setString(index++, metadata.identity)
        statement.setString(index++, metadata.workflowId)
        statement.setString(index++, metadata.phaseId)
        statement.setInt(index++, metadata.attempt)
        statement.setInt(index++, metadata.repairTurn)
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
      connection.prepareStatement(
        "${selectColumns()} WHERE ${selector.whereClause()} ORDER BY phase_id, attempt, repair_turn",
      ).use { statement ->
        selector.bind(statement)
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
    connection.prepareStatement(
      "DELETE FROM rejected_output_diagnostics WHERE ${selector.whereClause()}",
    ).use { statement ->
      selector.bind(statement)
      statement.executeUpdate()
    }
  }

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    persistence("retain-producer-output") {
      connection.prepareStatement(
        """
        INSERT OR IGNORE INTO producer_output_evidence
        (workflow_id, phase_id, generation, attempt, repair_turn, agent_id, model, recorded_at,
         byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use {
        var index = 1
        it.setString(index++, evidence.workflowId)
        it.setString(index++, evidence.phaseId)
        it.setInt(index++, evidence.generation)
        it.setInt(index++, evidence.attempt)
        it.setInt(index++, evidence.repairTurn)
        it.setString(index++, evidence.agentId)
        it.setString(index++, evidence.model)
        it.setString(index++, evidence.recordedAt.toString())
        it.setLong(index++, evidence.byteSize)
        it.setString(index++, evidence.sha256)
        it.setBytes(index, evidence.payload)
        it.executeUpdate()
      }
      val retained = connection.queryProducerEvidence(
        ProducerEvidenceLookup(
          workflowId = evidence.workflowId,
          phaseId = evidence.phaseId,
          attempt = evidence.attempt,
          agentId = evidence.agentId,
          generation = evidence.generation,
          exactGeneration = true,
          repairTurn = evidence.repairTurn,
        ),
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
    agentId: String,
    generation: Int,
  ): ProducerOutputEvidence? = persistence("read-producer-output") {
    connection.queryProducerEvidence(
      ProducerEvidenceLookup(
        workflowId = workflowId,
        phaseId = phaseId,
        attempt = attempt,
        agentId = agentId,
        generation = generation,
        exactGeneration = false,
        // Null means "whichever repair turn is newest": a consumer resolving a producer's evidence
        // knows the attempt it wants, never how many repair turns that attempt ran.
        repairTurn = null,
      ),
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
    SELECT identity, workflow_id, phase_id, attempt, repair_turn, rule, rejection_path, reason, agent_id, model,
           recorded_at, byte_size, sha256, lifecycle, payload
    FROM rejected_output_diagnostics
  """.trimIndent()
}

private fun RejectedOutputDiagnosticSelector.whereClause(): String = buildList {
  add("workflow_id = ?")
  if (phaseId != null) add("phase_id = ?")
  if (attempt != null) add("attempt = ?")
  if (repairTurn != null) add("repair_turn = ?")
}.joinToString(" AND ")

private fun RejectedOutputDiagnosticSelector.bind(statement: PreparedStatement) {
  var index = 1
  statement.setString(index++, workflowId)
  phaseId?.let { statement.setString(index++, it) }
  attempt?.let { statement.setInt(index++, it) }
  repairTurn?.let { statement.setInt(index, it) }
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
        repairTurn = getInt("repair_turn"),
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

private data class ProducerEvidenceLookup(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val agentId: String,
  val generation: Int,
  val exactGeneration: Boolean,
  /** Exact turn when set; null resolves the newest turn retained for the attempt. */
  val repairTurn: Int?,
)

private fun Connection.queryProducerEvidence(lookup: ProducerEvidenceLookup): ProducerOutputEvidence? {
  val generationPredicate = if (lookup.exactGeneration) "generation = ?" else "generation <= ?"
  val repairTurnPredicate = if (lookup.repairTurn == null) "" else " AND repair_turn = ?"
  return prepareStatement(
    """
    SELECT * FROM producer_output_evidence
    WHERE workflow_id = ? AND phase_id = ? AND attempt = ? AND agent_id = ?
      AND $generationPredicate$repairTurnPredicate
    ORDER BY generation DESC, repair_turn DESC LIMIT 1
    """.trimIndent(),
  ).use {
    var index = 1
    it.setString(index++, lookup.workflowId)
    it.setString(index++, lookup.phaseId)
    it.setInt(index++, lookup.attempt)
    it.setString(index++, lookup.agentId)
    it.setInt(index++, lookup.generation)
    lookup.repairTurn?.let { turn -> it.setInt(index, turn) }
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
    repairTurn = getInt("repair_turn"),
  )
}
