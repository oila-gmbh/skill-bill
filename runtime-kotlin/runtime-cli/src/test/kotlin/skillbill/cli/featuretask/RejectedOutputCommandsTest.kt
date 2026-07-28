package skillbill.cli.featuretask

import skillbill.application.featuretask.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.RejectedOutputDiagnosticService
import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.RejectedOutputDiagnosticError
import skillbill.ports.persistence.RejectedOutputDiagnosticRecord
import skillbill.ports.persistence.RejectedOutputDiagnosticRepository
import skillbill.ports.persistence.RejectedOutputDiagnosticSelector
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RejectedOutputCommandsTest {
  @Test
  fun `metadata is safe by default and raw output is byte exact`() {
    val repository = CliDiagnosticRepository()
    val service = RejectedOutputDiagnosticService(repository, { }, { })
    val raw = byteArrayOf(0, -1, 10, 13, 0, 42)
    service.record(request(raw))

    val metadataOutput = ByteArrayOutputStream()
    RejectedOutputInspectCommand(service).execute(
      RejectedOutputInspectRequest("workflow-1"),
      metadataOutput,
    )
    assertTrue(metadataOutput.toString().contains("byte_size=${raw.size}"))
    assertFalse(metadataOutput.toByteArray().containsSubsequence(raw))

    val rawOutput = ByteArrayOutputStream()
    RejectedOutputInspectCommand(service).execute(
      RejectedOutputInspectRequest("workflow-1", "implement", 1, rawOutput = true),
      rawOutput,
    )
    assertContentEquals(raw, rawOutput.toByteArray())
  }

  @Test
  fun `raw output rejects ambiguous workflow selection`() {
    val repository = CliDiagnosticRepository()
    val service = RejectedOutputDiagnosticService(repository, { }, { })
    service.record(request(byteArrayOf(1), attempt = 1))
    service.record(request(byteArrayOf(2), attempt = 2))

    assertFailsWith<RejectedOutputDiagnosticError.Retrieval> {
      RejectedOutputInspectCommand(service).execute(
        RejectedOutputInspectRequest("workflow-1", rawOutput = true),
        ByteArrayOutputStream(),
      )
    }
  }

  @Test
  fun `metadata rendering encodes control characters onto one line`() {
    val repository = CliDiagnosticRepository()
    val service = RejectedOutputDiagnosticService(repository, { }, { })
    service.record(request(byteArrayOf(1)).copy(reason = "invalid\nforged=value\u0000"))
    val output = ByteArrayOutputStream()

    RejectedOutputInspectCommand(service).execute(RejectedOutputInspectRequest("workflow-1"), output)

    val rendered = output.toString()
    assertTrue(rendered.contains("""reason="invalid\nforged=value\u0000""""))
    assertTrue(rendered.lines().count { it.isNotEmpty() } == 1)
  }

  private fun request(raw: ByteArray, attempt: Int = 1) = RejectedOutputDiagnosticRequest(
    workflowId = "workflow-1",
    phaseId = "implement",
    attempt = attempt,
    rule = "schema",
    path = "$.status",
    reason = "invalid",
    agentId = "codex",
    model = "gpt",
    rawResponse = raw,
  )
}

private class CliDiagnosticRepository : RejectedOutputDiagnosticRepository {
  private val records = linkedMapOf<String, RejectedOutputDiagnosticRecord>()

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

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int {
    val identities = select(selector).map { it.identity }
    identities.forEach(records::remove)
    return identities.size
  }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
  candidate.isNotEmpty() && indices.any { start ->
    start + candidate.size <= size &&
      candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
  }
