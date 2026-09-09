package skillbill.mcp.review

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBroker
import skillbill.launcher.review.GovernedReviewEvidenceEndpoint
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceCoordinates
import skillbill.ports.review.model.ReviewEvidenceSource
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GovernedEvidenceCheckpointTest {
  @Test
  fun `checkpoint expansions deliver index or worktree content despite canonical HEAD and reject drift`() {
    for ((source, expected) in listOf(
      "index" to "indexed\n",
      "worktree" to "worktree\n",
      "committed" to "committed\n",
    )) {
      assertCheckpointExpansion(source, expected)
    }
  }

  private fun assertCheckpointExpansion(source: String, expected: String) {
    val root = Files.createTempDirectory("review-checkpoint-expansion")
    val coordinates = prepareCheckpoint(root, source)
    val head = git(root, "rev-parse", "HEAD").trim()
    val hunk = ReviewChangedHunk("A.kt", 1, 1, 1, 1, "-committed\n+changed")
    val owned = assignment("codex:bill-custom-review-ui", listOf(hunk)).copy(baseRevision = head, headRevision = head)
    val broker = checkpointBroker(root, owned, hunk, coordinates)
    val expansion = broker.authorizeExpansion(ReviewExpansionAuthorizationRequest(owned.lane, "A.kt", "whole caller"))
    GovernedReviewEvidenceEndpoint.bind(
      owned.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        val entries =
          requireNotNull(
            JsonSupport.anyToStringAnyMapList(
              payload(client.exchange(call(mapOf("operation" to "discover"))))["entries"],
            ),
          )
        client.exchange(call(read(entries.single { it["expansion_id"] == null })))
        val expanded = entries.single { it["expansion_id"] == expansion.expansionId }
        if (coordinates is ReviewEvidenceCoordinates.Checkpoint) {
          Files.writeString(root.resolve("A.kt"), "drift\n")
          if (coordinates.kind == ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX) git(root, "add", "A.kt")
          assertFailsWith<InvalidReviewContextSchemaError> {
            broker.authorizeExpansion(ReviewExpansionAuthorizationRequest(owned.lane, "A.kt", "whole caller"))
          }
          assertEquals(2, broker.accounting().requiredEvidenceUnits)
          assertTrue(client.exchange(call(read(expanded))).contains("changed after the immutable launch checkpoint"))
          assertEquals(1, broker.accounting().remainingEvidence.size)
          Files.writeString(root.resolve("A.kt"), expected)
          if (coordinates.kind == ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX) git(root, "add", "A.kt")
        }
        val response = payload(client.exchange(call(read(expanded))))
        assertEquals(
          expected,
          requireNotNull(JsonSupport.anyToStringAnyMapList(response["results"])).single()["content"],
        )
        assertTrue(broker.accounting().remainingEvidence.isEmpty())
      }
    }
  }

  @Test
  fun `unavailable checkpoint entries stay refused through transport after regular file replacement`() {
    for (kind in listOf("symlink", "directory", "absent")) {
      val root = Files.createTempDirectory("review-unavailable-checkpoint")
      val path = root.resolve("Unavailable.kt")
      val coordinates = unavailableCheckpoint(root, kind)
      Files.deleteIfExists(path)
      Files.writeString(path, "replacement must not be delivered")
      assertTrue(coordinates != checkpoint(root, listOf("Unavailable.kt")))
      val hunk = ReviewChangedHunk("Unavailable.kt", 1, 1, 1, 1, "+assigned delta")
      val owned = assignment("unavailable", listOf(hunk))
      assertUnavailableLedgerRejected(root, owned, hunk, coordinates)
      val broker = checkpointBroker(root, owned, hunk, coordinates)
      GovernedReviewEvidenceEndpoint.bind(
        owned.lane,
        BrokerBackedNativeReviewOperationProtocol(broker),
        listOf("/bin/true"),
      ).use { endpoint ->
        Client(endpoint).use { client ->
          val response = client.exchange(
            frame(
              "tools/call",
              mapOf(
                "name" to "request_expansion",
                "arguments" to mapOf("path" to "Unavailable.kt", "reachability_reason" to "whole caller"),
              ),
            ),
          )
          assertTrue(response.contains("not a regular checkpoint file"))
          assertTrue(!response.contains("replacement must not be delivered"))
          assertEquals(1, broker.accounting().requiredEvidenceUnits)
          val entries =
            requireNotNull(
              JsonSupport.anyToStringAnyMapList(
                payload(client.exchange(call(mapOf("operation" to "discover"))))["entries"],
              ),
            )
          val delta = payload(client.exchange(call(read(entries.single { it["expansion_id"] == null }))))
          assertEquals(
            hunk.content,
            requireNotNull(JsonSupport.anyToStringAnyMapList(delta["results"])).single()["content"],
          )
          assertTrue(entries.all { it["expansion_id"] == null })
          assertEquals(1, broker.accounting().deliveredEvidenceUnits)
          assertTrue(broker.accounting().remainingEvidence.isEmpty())
          assertEquals(1, broker.accounting().refusedOperationCount)
        }
      }
    }
  }

  @Test
  fun `oversized expansion reason is refused before authorization and a corrected request recovers`() {
    val root = Files.createTempDirectory("review-expansion-bounds")
    Files.writeString(root.resolve("A.kt"), "whole caller")
    val hunk = ReviewChangedHunk("A.kt", 1, 1, 1, 1, "+caller")
    val owned = assignment("bounds", listOf(hunk))
    val broker = FileSystemReviewEvidenceBroker(
      ReviewEvidenceBrokerBinding(
        root,
        owned,
        "rubric",
        ReviewContextBudgetPolicy.DEFAULT,

        projectedHunks = listOf(hunk),
        sources = listOf(
          ReviewEvidenceSource(
            owned,
            "rubric",
            coordinates = checkpoint(
              root,
              listOf("A.kt"),
            ),
          ),
        ),
      ),
    )
    GovernedReviewEvidenceEndpoint.bind(
      owned.lane,
      BrokerBackedNativeReviewOperationProtocol(broker),
      listOf("/bin/true"),
    ).use { endpoint ->
      Client(endpoint).use { client ->
        fun expansion(reason: String) = frame(
          "tools/call",
          mapOf(
            "name" to "request_expansion",
            "arguments" to mapOf("path" to "A.kt", "reachability_reason" to reason),
          ),
        )
        assertTrue(client.exchange(expansion("x".repeat(ReviewEvidenceLimits.FIELD_CHARACTERS + 1))).contains("error"))
        assertEquals(1, broker.accounting().requiredEvidenceUnits)
        client.exchange(expansion("caller context"))
        val entries =
          requireNotNull(
            JsonSupport.anyToStringAnyMapList(
              payload(client.exchange(call(mapOf("operation" to "discover"))))["entries"],
            ),
          )
        entries.forEach { client.exchange(call(read(it))) }
        assertTrue(broker.accounting().remainingEvidence.isEmpty())
        assertTrue(broker.accounting().refusedOperationCount > 0)
      }
    }
  }

  private fun prepareCheckpoint(root: Path, source: String): ReviewEvidenceCoordinates {
    git(root, "init", "--quiet")
    Files.writeString(root.resolve("A.kt"), "committed\n")
    git(root, "add", ".")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "base")
    Files.writeString(root.resolve("A.kt"), "indexed\n")
    git(root, "add", "A.kt")
    val index = ReviewEvidenceCoordinates.Checkpoint(
      ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX,
      mapOf("A.kt" to ReviewCheckpointFileIdentity.Regular(git(root, "rev-parse", ":A.kt").trim())),
    )
    Files.writeString(root.resolve("A.kt"), "worktree\n")
    val worktree = checkpoint(root, listOf("A.kt"))
    return when (source) {
      "index" -> index
      "worktree" -> worktree
      else -> ReviewEvidenceCoordinates.Committed("HEAD")
    }
  }

  private fun unavailableCheckpoint(root: Path, kind: String): ReviewEvidenceCoordinates.Checkpoint {
    val path = root.resolve("Unavailable.kt")
    if (kind == "symlink") {
      Files.createSymbolicLink(path, root.resolve("missing-target"))
    } else if (kind == "directory") {
      Files.createDirectories(path)
    }
    val coordinates = checkpoint(root, listOf("Unavailable.kt"))
    val unavailable = if (kind == "symlink") {
      ReviewCheckpointFileIdentity.Unavailable.SYMBOLIC_LINK
    } else if (kind == "directory") {
      ReviewCheckpointFileIdentity.Unavailable.DIRECTORY
    } else {
      ReviewCheckpointFileIdentity.Absent
    }
    assertEquals(unavailable, coordinates.files["Unavailable.kt"])
    return coordinates
  }

  private fun assertUnavailableLedgerRejected(
    root: Path,
    owned: ReviewAssignment,
    hunk: ReviewChangedHunk,
    coordinates: ReviewEvidenceCoordinates,
  ) {
    assertFailsWith<InvalidReviewContextSchemaError> {
      FileSystemReviewEvidenceBroker(
        ReviewEvidenceBrokerBinding(
          root,
          owned,
          "rubric",
          ReviewContextBudgetPolicy.DEFAULT,
          trustedExpansionLedger = listOf(
            ReviewExpansionRecord("exp-unavailable", owned.digest, "Unavailable.kt", "whole caller", true, 0),
          ),
          projectedHunks = listOf(hunk),
          sources = listOf(ReviewEvidenceSource(owned, "rubric", coordinates = coordinates)),
        ),
      )
    }
  }

  private fun checkpointBroker(
    root: Path,
    owned: ReviewAssignment,
    hunk: ReviewChangedHunk,
    coordinates: ReviewEvidenceCoordinates,
  ): FileSystemReviewEvidenceBroker = FileSystemReviewEvidenceBroker(
    ReviewEvidenceBrokerBinding(
      root,
      owned,
      "rubric",
      ReviewContextBudgetPolicy.DEFAULT,
      projectedHunks = listOf(hunk),
      sources = listOf(ReviewEvidenceSource(owned, "rubric", coordinates = coordinates)),
    ),
  )
}
