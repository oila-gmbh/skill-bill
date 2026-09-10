package skillbill.mcp.review

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBroker
import skillbill.launcher.review.GovernedReviewEvidenceEndpoint
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewEvidenceSource
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewExpansionRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernedEvidenceRevisionTest {
  @Test
  fun `committed selectors distinguish revisions of one path and whole file ignores later worktree changes`() {
    val root = Files.createTempDirectory("review-committed")
    val (base, firstSha, secondSha) = committedRevisions(root)
    val marker = ReviewChangedHunk("A.kt", 1, 1, 1, 1, "+marker")
    val first = assignment(
      "first",
      listOf(marker),
    ).copy(
      baseRevision = base,
      headRevision = firstSha,
      assignedHunks = emptyList(),
    )
    val second = assignment(
      "second",
      listOf(marker),
    ).copy(
      baseRevision = firstSha,
      headRevision = secondSha,
      assignedHunks = emptyList(),
    )
    val broker = FileSystemReviewEvidenceBroker(
      ReviewEvidenceBrokerBinding(
        root,
        first,
        "first-rubric",
        ReviewContextBudgetPolicy.DEFAULT,
        sources = listOf(
          ReviewEvidenceSource(first, "first-rubric"),
          ReviewEvidenceSource(second, "second-rubric"),
        ),
      ),
    )
    val expansion = broker.authorizeExpansion(ReviewExpansionAuthorizationRequest(second.lane, "A.kt", "whole file"))
    Files.writeString(root.resolve("A.kt"), "uncommitted replacement\n")
    GovernedReviewEvidenceEndpoint.bind(
      first.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        val page = payload(client.exchange(call(mapOf("operation" to "discover"))))
        val entries = requireNotNull(JsonSupport.anyToStringAnyMapList(page["entries"]))
        assertEquals(3, entries.size)
        val contents = entries.associate { entry ->
          entry["selector"].toString() to requireNotNull(
            JsonSupport.anyToStringAnyMapList(
              payload(client.exchange(call(read(entry))))["results"],
            ),
          ).single()["content"].toString()
        }
        assertTrue(contents.getValue("target:$base:$firstSha:A.kt").contains("+first"))
        assertTrue(contents.getValue("target:$firstSha:$secondSha:A.kt").contains("+second"))
        assertEquals("second\n", contents.getValue(expansion.expansionId))
        assertTrue(broker.accounting().remainingEvidence.isEmpty())
      }
    }
  }

  @Test
  fun `assigned guidance deltas are delivered but cannot reload whole guidance`() {
    for (projected in listOf(false, true)) {
      val root = Files.createTempDirectory("review-guidance-deltas")
      val paths = listOf("AGENTS.md", "specialist-contract.md")
      val (base, head) = guidanceRevisions(root, paths)
      val hunks = paths.map { ReviewChangedHunk(it, 1, 1, 1, 1, "-old guidance\n+changed guidance") }
      val owned = assignment("guidance", hunks).copy(
        baseRevision = base,
        headRevision = head,
        assignedHunks = if (projected) hunks.map { it.hunkId } else emptyList(),
      )
      val broker = FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          owned,
          "rubric",
          ReviewContextBudgetPolicy.DEFAULT,
          projectedHunks = if (projected) hunks else emptyList(),
        ),
      )
      GovernedReviewEvidenceEndpoint.bind(
        owned.lane,
        BrokerBackedNativeReviewOperationProtocol(broker),
        listOf("/bin/true"),
      ).use { endpoint ->
        Client(endpoint).use { client ->
          paths.forEach { path ->
            val response = payload(client.exchange(call(mapOf("requests" to listOf(mapOf("path" to path))))))
            assertEquals(
              true,
              requireNotNull(JsonSupport.anyToStringAnyMapList(response["results"])).single()["refused"],
            )
            assertFailsWith<IllegalArgumentException> {
              broker.authorizeExpansion(ReviewExpansionAuthorizationRequest(owned.lane, path, "reload guidance"))
            }
          }
          val entries = requireNotNull(
            JsonSupport.anyToStringAnyMapList(
              payload(client.exchange(call(mapOf("operation" to "discover"))))["entries"],
            ),
          )
          entries.forEach { entry ->
            val forged = entry + ("path" to "Other.md")
            assertTrue(client.exchange(call(read(forged))).contains("error"))
            val response = payload(client.exchange(call(read(entry))))
            assertTrue(
              requireNotNull(
                JsonSupport.anyToStringAnyMapList(response["results"]),
              ).single()["content"].toString().contains("+changed guidance"),
            )
          }
          assertTrue(broker.accounting().remainingEvidence.isEmpty())
        }
      }
    }
  }

  @Test
  fun `deleted committed file refuses direct trusted and MCP expansion without poisoning remaining coverage`() {
    val root = Files.createTempDirectory("review-deleted-expansion")
    val (_, _, base) = committedRevisions(root)
    Files.delete(root.resolve("A.kt"))
    Files.writeString(root.resolve("Empty.kt"), "")
    git(root, "add", ".")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "delete")
    val head = git(root, "rev-parse", "HEAD").trim()
    val owned = assignment(
      "deleted",
      listOf(ReviewChangedHunk("A.kt", 1, 1, 0, 0, "-second"), ReviewChangedHunk("Empty.kt", 0, 0, 1, 0, "empty")),
    ).copy(baseRevision = base, headRevision = head, assignedHunks = emptyList())
    val binding = ReviewEvidenceBrokerBinding(root, owned, "rubric", ReviewContextBudgetPolicy.DEFAULT)
    Files.writeString(root.resolve("A.kt"), "worktree replacement")
    val broker = FileSystemReviewEvidenceBroker(binding)
    val before = broker.accounting().remainingEvidence
    val reason = "whole caller"
    assertFailsWith<InvalidReviewContextSchemaError> {
      broker.authorizeExpansion(ReviewExpansionAuthorizationRequest(owned.lane, "A.kt", reason))
    }
    assertEquals(before, broker.accounting().remainingEvidence)
    val trusted = ReviewExpansionRecord("exp-deleted", owned.digest, "A.kt", reason, true, 0)
    assertFailsWith<InvalidReviewContextSchemaError> {
      FileSystemReviewEvidenceBroker(binding.copy(trustedExpansionLedger = listOf(trusted)))
    }
    GovernedReviewEvidenceEndpoint.bind(
      owned.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        fun expand(path: String) = frame(
          "tools/call",
          mapOf("name" to "request_expansion", "arguments" to mapOf("path" to path, "reachability_reason" to reason)),
        )
        assertTrue(client.exchange(expand("A.kt")).contains("absent at the selected revision"))
        assertEquals(before, broker.accounting().remainingEvidence)
        assertEquals(2, broker.accounting().refusedOperationCount)
        assertTrue(broker.accounting().expansions.isEmpty())
        assertNull(broker.accounting().terminalOutcome)
        assertTrue(discoverEntries(client).all { it["expansion_id"] == null })
        assertTrue(!client.exchange(expand("Empty.kt")).contains("error"))
        val entries = discoverEntries(client)
        assertEquals(3, entries.size)
        entries.forEach { entry ->
          val response = payload(client.exchange(call(read(entry))))
          val result = requireNotNull(JsonSupport.anyToStringAnyMapList(response["results"])).single()
          assertEquals(false, result["refused"])
          if (entry["path"] == "A.kt") assertTrue(result["content"].toString().contains("-second"))
          if (entry["expansion_id"] != null) assertEquals("", result["content"])
        }
        assertTrue(broker.accounting().remainingEvidence.isEmpty())
        assertEquals(3, broker.accounting().deliveredEvidenceUnits)
        assertEquals(2, broker.accounting().refusedOperationCount)
      }
    }
  }

  @Test
  fun `unavailable delta coordinates report accounted refusals for exact selectors and path reads`() {
    val root = Files.createTempDirectory("review-unavailable-delta")
    val owned = assignment("unavailable", listOf(ReviewChangedHunk("A.kt", 1, 1, 1, 1, "+assigned")))
      .copy(assignedHunks = emptyList())
    val broker = FileSystemReviewEvidenceBroker(
      ReviewEvidenceBrokerBinding(root, owned, "rubric", ReviewContextBudgetPolicy.DEFAULT),
    )
    GovernedReviewEvidenceEndpoint.bind(
      owned.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        val entry = discoverEntries(client).single()
        for (request in listOf(read(entry), mapOf("requests" to listOf(mapOf("path" to "A.kt"))))) {
          val response = payload(client.exchange(call(request)))
          val result = requireNotNull(JsonSupport.anyToStringAnyMapList(response["results"])).single()
          assertEquals(true, result["refused"])
          assertEquals("evidence_unavailable", result["category"])
        }
        assertEquals(2, broker.accounting().refusedOperationCount)
        assertTrue(broker.accounting().refusals.all { it.category == "evidence_unavailable" })
        assertEquals(0, broker.accounting().deliveredEvidenceUnits)
        assertEquals(1, broker.accounting().remainingEvidence.size)
        assertNull(broker.accounting().terminalOutcome)
      }
    }
  }

  private fun committedRevisions(root: Path): Triple<String, String, String> {
    git(root, "init", "--quiet")
    Files.writeString(root.resolve("A.kt"), "base\n")
    git(root, "add", "A.kt")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "base")
    val base = git(root, "rev-parse", "HEAD").trim()
    Files.writeString(root.resolve("A.kt"), "first\n")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-am", "first")
    val firstSha = git(root, "rev-parse", "HEAD").trim()
    Files.writeString(root.resolve("A.kt"), "second\n")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-am", "second")
    val secondSha = git(root, "rev-parse", "HEAD").trim()
    return Triple(base, firstSha, secondSha)
  }

  private fun guidanceRevisions(root: Path, paths: List<String>): Pair<String, String> {
    git(root, "init", "--quiet")
    paths.forEach { Files.writeString(root.resolve(it), "old guidance\n") }
    git(root, "add", ".")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "base")
    val base = git(root, "rev-parse", "HEAD").trim()
    paths.forEach { Files.writeString(root.resolve(it), "changed guidance\n") }
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-am", "change")
    val head = git(root, "rev-parse", "HEAD").trim()
    return base to head
  }

  @Test
  fun `foreign cursors and cross-lane expansions do not grant evidence`() {
    val root = Files.createTempDirectory("review-cursors")
    val hunks = listOf("A.kt", "B.kt").map { ReviewChangedHunk(it, 1, 1, 1, 1, "+new") }
    val first = assignment("first", hunks)
    val binding = ReviewEvidenceBrokerBinding(
      root,
      first,
      "rubric",
      ReviewContextBudgetPolicy.DEFAULT,
      projectedHunks = hunks,
    )
    val broker = FileSystemReviewEvidenceBroker(binding)
    val other = FileSystemReviewEvidenceBroker(binding)
    val cursor = assertNotNull(broker.discover(ReviewEvidenceDiscoveryRequest(pageSize = 1)).nextCursor)
    assertFailsWith<InvalidReviewContextSchemaError> {
      other.discover(ReviewEvidenceDiscoveryRequest(cursor))
    }
    assertFailsWith<IllegalArgumentException> {
      broker.authorizeExpansion(ReviewExpansionAuthorizationRequest("foreign", "A.kt", "forged lane"))
    }
    assertEquals(0, broker.accounting().deliveredEvidenceUnits)
  }
}
