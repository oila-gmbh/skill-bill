package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.RejectedOutputPayloadReader
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import skillbill.ports.persistence.model.RejectedOutputPayloadRead
import java.io.OutputStream
import java.security.MessageDigest
import java.sql.Connection

internal class SqliteRejectedOutputPayloadReader(
  private val connection: Connection,
) : RejectedOutputPayloadReader {
  override fun metadata(identity: String): RejectedOutputDiagnostic = persistence("read-payload-metadata") {
    connection.findRejectedOutputMetadata(identity) ?: throw RejectedOutputDiagnosticError.Absent(identity)
  }

  override fun stream(identity: String, offset: Long, length: Long?, output: OutputStream): RejectedOutputPayloadRead =
    persistence("stream-payload") {
      connection.readSnapshot {
        val snapshot = connection.findRejectedOutputSnapshot(identity)
          ?: throw RejectedOutputDiagnosticError.Absent(identity)
        ensureStored(snapshot.metadata)
        if (snapshot.payloadLength != snapshot.metadata.byteSize) {
          throw RejectedOutputDiagnosticError.Corrupt(identity)
        }
        connection.verifyRejectedOutputPayload(snapshot.metadata)
        val available = (snapshot.metadata.byteSize - offset).coerceAtLeast(0)
        val requested = (length ?: available).coerceAtMost(available)
        val copied = connection.copyRejectedOutputRange(identity, offset, requested, output)
        if (copied != requested) throw RejectedOutputDiagnosticError.Corrupt(identity)
        RejectedOutputPayloadRead(snapshot.metadata, copied)
      }
    }
}

private data class RejectedOutputSnapshot(
  val metadata: RejectedOutputDiagnostic,
  val payloadLength: Long,
)

private fun Connection.findRejectedOutputSnapshot(identity: String): RejectedOutputSnapshot? = prepareStatement(
  """
    SELECT identity, workflow_id, phase_id, attempt, rule, rejection_path, reason, agent_id, model,
           recorded_at, byte_size, sha256, lifecycle, length(payload) AS payload_length
    FROM rejected_output_diagnostics
    WHERE identity = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, identity)
  statement.executeQuery().use { rows ->
    if (!rows.next()) return@use null
    val payloadLength = rows.getObject("payload_length") as? Number
      ?: throw RejectedOutputDiagnosticError.Corrupt(identity)
    RejectedOutputSnapshot(rows.toMetadata(), payloadLength.toLong())
  }
}

private fun Connection.verifyRejectedOutputPayload(metadata: RejectedOutputDiagnostic) {
  val digest = MessageDigest.getInstance("SHA-256")
  var offset = 0L
  while (offset < metadata.byteSize) {
    val requested = minOf(REJECTED_OUTPUT_STREAM_CHUNK_BYTES.toLong(), metadata.byteSize - offset)
    val chunk = readRejectedOutputChunk(metadata.identity, offset, requested)
    if (chunk.size.toLong() != requested) throw RejectedOutputDiagnosticError.Corrupt(metadata.identity)
    digest.update(chunk)
    offset += chunk.size
  }
  val actual = digest.digest().joinToString("") { "%02x".format(it) }
  if (actual != metadata.sha256) throw RejectedOutputDiagnosticError.Corrupt(metadata.identity)
}

private fun Connection.copyRejectedOutputRange(
  identity: String,
  offset: Long,
  length: Long,
  output: OutputStream,
): Long {
  var copied = 0L
  while (copied < length) {
    val requested = minOf(REJECTED_OUTPUT_STREAM_CHUNK_BYTES.toLong(), length - copied)
    val chunk = readRejectedOutputChunk(identity, offset + copied, requested)
    if (chunk.isEmpty()) break
    output.write(chunk)
    copied += chunk.size
  }
  return copied
}

private fun Connection.readRejectedOutputChunk(identity: String, offset: Long, length: Long): ByteArray =
  prepareStatement(
    "SELECT substr(payload, ?, ?) AS payload_chunk FROM rejected_output_diagnostics WHERE identity = ?",
  ).use { statement ->
    statement.setLong(1, offset + SQLITE_BLOB_INDEX_ORIGIN)
    statement.setLong(2, length)
    statement.setString(IDENTITY_PARAMETER_INDEX, identity)
    statement.executeQuery().use { rows ->
      if (!rows.next()) throw RejectedOutputDiagnosticError.Absent(identity)
      rows.getBytes("payload_chunk") ?: throw RejectedOutputDiagnosticError.Corrupt(identity)
    }
  }

private inline fun <T> Connection.readSnapshot(block: () -> T): T {
  val ownsSnapshot = autoCommit
  if (ownsSnapshot) autoCommit = false
  var committed = false
  try {
    return block().also {
      if (ownsSnapshot) commit()
      committed = true
    }
  } finally {
    if (ownsSnapshot) {
      if (!committed) runCatching { rollback() }
      autoCommit = true
    }
  }
}

private fun ensureStored(metadata: RejectedOutputDiagnostic) {
  when (metadata.lifecycle) {
    RejectedOutputLifecycle.STORED -> Unit
    RejectedOutputLifecycle.EXPIRED -> throw RejectedOutputDiagnosticError.Expired(metadata.identity)
    RejectedOutputLifecycle.OVERSIZED -> throw RejectedOutputDiagnosticError.Oversized(metadata.identity)
  }
}

private const val SQLITE_BLOB_INDEX_ORIGIN = 1L
private const val REJECTED_OUTPUT_STREAM_CHUNK_BYTES = 65_536
private const val IDENTITY_PARAMETER_INDEX = 3
