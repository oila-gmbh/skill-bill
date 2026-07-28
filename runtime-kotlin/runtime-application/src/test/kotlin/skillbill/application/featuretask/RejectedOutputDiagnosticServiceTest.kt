package skillbill.application.featuretask

import skillbill.error.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RejectedOutputDiagnosticServiceTest {
  private val now = Instant.parse("2026-07-28T10:00:00Z")

  @Test
  fun `records exact bytes and deduplicates one attempt`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val bytes = byteArrayOf(0, 13, 10, -1, 42)
    val request = request(bytes)

    val first = service.record(request)
    val second = service.record(request)

    assertEquals(first.identity, second.identity)
    assertEquals(bytes.size.toLong(), first.byteSize)
    assertContentEquals(bytes, service.readRaw(first.identity))
    assertEquals(1, repository.records.size)
  }

  @Test
  fun `different attempts have distinct stable identities`() {
    val service = service(MemoryRepository())
    assertNotEquals(service.record(request(byteArrayOf(1))).identity, service.record(request(byteArrayOf(1), 2)).identity)
  }

  @Test
  fun `ceiling stores tombstone and read reports oversized`() {
    val service = service(MemoryRepository(), maximumPayloadBytes = 1)
    val metadata = service.record(request(byteArrayOf(1, 2)))

    assertEquals(RejectedOutputLifecycle.OVERSIZED, metadata.lifecycle)
    assertFailsWith<RejectedOutputDiagnosticError.Oversized> { service.readRaw(metadata.identity) }
  }

  @Test
  fun `corrupt payload fails without returning content`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val metadata = service.record(request(byteArrayOf(1, 2)))
    repository.records[metadata.identity] = repository.records.getValue(metadata.identity).copy(payload = byteArrayOf(9))

    assertFailsWith<RejectedOutputDiagnosticError.Corrupt> { service.readRaw(metadata.identity) }
  }

  @Test
  fun `record and read seams reject metadata outside the canonical schema with typed error`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val metadata = service.record(request(byteArrayOf(1, 2)))
    repository.records[metadata.identity] = repository.records.getValue(metadata.identity).copy(
      metadata = metadata.copy(sha256 = "not-a-digest"),
    )

    assertFailsWith<InvalidRejectedOutputDiagnosticSchemaError> { service.readRaw(metadata.identity) }
  }

  @Test
  fun `invalid request and configuration failures are typed`() {
    assertFailsWith<RejectedOutputDiagnosticError.InvalidConfiguration> {
      RejectedOutputDiagnosticConfig(maximumPayloadBytes = -1)
    }
    assertFailsWith<RejectedOutputDiagnosticError.InvalidRequest> {
      service(MemoryRepository()).record(request(byteArrayOf(1)).copy(workflowId = ""))
    }
    assertFailsWith<RejectedOutputDiagnosticError.InvalidRequest> {
      service(MemoryRepository()).inspect(RejectedOutputDiagnosticSelector(""))
    }
  }

  private fun service(repository: MemoryRepository, maximumPayloadBytes: Long = 100) =
    RejectedOutputDiagnosticService(
      repository,
      permissions = { },
      config = RejectedOutputDiagnosticConfig(maximumPayloadBytes = maximumPayloadBytes),
      clock = Clock.fixed(now, ZoneOffset.UTC),
    )

  private fun request(bytes: ByteArray, attempt: Int = 1) = RejectedOutputDiagnosticRequest(
    workflowId = "workflow-1",
    phaseId = "plan",
    attempt = attempt,
    rule = "phase-output-schema",
    path = "/produced_outputs",
    reason = "required property missing",
    agentId = "codex",
    model = "gpt",
    rawResponse = bytes,
  )
}

private class MemoryRepository : RejectedOutputDiagnosticRepository {
  val records = linkedMapOf<String, RejectedOutputDiagnosticRecord>()

  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord =
    records.getOrPut(record.metadata.identity) { record }

  override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
    records.values.map { it.metadata }.filter {
      it.workflowId == selector.workflowId &&
        (selector.phaseId == null || it.phaseId == selector.phaseId) &&
        (selector.attempt == null || it.attempt == selector.attempt)
    }

  override fun read(identity: String): RejectedOutputDiagnosticRecord =
    records[identity] ?: throw RejectedOutputDiagnosticError.Absent(identity)

  override fun markExpired(before: Instant): Int = 0

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int = 0
}
