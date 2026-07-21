package skillbill.application.review

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.ReviewBuildTestFactsPort
import skillbill.ports.review.ReviewGuidancePort
import skillbill.ports.review.ReviewLaneSelectionPort
import skillbill.ports.review.ReviewLearningsPort
import skillbill.ports.review.ReviewScopeResolverPort
import skillbill.ports.review.ReviewStackRoutingPort
import skillbill.ports.review.model.ReviewFactPorts
import skillbill.ports.review.model.ReviewScopeFacts
import skillbill.ports.review.model.ReviewStackRoutingFacts
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewBuildTestFact
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewDependencyAllowlist
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneDecision
import skillbill.review.context.model.ReviewLearningsReference
import skillbill.review.context.model.ReviewRevision
import skillbill.review.context.model.ReviewRuleReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewPreparationServiceTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")

  private class CountingPorts(
    val scope: ReviewScopeFacts,
    val routing: ReviewStackRoutingFacts,
    val rules: List<ReviewRuleReference>,
    val learnings: List<ReviewLearningsReference>,
    val facts: List<ReviewBuildTestFact>,
    val decisions: List<ReviewLaneDecision>,
  ) : ReviewScopeResolverPort,
    ReviewStackRoutingPort,
    ReviewGuidancePort,
    ReviewLearningsPort,
    ReviewBuildTestFactsPort,
    ReviewLaneSelectionPort {
    val calls: MutableMap<String, Int> = linkedMapOf()

    private fun <T> record(name: String, value: T): T {
      calls[name] = calls.getOrDefault(name, 0) + 1
      return value
    }

    override fun resolveScope(reviewId: String) = record("scope", scope)
    override fun resolveStackRouting(scope: ReviewScopeFacts) = record("routing", routing)
    override fun resolveMatchedRules(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = record("rules", rules)

    override fun resolveLearnings(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) =
      record("learnings", learnings)

    override fun resolveBuildTestFacts(scope: ReviewScopeFacts) = record("facts", facts)
    override fun decideLanes(scope: ReviewScopeFacts, routing: ReviewStackRoutingFacts) = record("lanes", decisions)
  }

  private class RecordingValidator : ReviewContextEnvelopeValidator {
    val labels: MutableList<String> = mutableListOf()
    override fun validate(envelope: Map<String, Any?>, sourceLabel: String) {
      labels += sourceLabel
    }
  }

  private fun ports(
    hunks: List<ReviewChangedHunk> = listOf(hunkB, hunkA),
    decisions: List<ReviewLaneDecision> = listOf(
      ReviewLaneDecision("testing", true, "test sources changed", ownedPaths = listOf("src/A.kt")),
      ReviewLaneDecision("security", true, "auth surface changed", ownedPaths = listOf("src/B.kt")),
      ReviewLaneDecision("ui", false, "no UI files changed"),
    ),
  ) = CountingPorts(
    scope = ReviewScopeFacts("acme/repo", "base", "head", "clean", hunks),
    routing = ReviewStackRoutingFacts("kotlin", "kotlin", listOf("addon-b", "addon-a"), listOf("kotlin")),
    rules = listOf(
      ReviewRuleReference(
        "rule-1",
        "AGENTS.md",
        "Prefer named strategies.",
        ReviewRuleReference.digestOf("Prefer named strategies."),
      ),
    ),
    learnings = listOf(ReviewLearningsReference("learn-1", "telemetry", "c".repeat(64))),
    facts = listOf(ReviewBuildTestFact("test", "gradle test", "passed")),
    decisions = decisions,
  )

  private fun service(counting: CountingPorts, validator: ReviewContextEnvelopeValidator = RecordingValidator()) =
    ReviewPreparationService(
      ReviewFactPorts(counting, counting, counting, counting, counting, counting),
      validator,
    )

  private fun request(allowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt"))) =
    ReviewPreparationRequest(
      reviewId = "review",
      reviewRevision = ReviewRevision("rvs-1", 2),
      criteriaReferences = mapOf("security" to listOf("AC-002")),
      dependencyAllowlist = allowlist,
    )

  @Test fun `every fact port is resolved exactly once across a multi lane prepare`() {
    val counting = ports()
    val result = service(counting).prepare(request())
    assertEquals(listOf(1, 1, 1, 1, 1, 1), counting.calls.values.toList())
    assertEquals(setOf("scope", "routing", "rules", "learnings", "facts", "lanes"), counting.calls.keys)
    assertEquals(2, result.assignments.size)
  }

  @Test fun `lane projection covers selected lanes and excludes rejected lanes`() {
    val result = service(ports()).prepare(request())
    assertEquals(listOf("security", "testing"), result.packet.selectedLanes)
    assertEquals(listOf("security", "testing"), result.assignments.map { it.lane })
    assertTrue(result.packet.laneDecisions.any { it.lane == "ui" && !it.included })
    assertEquals("no UI files changed", result.packet.laneDecisions.first { it.lane == "ui" }.reason)
  }

  @Test fun `preparation is deterministic across shuffled port ordering`() {
    val first = service(ports(hunks = listOf(hunkA, hunkB))).prepare(request())
    val second = service(ports(hunks = listOf(hunkB, hunkA))).prepare(request())
    assertEquals(first.packet.digest, second.packet.digest)
    assertEquals(first.packetEnvelope, second.packetEnvelope)
    assertEquals(first.assignmentEnvelopes, second.assignmentEnvelopes)
  }

  @Test fun `projection emits sorted values regardless of the order ports return them`() {
    val unsorted = service(
      ports(
        hunks = listOf(hunkB, hunkA),
        decisions = listOf(
          ReviewLaneDecision("testing", true, "test sources changed", ownedPaths = listOf("src/A.kt")),
          ReviewLaneDecision("security", true, "auth surface changed", ownedPaths = listOf("src/B.kt")),
          ReviewLaneDecision("ui", false, "no UI files changed"),
        ),
      ),
    ).prepare(request()).packetEnvelope.asWireMap()

    assertEquals(listOf("security", "testing"), unsorted["selected_lanes"])
    assertEquals(listOf("addon-a", "addon-b"), unsorted["add_ons"])
    assertEquals(
      listOf("security", "testing", "ui"),
      (unsorted["lane_decisions"] as List<*>).map { (it as Map<*, *>)["lane"] },
    )
    assertEquals(
      listOf("src/A.kt", "src/B.kt"),
      (unsorted["changed_hunks"] as List<*>).map { (it as Map<*, *>)["path"] },
    )
    assertEquals(
      listOf("src/A.kt", "src/B.kt"),
      (unsorted["evidence_targets"] as List<*>).map { (it as Map<*, *>)["path"] },
    )
  }

  @Test fun `every projected envelope is schema validated before launch`() {
    val validator = RecordingValidator()
    service(ports(), validator).prepare(request())
    assertEquals(
      listOf("review-packet:review", "review-assignment:review:security", "review-assignment:review:testing"),
      validator.labels,
    )
  }

  @Test fun `assignments carry the packet digest revision and dependency allowlist`() {
    val result = service(ports()).prepare(request())
    result.assignments.forEach { assignment ->
      assertEquals(result.packet.digest, assignment.packetDigest)
      assertEquals(ReviewRevision("rvs-1", 2), assignment.reviewRevision)
      assertEquals(listOf("src/Dep.kt"), assignment.dependencyAllowlist.normalized)
    }
    assertEquals(listOf("AC-002"), result.assignments.first { it.lane == "security" }.criteriaReferences)
  }

  @Test fun `each lane receives only the hunks its decision owns`() {
    val result = service(ports()).prepare(request())
    val testing = result.assignments.first { it.lane == "testing" }
    val security = result.assignments.first { it.lane == "security" }
    assertEquals(listOf("src/A.kt"), testing.assignedPaths)
    assertEquals(listOf("src/B.kt"), security.assignedPaths)
    assertEquals(listOf(hunkA.hunkId), testing.assignedHunks)
    assertEquals(listOf(hunkB.hunkId), security.assignedHunks)
    assertTrue(testing.assignedHunks.none { it in security.assignedHunks })
    assertEquals(listOf("src/A.kt"), testing.evidenceTargets.map { it.path })
  }

  @Test fun `a lane claiming a path the packet does not own is rejected`() {
    val counting = ports(
      decisions = listOf(
        ReviewLaneDecision("testing", true, "test sources changed", ownedPaths = listOf("src/Absent.kt")),
      ),
    )
    val failure = assertFailsWith<InvalidReviewContextSchemaError> { service(counting).prepare(request()) }
    assertTrue("claims paths the packet does not own" in failure.message.orEmpty())
  }

  @Test fun `no included lane is rejected before launch`() {
    val counting = ports(decisions = listOf(ReviewLaneDecision("ui", false, "no UI files changed")))
    val failure = assertFailsWith<InvalidReviewContextSchemaError> { service(counting).prepare(request()) }
    assertTrue("no included lane" in failure.message.orEmpty())
  }

  @Test fun `dependency allowlist overlapping a changed path is rejected`() {
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).prepare(request(ReviewDependencyAllowlist(listOf("src/A.kt"))))
    }
    assertTrue("overlap changed paths" in failure.message.orEmpty())
  }

  @Test fun `assignment claiming an unowned path is rejected`() {
    val prepared = service(ports()).prepare(request())
    val foreign = prepared.assignments.first().copy(assignedPaths = listOf("src/Elsewhere.kt"))
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).validateAgainstPacket(prepared.packet, listOf(foreign) + prepared.assignments.drop(1))
    }
    assertTrue("paths not owned by the packet" in failure.message.orEmpty())
  }

  @Test fun `assignment claiming an unowned hunk id is rejected`() {
    val prepared = service(ports()).prepare(request())
    val foreign = prepared.assignments.first().copy(assignedHunks = listOf("f".repeat(64)))
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).validateAgainstPacket(prepared.packet, listOf(foreign) + prepared.assignments.drop(1))
    }
    assertTrue("hunk ids not owned by the packet" in failure.message.orEmpty())
  }

  @Test fun `cross revision assignments are rejected before launch`() {
    val prepared = service(ports()).prepare(request())
    val staleDigest = prepared.assignments.first().copy(packetDigest = "a".repeat(64))
    assertTrue(
      "different review revision" in assertFailsWith<InvalidReviewContextSchemaError> {
        service(ports()).validateAgainstPacket(prepared.packet, listOf(staleDigest) + prepared.assignments.drop(1))
      }.message.orEmpty(),
    )
    val staleRevision = prepared.assignments.first().copy(reviewRevision = ReviewRevision("rvs-1", 9))
    assertTrue(
      "does not match packet revision" in assertFailsWith<InvalidReviewContextSchemaError> {
        service(ports()).validateAgainstPacket(prepared.packet, listOf(staleRevision) + prepared.assignments.drop(1))
      }.message.orEmpty(),
    )
  }

  @Test fun `duplicate lane assignments are rejected`() {
    val prepared = service(ports()).prepare(request())
    val duplicated: List<ReviewAssignment> = listOf(prepared.assignments.first(), prepared.assignments.first())
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).validateAgainstPacket(prepared.packet, duplicated)
    }
    assertTrue("duplicate lanes" in failure.message.orEmpty())
  }

  @Test fun `missing selected lane assignment is rejected`() {
    val prepared = service(ports()).prepare(request())
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).validateAgainstPacket(prepared.packet, prepared.assignments.dropLast(1))
    }
    assertTrue("cover exactly" in failure.message.orEmpty())
  }

  @Test fun `assignment must match its packet lane decision paths and hunks`() {
    val prepared = service(ports()).prepare(request())
    val first = prepared.assignments.first()
    val other = prepared.assignments.last()
    val changedDecision = first.copy(laneDecision = first.laneDecision.copy(reason = "forged"))
    assertTrue(
      "lane decision differs" in assertFailsWith<InvalidReviewContextSchemaError> {
        service(ports()).validateAgainstPacket(prepared.packet, listOf(changedDecision, other))
      }.message.orEmpty(),
    )
    val crossLaneHunk = first.copy(assignedHunks = other.assignedHunks)
    assertTrue(
      "packet-owned hunks" in assertFailsWith<InvalidReviewContextSchemaError> {
        service(ports()).validateAgainstPacket(prepared.packet, listOf(crossLaneHunk, other))
      }.message.orEmpty(),
    )
    val missingRules = first.copy(matchedRules = emptyList())
    assertTrue(
      "matched rules differ" in assertFailsWith<InvalidReviewContextSchemaError> {
        service(ports()).validateAgainstPacket(prepared.packet, listOf(missingRules, other))
      }.message.orEmpty(),
    )
  }

  @Test fun `assignment dependency entries outside the packet allowlist are rejected`() {
    val prepared = service(ports()).prepare(request())
    val escaping = prepared.assignments.first()
      .copy(dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Other.kt")))
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).validateAgainstPacket(prepared.packet, listOf(escaping) + prepared.assignments.drop(1))
    }
    assertTrue("escapes the packet allowlist" in failure.message.orEmpty())
  }

  private fun expansion(assignmentDigest: String, id: String = "exp-1", sequence: Int = 0) = ReviewExpansionRecord(
    expansionId = id,
    assignmentDigest = assignmentDigest,
    requestedPath = "src/Dep.kt",
    reachabilityReason = "Assigned hunk calls into this helper.",
    authorized = true,
    sequence = sequence,
  )

  @Test fun `an expansion referencing its own assignment is accepted`() {
    val prepared = service(ports()).prepare(request())
    val assignment = prepared.assignments.first()
    val expanded = assignment.copy(expansions = listOf(expansion(assignment.digest)))
    val packet = prepared.packet.copy(expansionLedger = listOf(expansion(assignment.digest)))
    service(ports()).validateAgainstPacket(packet, listOf(expanded) + prepared.assignments.drop(1))
  }

  @Test fun `recording an expansion leaves the assignment and packet digests unchanged`() {
    val prepared = service(ports()).prepare(request())
    val assignment = prepared.assignments.first()
    val record = expansion(assignment.digest)
    assertEquals(assignment.digest, assignment.copy(expansions = listOf(record)).digest)
    assertEquals(prepared.packet.digest, prepared.packet.copy(expansionLedger = listOf(record)).digest)
  }

  @Test fun `an expansion referencing an unrelated assignment digest is rejected`() {
    val prepared = service(ports()).prepare(request())
    val stray = expansion("f".repeat(64))
    val failure = assertFailsWith<IllegalArgumentException> {
      prepared.assignments.first().copy(expansions = listOf(stray))
    }
    assertTrue("enclosing assignment digest" in failure.message.orEmpty())
  }

  @Test fun `an expansion cannot reference another assignment in the same review`() {
    val prepared = service(ports()).prepare(request())
    val first = prepared.assignments.first()
    val second = prepared.assignments.last()
    assertFailsWith<IllegalArgumentException> {
      first.copy(expansions = listOf(expansion(second.digest)))
    }
  }

  @Test fun `a packet ledger entry referencing an unknown assignment digest is rejected`() {
    val prepared = service(ports()).prepare(request())
    val failure = assertFailsWith<InvalidReviewContextSchemaError> {
      service(ports()).validateAgainstPacket(
        prepared.packet.copy(expansionLedger = listOf(expansion("e".repeat(64)))),
        prepared.assignments,
      )
    }
    assertTrue("Packet expansion ledger records" in failure.message.orEmpty())
  }

  @Test fun `an assignment exceeding the configured expansion bound is rejected`() {
    val counting = ports()
    val bounded = ReviewPreparationService(
      ReviewFactPorts(counting, counting, counting, counting, counting, counting),
      RecordingValidator(),
      ReviewContextBudgetPolicy(maxAssignmentExpansions = 1),
    )
    val prepared = bounded.prepare(request())
    val assignment = prepared.assignments.first()
    val overBound = assignment.copy(
      expansions = listOf(
        expansion(assignment.digest, id = "exp-1", sequence = 0),
        expansion(assignment.digest, id = "exp-2", sequence = 1),
      ),
    )
    val failure = assertFailsWith<skillbill.review.context.model.ReviewContextBudgetExceededException> {
      bounded.validateAgainstPacket(prepared.packet, listOf(overBound) + prepared.assignments.drop(1))
    }
    assertEquals("assignment_expansions", failure.outcome.budgetKind)
    assertEquals("review_context_budget_exceeded", failure.outcome.type)
  }
}
