package skillbill.application.featuretask

import skillbill.error.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.RejectedOutputLifecycle
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
    assertContentEquals(bytes, service.readBytes(first.identity))
    assertEquals(1, repository.records.size)
  }

  @Test
  fun `different attempts have distinct stable identities`() {
    val service = service(MemoryRepository())
    assertNotEquals(
      service.record(request(byteArrayOf(1))).identity,
      service.record(request(byteArrayOf(1), 2)).identity,
    )
  }

  @Test
  fun `payload size does not discard exact bytes`() {
    val service = service(MemoryRepository())
    val bytes = ByteArray(1_387_312) { (it % 251).toByte() }
    val metadata = service.record(request(bytes))

    assertEquals(RejectedOutputLifecycle.STORED, metadata.lifecycle)
    assertContentEquals(bytes, service.readBytes(metadata.identity))
  }

  @Test
  fun `an incomplete upstream capture blocks instead of storing a tombstone`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val error = assertFailsWith<RejectedOutputDiagnosticError.Persistence> {
      service.record(
        request(byteArrayOf(1)).copy(
          observedByteSize = 1_048_577,
          observedSha256 = "a".repeat(64),
          truncated = true,
        ),
      )
    }
    assertTrue(error.message.orEmpty().contains("record-incomplete-capture"))
    assertTrue(repository.records.isEmpty())
  }

  @Test
  fun `a complete file-backed capture persists exact bytes`() {
    val bytes = ByteArray(1_387_312) { (it % 251).toByte() }
    val path = Files.createTempFile("rejected-output-source-", ".bin")
    Files.write(path, bytes)
    val service = service(MemoryRepository())

    val metadata = service.record(
      request(bytes.copyOf(32)).copy(
        observedByteSize = bytes.size.toLong(),
        observedSha256 = RejectedOutputDiagnosticService.sha256(bytes),
        truncated = true,
        rawResponsePath = path.toString(),
      ),
    )

    assertContentEquals(bytes, service.readBytes(metadata.identity))
    Files.deleteIfExists(path)
  }

  @Test
  fun `record executes configured retention before inserting new evidence`() {
    val repository = MemoryRepository()
    service(repository).record(request(byteArrayOf(1)))

    assertEquals(1, repository.expiryCalls)
  }

  @Test
  fun `re-recording an expired attempt is an idempotent tombstone lookup`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val first = service.record(request(byteArrayOf(1, 2)))
    repository.records[first.identity] = repository.records.getValue(first.identity).copy(
      metadata = first.copy(lifecycle = RejectedOutputLifecycle.EXPIRED),
      payload = null,
    )

    val replay = service.record(request(byteArrayOf(1, 2)))

    assertEquals(RejectedOutputLifecycle.EXPIRED, replay.lifecycle)
    assertEquals(1, repository.records.size)
  }

  @Test
  fun `producer evidence applies permissions and retention before insertion`() {
    val repository = MemoryRepository()
    var permissionCalls = 0
    val service = RejectedOutputDiagnosticService(
      repository,
      permissions = { permissionCalls += 1 },
      metadataValidator = RejectedOutputDiagnosticMetadataValidator { },
      clock = Clock.fixed(now, ZoneOffset.UTC),
    )
    service.retainProducerOutput(
      skillbill.ports.persistence.ProducerOutputEvidence(
        "workflow-1", "plan", 1, "codex", "gpt", now, 1, "a".repeat(64), byteArrayOf(1),
      ),
    )

    assertEquals(1, permissionCalls)
    assertEquals(1, repository.expiryCalls)
    assertEquals(1, repository.producerOutputRetentions)
  }

  @Test
  fun `corrupt payload fails without returning content`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val metadata = service.record(request(byteArrayOf(1, 2)))
    repository.records[metadata.identity] = repository.records.getValue(
      metadata.identity,
    ).copy(payload = byteArrayOf(9))

    assertFailsWith<RejectedOutputDiagnosticError.Corrupt> { service.readBytes(metadata.identity) }
  }

  @Test
  fun `record and read seams reject metadata outside the canonical schema with typed error`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val metadata = service.record(request(byteArrayOf(1, 2)))
    repository.records[metadata.identity] = repository.records.getValue(metadata.identity).copy(
      metadata = metadata.copy(sha256 = "not-a-digest"),
    )

    assertFailsWith<InvalidRejectedOutputDiagnosticSchemaError> { service.readBytes(metadata.identity) }
  }

  @Test
  fun `invalid request and configuration failures are typed`() {
    assertFailsWith<RejectedOutputDiagnosticError.InvalidConfiguration> {
      RejectedOutputDiagnosticConfig(retention = Duration.ofDays(-1))
    }
    assertFailsWith<RejectedOutputDiagnosticError.InvalidRequest> {
      service(MemoryRepository()).record(request(byteArrayOf(1)).copy(workflowId = ""))
    }
    assertFailsWith<RejectedOutputDiagnosticError.InvalidRequest> {
      service(MemoryRepository()).inspect(RejectedOutputDiagnosticSelector(""))
    }
  }

  private fun service(repository: MemoryRepository) = RejectedOutputDiagnosticService(
    repository,
    permissions = { },
    metadataValidator = RejectedOutputDiagnosticMetadataValidator { metadata ->
      if (!Regex("[0-9a-f]{64}").matches(metadata.sha256)) {
        throw InvalidRejectedOutputDiagnosticSchemaError("sha256 is invalid")
      }
    },
    config = RejectedOutputDiagnosticConfig(),
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

private fun RejectedOutputDiagnosticService.readBytes(identity: String): ByteArray =
  ByteArrayOutputStream().also { streamRaw(identity, it) }.toByteArray()

private class MemoryRepository : RejectedOutputDiagnosticRepository {
  val records = linkedMapOf<String, RejectedOutputDiagnosticRecord>()
  var expiryCalls: Int = 0
  var producerOutputRetentions: Int = 0
  override val filePayloads = object : skillbill.ports.persistence.RejectedOutputFilePayloadRepository {
    override fun insert(record: RejectedOutputDiagnosticRecord, payloadPath: Path): RejectedOutputDiagnosticRecord =
      insert(record.copy(payload = Files.readAllBytes(payloadPath)))
  }
  override val producerOutputs = object : skillbill.ports.persistence.ProducerOutputEvidenceRepository {
    override fun retain(evidence: skillbill.ports.persistence.ProducerOutputEvidence) {
      producerOutputRetentions += 1
    }
  }

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

  override fun markExpired(before: Instant): Int {
    expiryCalls += 1
    return 0
  }

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int = 0
}
