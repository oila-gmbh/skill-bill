package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

  private fun record(payload: ByteArray): RejectedOutputDiagnosticRecord =
    RejectedOutputDiagnosticRecord(
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
        sha256 = "b".repeat(64),
        lifecycle = RejectedOutputLifecycle.STORED,
      ),
      payload,
    )
}
