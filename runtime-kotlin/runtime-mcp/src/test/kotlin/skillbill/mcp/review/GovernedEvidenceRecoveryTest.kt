package skillbill.mcp.review

import skillbill.contracts.JsonSupport
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBroker
import skillbill.launcher.review.GovernedReviewEvidenceEndpoint
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.model.REVIEW_EVIDENCE_MAX_REQUESTS
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernedEvidenceRecoveryTest {
  @Test
  fun `worker completion drains the final published receipt before coverage is finalized`() {
    val root = Files.createTempDirectory("review-delivery-shutdown")
    val binding = recoveryBinding(root, false)
    val broker = FileSystemReviewEvidenceBroker(binding)
    GovernedReviewEvidenceEndpoint.bind(
      binding.assignment.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        val entries = discoverEntries(client)
        entries.dropLast(1).forEach { client.exchange(call(read(it))) }
        val close = FutureTask { endpoint.close() }
        val response = client.exchange(call(read(entries.last()))) {
          val closer = thread { close.run() }
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
          while (closer.isAlive && closer.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.yield()
          }
          assertEquals(Thread.State.TIMED_WAITING, closer.state)
          assertTrue(!close.isDone)
          assertEquals(1, broker.accounting().remainingEvidence.size)
        }
        assertTrue(
          requireNotNull(JsonSupport.anyToStringAnyMapList(payload(response)["results"]))
            .single()["content"] in binding.projectedHunks.map { it.content },
        )
        close.get(5, TimeUnit.SECONDS)
        assertEquals(broker.accounting().requiredEvidenceUnits, broker.accounting().deliveredEvidenceUnits)
        assertTrue(broker.accounting().remainingEvidence.isEmpty())
        assertEquals(0, broker.accounting().refusedOperationCount)
      }
    }
  }

  @Test
  fun `shutdown bounds the wait for a failed worker write and records undelivered evidence`() {
    val root = Files.createTempDirectory("review-delivery-abandoned")
    val binding = recoveryBinding(root, false)
    val broker = FileSystemReviewEvidenceBroker(binding)
    GovernedReviewEvidenceEndpoint.bind(
      binding.assignment.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        val entry = discoverEntries(client).first()
        assertFailsWith<IOException> { client.failWrite(call(read(entry))) }
        val close = FutureTask { endpoint.close() }
        thread { close.run() }
        close.get(5, TimeUnit.SECONDS)
        assertEquals(0, broker.accounting().deliveredEvidenceUnits)
        assertEquals(broker.accounting().requiredEvidenceUnits, broker.accounting().remainingEvidence.size)
        assertTrue(broker.accounting().refusals.any { it.category == "unconfirmed_delivery" })
      }
    }
  }

  @Test
  fun `worker discovers shared and unique immutable units and recovers after refusal through the real endpoint`() {
    for (merged in listOf(false, true)) {
      val root = Files.createTempDirectory("review-recovery")
      val binding = recoveryBinding(root, merged)
      val broker = FileSystemReviewEvidenceBroker(binding)
      val expandedLane = binding.sources.last().assignment.lane
      val expandedPath = if (merged) "Unique.kt" else "Shared.kt"
      val expansion = broker.authorizeExpansion(
        ReviewExpansionAuthorizationRequest(expandedLane, expandedPath, "owned call"),
      )
      assertEquals(binding.assignment.digest, expansion.assignmentDigest)
      GovernedReviewEvidenceEndpoint.bind(
        binding.assignment.lane,
        BrokerBackedNativeReviewOperationProtocol(broker),
        listOf("/bin/true"),
      )
        .use { endpoint ->
          Client(endpoint).use { client ->
            val tools = client.exchange(frame("tools/list"))
            assertTrue(tools.contains("read_evidence") && tools.contains("request_expansion"))
            val entries = discoverEntries(client)
            assertEquals(0, broker.accounting().deliveredEvidenceUnits)
            val expanded = entries.single { it["expansion_id"] == expansion.expansionId }
            val owner = requireNotNull(JsonSupport.anyToStringAnyMapList(expanded["owners"])).single()
            assertEquals(expandedLane, owner["lane"])
            assertEquals(binding.sources.last().assignment.digest, owner["assignment_digest"])
            val refused = payload(client.exchange(call(mapOf("requests" to listOf(mapOf("path" to "Forbidden.kt"))))))
            assertEquals(
              true,
              requireNotNull(JsonSupport.anyToStringAnyMapList(refused["results"])).single()["refused"],
            )
            val firstEntry = entries.first { it["expansion_id"] == null }
            assertFailsWith<IOException> { client.failWrite(call(read(firstEntry))) }
            assertEquals(0, broker.accounting().deliveredEvidenceUnits)
            val delivered = payload(client.exchange(call(read(firstEntry))))
            assertTrue(
              requireNotNull(
                JsonSupport.anyToStringAnyMapList(delivered["results"]),
              ).single()["content"].toString().contains("shared"),
            )
            assertTrue(broker.accounting().remainingEvidence.isNotEmpty())
            for (entry in entries.filter { it != firstEntry }) {
              val response = payload(client.exchange(call(read(entry))))
              val content = requireNotNull(JsonSupport.anyToStringAnyMapList(response["results"])).single()["content"]
              assertNotNull(content)
              if (entry == expanded) assertEquals(Files.readString(root.resolve(expandedPath)), content)
            }
            assertEquals(broker.accounting().requiredEvidenceUnits, broker.accounting().deliveredEvidenceUnits)
            assertTrue(broker.accounting().remainingEvidence.isEmpty())
            assertTrue(broker.accounting().refusedOperationCount > 0)
            assertEquals(0, broker.accounting().toolCalls)
            assertTrue(broker.accounting().evidenceRequests > 0)
          }
        }
    }
  }

  @Test
  fun `malformed worker requests count once through either transport path and corrected reads recover`() {
    for (direct in listOf(false, true)) {
      val binding = recoveryBinding(Files.createTempDirectory("review-malformed-recovery"), false)
      val broker = FileSystemReviewEvidenceBroker(binding)
      GovernedReviewEvidenceEndpoint.bind(
        binding.assignment.lane,
        BrokerBackedNativeReviewOperationProtocol(broker),
        listOf("/bin/true"),
      ).use { endpoint ->
        Client(endpoint).use { client ->
          client.exchange(frame("initialize"))
          client.exchange(frame("tools/list"))
          assertEquals("", client.exchange("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
          assertEquals(0, broker.accounting().evidenceRequests)
          malformedRequests().forEachIndexed { index, request ->
            val response = if (direct) requireNotNull(client.forward(request)) else client.exchange(request)
            assertTrue(response.contains("\"error\""), response)
            assertEquals(index + 1, broker.accounting().evidenceRequests)
            assertEquals(index + 1, broker.accounting().refusedOperationCount)
            assertEquals(0, broker.accounting().deliveredEvidenceUnits)
          }
          val entries = discoverEntries(client)
          assertEquals(malformedRequests().size + entries.size, broker.accounting().evidenceRequests)
          entries.forEach { entry ->
            val response = client.exchange(call(read(entry)))
            assertTrue(
              requireNotNull(JsonSupport.anyToStringAnyMapList(payload(response)["results"]))
                .single()["content"] in binding.projectedHunks.map { it.content },
            )
            val beforeReplay = broker.accounting()
            val receipt = assertNotNull(GovernedReviewEvidenceBridge.deliveryReceipt(response))
            assertTrue(
              requireNotNull(client.forward(frame("evidence/delivered", mapOf("receipt" to receipt))))
                .contains("confirmed"),
            )
            assertEquals(beforeReplay, broker.accounting())
          }
          val accounting = broker.accounting()
          assertEquals(malformedRequests().size + entries.size * 2, accounting.evidenceRequests)
          assertEquals(malformedRequests().size, accounting.refusedOperationCount)
          assertTrue(accounting.refusals.isNotEmpty())
          assertEquals(accounting.requiredEvidenceUnits, accounting.deliveredEvidenceUnits)
          assertTrue(accounting.remainingEvidence.isEmpty())
          assertNull(accounting.terminalOutcome)
          assertEquals(0, accounting.toolCalls)
        }
      }
    }
  }

  @Test
  fun `malformed transport traffic exhausts the broker budget and bounds refusal storage`() {
    for (direct in listOf(false, true)) {
      val binding = recoveryBinding(Files.createTempDirectory("review-malformed-limit"), false)
      val broker = FileSystemReviewEvidenceBroker(binding)
      GovernedReviewEvidenceEndpoint.bind(
        binding.assignment.lane,
        BrokerBackedNativeReviewOperationProtocol(broker),
        listOf("/bin/true"),
      ).use { endpoint ->
        Client(endpoint).use { client ->
          val requests = malformedRequests()
          repeat(REVIEW_EVIDENCE_MAX_REQUESTS + 2) { index ->
            val request = requests[index % requests.size]
            val response = if (direct) requireNotNull(client.forward(request)) else client.exchange(request)
            assertTrue(response.contains("\"error\""))
          }
          val exhausted = broker.accounting()
          assertEquals(REVIEW_EVIDENCE_MAX_REQUESTS + 1, exhausted.evidenceRequests)
          assertEquals(REVIEW_EVIDENCE_MAX_REQUESTS + 2, exhausted.refusedOperationCount)
          assertEquals("evidence_requests", exhausted.terminalOutcome?.budgetKind)
          assertTrue(exhausted.refusals.isNotEmpty() && exhausted.refusals.size <= 128)
          assertTrue(client.exchange(call(mapOf("operation" to "discover"))).contains("budget is exhausted"))
          val read = payload(client.exchange(call(mapOf("requests" to listOf(mapOf("path" to "Shared.kt"))))))
          assertEquals(true, requireNotNull(JsonSupport.anyToStringAnyMapList(read["results"])).single()["refused"])
          assertEquals(0, broker.accounting().deliveredEvidenceUnits)
          assertEquals(exhausted.remainingEvidence, broker.accounting().remainingEvidence)
          assertEquals(exhausted.terminalOutcome, broker.accounting().terminalOutcome)
        }
      }
    }
  }

  private fun malformedRequests(): List<String> = listOf(
    "{",
    "[]",
    """{"jsonrpc":"2.0","id":{},"method":"tools/call","params":{"name":"read_evidence"}}""",
    frame("unknown"),
    frame("tools/call", mapOf("name" to "Read")),
    frame("tools/call"),
    call(mapOf("operation" to "discover", "page_size" to 0)),
    call(mapOf("requests" to "invalid")),
    frame("tools/call", mapOf("name" to "request_expansion", "arguments" to emptyMap<String, Any?>())),
  )
}
