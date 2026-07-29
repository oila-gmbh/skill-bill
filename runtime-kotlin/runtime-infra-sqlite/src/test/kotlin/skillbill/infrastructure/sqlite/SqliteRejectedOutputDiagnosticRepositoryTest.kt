package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteRejectedOutputDiagnosticRepositoryTest {
  @Test
  fun `round trip preserves binary response and metadata`() {
    val directory = Files.createTempDirectory("rejected-output-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val payload = byteArrayOf(0, -1, 13, 10, 0, 42)
      val record = record(payload)

      repository.insert(record)

      assertContentEquals(payload, repository.read(record.metadata.identity).payload)
      assertEquals(record.metadata, repository.read(record.metadata.identity).metadata)
      assertEquals(listOf(record.metadata), repository.select(RejectedOutputDiagnosticSelector("workflow-1")))
    }
  }

  @Test
  fun `conflicting duplicate cannot replace immutable evidence`() {
    val directory = Files.createTempDirectory("rejected-output-conflict-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val record = record(byteArrayOf(1))
      repository.insert(record)

      assertFailsWith<RejectedOutputDiagnosticError.Conflict> {
        repository.insert(record.copy(payload = byteArrayOf(2)))
      }
      assertContentEquals(byteArrayOf(1), repository.read(record.metadata.identity).payload)
    }
  }

  @Test
  fun `streams a byte range without reading the record payload`() {
    val directory = Files.createTempDirectory("rejected-output-stream-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val record = record(ByteArray(1_387_312) { (it % 251).toByte() })
      repository.insert(record)
      val output = ByteArrayOutputStream()

      val read = repository.payloadReader.stream(record.metadata.identity, 1_000_000, 4096, output)

      assertEquals(4096, read.byteCount)
      assertContentEquals(record.payload!!.copyOfRange(1_000_000, 1_004_096), output.toByteArray())
      assertEquals(record.metadata, read.metadata)
    }
  }

  @Test
  fun `stream rejects a corrupt blob before exposing bytes`() {
    val directory = Files.createTempDirectory("rejected-output-corrupt-stream-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val record = record(ByteArray(150_000) { (it % 251).toByte() })
      repository.insert(record)
      connection.prepareStatement(
        "UPDATE rejected_output_diagnostics SET payload = ? WHERE identity = ?",
      ).use { statement ->
        statement.setBytes(1, ByteArray(150_000) { 7 })
        statement.setString(2, record.metadata.identity)
        statement.executeUpdate()
      }
      val output = ByteArrayOutputStream()

      assertFailsWith<RejectedOutputDiagnosticError.Corrupt> {
        repository.payloadReader.stream(record.metadata.identity, 0, null, output)
      }
      assertTrue(output.size() == 0)
    }
  }

  @Test
  fun `metadata decoder maps malformed values to typed corruption`() {
    val directory = Files.createTempDirectory("rejected-output-corrupt-metadata-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val record = record(byteArrayOf(1))
      repository.insert(record)
      connection.prepareStatement(
        "UPDATE rejected_output_diagnostics SET recorded_at = 'not-an-instant' WHERE identity = ?",
      ).use { statement ->
        statement.setString(1, record.metadata.identity)
        statement.executeUpdate()
      }

      assertFailsWith<RejectedOutputDiagnosticError.Corrupt> {
        repository.payloadReader.metadata(record.metadata.identity)
      }
    }
  }

  private fun record(payload: ByteArray): RejectedOutputDiagnosticRecord = RejectedOutputDiagnosticRecord(
    RejectedOutputDiagnostic(
      identity = "rod_${"a".repeat(64)}",
      workflowId = "workflow-1",
      phaseId = "plan",
      attempt = 1,
      rule = "schema",
      path = "/status",
      reason = "invalid",
      agentId = "codex",
      model = "gpt",
      recordedAt = Instant.parse("2026-07-28T10:00:00Z"),
      byteSize = payload.size.toLong(),
      sha256 = sha256(payload),
      lifecycle = RejectedOutputLifecycle.STORED,
    ),
    payload,
  )

  private fun sha256(payload: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
}
