package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

internal val REJECTED_OUTPUT_SELECT_COLUMNS = """
  SELECT identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
         recorded_at, byte_size, sha256, lifecycle, payload
  FROM rejected_output_diagnostics
""".trimIndent()

internal val REJECTED_OUTPUT_METADATA_COLUMNS = """
  SELECT identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
         recorded_at, byte_size, sha256, lifecycle
  FROM rejected_output_diagnostics
""".trimIndent()

internal fun Connection.findRejectedOutputMetadata(identity: String): RejectedOutputDiagnostic? =
  prepareStatement("$REJECTED_OUTPUT_METADATA_COLUMNS WHERE identity = ?").use { statement ->
    statement.setString(1, identity)
    statement.executeQuery().use { rows -> if (rows.next()) rows.toMetadata() else null }
  }

internal fun Connection.findRejectedOutputDiagnostic(identity: String): RejectedOutputDiagnosticRecord? =
  prepareStatement("$REJECTED_OUTPUT_SELECT_COLUMNS WHERE identity = ?").use { statement ->
    statement.setString(1, identity)
    statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
  }

internal inline fun <T> persistence(operation: String, block: () -> T): T = try {
  block()
} catch (error: RejectedOutputDiagnosticError) {
  throw error
} catch (error: SQLException) {
  throw RejectedOutputDiagnosticError.Persistence(operation, error)
}

internal fun ResultSet.toMetadata(): RejectedOutputDiagnostic {
  val identity = try {
    getString("identity")
  } catch (error: SQLException) {
    corruptRecord("<unreadable>", error)
  }
  return try {
    RejectedOutputDiagnostic(
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
    )
  } catch (error: SQLException) {
    corruptRecord(identity, error)
  } catch (error: DateTimeParseException) {
    corruptRecord(identity, error)
  } catch (error: IllegalArgumentException) {
    corruptRecord(identity, error)
  }
}

private fun ResultSet.toRecord(): RejectedOutputDiagnosticRecord = RejectedOutputDiagnosticRecord(
  metadata = toMetadata(),
  payload = try {
    getBytes("payload")
  } catch (error: SQLException) {
    corruptRecord("<unreadable>", error)
  },
)

private fun corruptRecord(identity: String, error: Throwable): Nothing =
  throw RejectedOutputDiagnosticError.Corrupt(identity, error)

internal fun RejectedOutputDiagnosticRecord.sameImmutableEvidence(other: RejectedOutputDiagnosticRecord): Boolean =
  metadata.copy(recordedAt = other.metadata.recordedAt) == other.metadata &&
    (
      (payload == null && other.payload == null) || (
        payload != null && other.payload != null && payload.contentEquals(other.payload)
        )
      )

internal fun RejectedOutputDiagnosticRecord.matchesMetadataAndPayloadDigest(
  other: RejectedOutputDiagnosticRecord,
): Boolean {
  if (metadata.copy(recordedAt = other.metadata.recordedAt) != other.metadata) return false
  val bytes = payload ?: return false
  return bytes.size.toLong() == metadata.byteSize &&
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == metadata.sha256
}
