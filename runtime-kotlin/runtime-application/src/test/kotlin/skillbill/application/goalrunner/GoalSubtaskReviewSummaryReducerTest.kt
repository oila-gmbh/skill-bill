package skillbill.application.goalrunner

import skillbill.application.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.StructuredGoalReviewFinding
import skillbill.application.subtaskreview.UnaddressedFindingLedgerScope
import skillbill.application.subtaskreview.structuredFindings
import skillbill.application.subtaskreview.verificationBoundaryFindingPaths
import skillbill.review.ReviewFindingFieldCodec
import skillbill.review.model.ReviewFindingCitation
import skillbill.text.Utf8Text
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalSubtaskReviewSummaryReducerTest {
  @Test
  fun `user-directed review skip has no unresolved findings`() {
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(
      mapOf(
        "verdict" to FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER.wireValue,
        "produced_outputs" to mapOf("user_directed_skip" to "No review requested"),
      ),
    )

    assertEquals(FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER, outcome.verdict)
    assertEquals(0, outcome.unresolvedFindingCount)
  }

  @Test
  fun `Major findings request changes and count as unresolved`() {
    val output = mapOf(
      "verdict" to FeatureTaskRuntimeVerdict.APPROVED.wireValue,
      "produced_outputs" to mapOf(
        "findings" to listOf(mapOf("severity" to "major", "message" to "Follow-up risk")),
      ),
    )

    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output)

    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, outcome.verdict)
    assertEquals(1, outcome.unresolvedFindingCount)
    assertEquals(1, GoalSubtaskReviewSummaryReducer.fromOutput(output).size)
  }

  @Test
  fun `Blocker findings request changes and count as unresolved`() {
    val output = mapOf(
      "verdict" to FeatureTaskRuntimeVerdict.APPROVED.wireValue,
      "produced_outputs" to mapOf(
        "findings" to listOf(mapOf("severity" to "blocker", "message" to "Data loss")),
      ),
    )

    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output)

    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, outcome.verdict)
    assertEquals(1, outcome.unresolvedFindingCount)
    assertEquals(1, GoalSubtaskReviewSummaryReducer.fromOutput(output).size)
  }

  @Test
  fun `compact summaries keep distinct finding ids that share a label`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "severity" to "major",
              "class_or_symbol" to "OrderService",
              "id" to "F-001",
              "message" to "OrderService misses validation on submit",
            ),
            mapOf(
              "severity" to "minor",
              "class_or_symbol" to "OrderService",
              "id" to "F-002",
              "message" to "OrderService comment is stale",
            ),
          ),
        ),
      ),
    )

    assertEquals(setOf("F-001", "F-002"), summary.map { it.findingId }.toSet())
  }

  @Test
  fun `compact summaries remove paths lines hunks and duplicate findings`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "severity" to "major",
              "message" to "src/main/kotlin/OrderService.kt:42 @@ -1,2 +1,2 @@ OrderService misses validation",
            ),
            mapOf(
              "severity" to "major",
              "message" to "src/main/kotlin/OrderService.kt:42 @@ -1,2 +1,2 @@ OrderService misses validation",
            ),
            mapOf("severity" to "minor", "message" to "/tmp/work/Repository.kt:8 Repository leaks a detail"),
          ),
        ),
      ),
    )

    assertEquals(2, summary.size)
    assertEquals("OrderService", summary.first().label)
    val rendered = summary.joinToString(" ") { "${it.label} ${it.text}" }
    assertFalse("src/" in rendered)
    assertFalse("/tmp/" in rendered)
    assertFalse(Regex(":\\d+").containsMatchIn(rendered))
    assertFalse("@@" in rendered)
  }

  @Test
  fun `compact summaries prefer structured labels and remove bare filenames`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "severity" to "major",
              "class_or_symbol" to "CheckoutService.submit",
              "message" to "CheckoutService.kt:17 @@ -4,6 +4,9 @@ submit permits an invalid transition",
            ),
            mapOf(
              "severity" to "major",
              "symbol" to "CheckoutService.submit",
              "message" to "src/CheckoutService.kt:17 submit bypasses the aggregate invariant",
            ),
            mapOf(
              "severity" to "minor",
              "class_or_symbol" to "src/LegacyCheckout.kt:28",
              "message" to "LegacyCheckout.kt:28 needs a separate note",
            ),
          ),
        ),
      ),
    )

    assertEquals(2, summary.size)
    val checkout = summary.first { it.label == "CheckoutService.submit" }
    val legacy = summary.first { it.label == "LegacyCheckout" }
    assertFalse("CheckoutService.kt" in checkout.text)
    assertFalse(Regex("(?:^|\\s)[A-Za-z0-9_.-]+\\.(?:kt|java)(?:\\s|$)").containsMatchIn(checkout.text))
    assertTrue(checkout.text.contains("invalid transition"))
    assertFalse("/" in legacy.label)
    assertFalse(Regex(":\\d+").containsMatchIn(legacy.label))
  }

  @Test
  fun `compact summaries remove common location forms without selecting raw title text`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf("severity" to "major", "message" to "lines 42-44 bypass the invariant"),
            mapOf("severity" to "major", "message" to "L42-L44 bypass the invariant"),
            mapOf("severity" to "major", "message" to "at #12 bypasses the invariant"),
            mapOf("severity" to "major", "message" to "src/OrderService.kt:42-44 has columns 3-8 exposed"),
            mapOf("severity" to "major", "message" to "C:\\repo\\Checkout.kt:17 @@ -4,6 +4,9 @@ leaks state"),
          ),
        ),
      ),
    )

    val rendered = summary.joinToString(" ") { "${it.label} ${it.text}" }
    assertTrue(summary.any { it.label == "Review" })
    assertFalse(Regex("(?i)\\b(?:lines?|columns?)\\s+\\d+|\\bL\\d+|#\\d+|:\\d+|@@").containsMatchIn(rendered))
    assertFalse("src/" in rendered)
    assertFalse("C:\\repo" in rendered)
    assertFalse("OrderService.kt" in rendered)
    assertFalse("Checkout.kt" in rendered)
  }

  @Test
  fun `compact summaries reject unsafe labels and parenthesized or colon coordinates`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "severity" to "major",
              "class_or_symbol" to "src/main/kotlin/OrderService.kt(42,17)",
              "message" to "OrderService.kt(42,17) line: 42 leaves the order unvalidated",
            ),
            mapOf(
              "severity" to "major",
              "symbol" to "OrderService.submit",
              "message" to "src/main/kotlin/OrderService.kt:42 needs an invariant",
            ),
          ),
        ),
      ),
    )

    assertTrue(summary.any { it.label == "OrderService" })
    assertTrue(summary.any { it.label == "OrderService.submit" })
    val rendered = summary.joinToString(" ") { "${it.label} ${it.text}" }
    assertFalse("OrderService.kt" in rendered)
    assertFalse("line:" in rendered.lowercase())
    assertFalse(Regex("\\(\\d+,").containsMatchIn(rendered))
    assertFalse(Regex(":\\s*\\d+").containsMatchIn(rendered))
  }

  @Test
  fun `compact summaries remove bracket coordinates and diff markers`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "severity" to "major",
              "message" to "OrderService.kt[42,17] --- +++ leaves the order unvalidated",
            ),
            mapOf(
              "severity" to "minor",
              "message" to "diff --git index abcdef0 --- +++ preserves a safe summary",
            ),
          ),
        ),
      ),
    )

    val rendered = summary.joinToString(" ") { "${it.label} ${it.text}" }
    assertFalse("[42,17]" in rendered)
    assertFalse("---" in rendered)
    assertFalse("+++" in rendered)
    assertTrue(rendered.contains("leaves the order unvalidated"))
    assertTrue(rendered.contains("preserves a safe summary"))
  }

  @Test
  fun `Blocker plus Major findings reopen remediation and count as unresolved`() {
    val output = mapOf(
      "verdict" to FeatureTaskRuntimeVerdict.APPROVED.wireValue,
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "blocker",
            "class_or_symbol" to "DataStore",
            "message" to "Data loss",
          ),
          mapOf(
            "severity" to "major",
            "class_or_symbol" to "RiskPolicy",
            "message" to "Follow-up risk",
          ),
        ),
      ),
    )

    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output)

    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, outcome.verdict)
    assertEquals(2, outcome.unresolvedFindingCount)
    assertEquals(
      2,
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output,
        UnaddressedFindingLedgerScope("SKILL-146", 3, "workflow", 1),
      ).size,
    )
  }

  @Test
  fun `Minor-only structured findings stay approved and non-blocking`() {
    val output = mapOf(
      "verdict" to FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue,
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf("severity" to "minor", "message" to "Prefer clearer name"),
        ),
      ),
    )
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, outcome.verdict)
    assertEquals(0, outcome.unresolvedFindingCount)
  }

  @Test
  fun `rejected verification findings land in the ledger from review identity with optional reason`() {
    val reviewOutput = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "minor",
            "message" to "Prefer clearer name",
            "location" to "Example.kt",
          ),
        ),
      ),
    )
    val verifyOutput = mapOf(
      "produced_outputs" to mapOf(
        FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS to listOf(
          mapOf(
            "finding_id" to "F-001",
            "disposition" to "rejected",
            "reason" to "False positive against spec intent.",
            "severity" to "blocker",
            "location" to "Other.kt",
            "message" to "Census message must not win",
          ),
        ),
      ),
    )
    val scope = UnaddressedFindingLedgerScope("SKILL-202", 2, "wf-verify", 1)
    val truncationRecords = mutableListOf<String>()
    val rejected = GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
      verifyOutput = verifyOutput,
      reviewOutput = reviewOutput,
      scope = scope,
      truncationRecords = truncationRecords,
    )
    assertEquals(1, rejected.size)
    assertEquals("rejected", rejected.single().verificationDisposition)
    assertEquals("False positive against spec intent.", rejected.single().verificationReason)
    assertEquals("minor", rejected.single().severity)
    assertEquals("Example.kt", rejected.single().location)
    assertEquals("Prefer clearer name", rejected.single().summary)
    assertTrue(truncationRecords.isEmpty())
  }

  @Test
  fun `rejected verification findings without census reason still land in the ledger`() {
    val reviewOutput = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "minor",
            "message" to "Prefer clearer name",
            "location" to "Example.kt",
          ),
        ),
      ),
    )
    val verifyOutput = mapOf(
      "produced_outputs" to mapOf(
        FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS to listOf(
          mapOf(
            "finding_id" to "F-001",
            "disposition" to "rejected",
          ),
        ),
      ),
    )
    val rejected = GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
      verifyOutput = verifyOutput,
      reviewOutput = reviewOutput,
      scope = UnaddressedFindingLedgerScope("SKILL-216", 2, "wf-verify", 1),
    )
    assertEquals(1, rejected.size)
    assertNull(rejected.single().verificationReason)
    assertEquals("minor", rejected.single().severity)
  }

  @Test
  fun `rejected verification findings truncate over-long optional reason and record observability`() {
    val reviewOutput = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "finding_id" to "F-001",
            "severity" to "minor",
            "message" to "Prefer clearer name",
            "location" to "Example.kt",
          ),
        ),
      ),
    )
    val overLongReason = "x".repeat(GoalSubtaskReviewSummaryReducer.REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES + 1)
    val verifyOutput = mapOf(
      "produced_outputs" to mapOf(
        FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS to listOf(
          mapOf(
            "finding_id" to "F-001",
            "disposition" to "rejected",
            "reason" to overLongReason,
          ),
        ),
      ),
    )
    val truncationRecords = mutableListOf<String>()
    val rejected = GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
      verifyOutput = verifyOutput,
      reviewOutput = reviewOutput,
      scope = UnaddressedFindingLedgerScope("SKILL-216", 2, "wf-verify", 1),
      truncationRecords = truncationRecords,
    )
    assertEquals(1, rejected.size)
    assertEquals(
      GoalSubtaskReviewSummaryReducer.REJECTED_VERIFICATION_REASON_MAX_UTF8_BYTES,
      Utf8Text.utf8Size(requireNotNull(rejected.single().verificationReason)),
    )
    assertEquals(1, truncationRecords.size)
    assertTrue(truncationRecords.single().contains("F-001"))
  }

  @Test
  fun `compact summaries fall back when locations and diff markers consume all finding text`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf("severity" to "major", "message" to "OrderService.kt[42,17] --- +++"),
          ),
        ),
      ),
    )

    assertEquals("Review finding", summary.single().text)
  }

  @Test
  fun `compact summaries remove location details while ledger preserves them`() {
    // A finding message containing a path, a line reference, and a diff hunk is sanitized out of
    // the compact goal-facing summary. The same finding's location survives in the UnaddressedFinding
    // ledger row, so location-bearing evidence is retrievable through `skill-bill goal findings`.
    val output = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "major",
            "message" to "src/main/kotlin/OrderService.kt:42 @@ -1,2 +1,2 @@ OrderService misses validation",
            "issue_category" to "behavior_correctness",
            "location" to "src/main/kotlin/OrderService.kt:42",
          ),
          mapOf(
            "severity" to "blocker",
            "message" to "CheckoutService.kt:17 data leak in submit method",
            "issue_category" to "security_privacy",
            "location" to "CheckoutService.kt:17",
          ),
        ),
      ),
    )

    // Compact summary strips all location-bearing details
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(output)
    assertEquals(2, summary.size)
    val rendered = summary.joinToString(" ") { "${it.label} ${it.text}" }

    assertFalse("src/" in rendered, "compact summary must not contain path")
    assertFalse(Regex(":\\d+").containsMatchIn(rendered), "compact summary must not contain line reference")
    assertFalse("@@" in rendered, "compact summary must not contain diff hunk")
    assertTrue(rendered.contains("OrderService"), "compact summary must retain class name")
    assertTrue(rendered.contains("CheckoutService"), "compact summary must retain class name")

    // Ledger preserves full location-bearing evidence
    val ledger = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output,
      UnaddressedFindingLedgerScope("SKILL-142", 1, "wf-1", 1),
    )
    assertEquals(2, ledger.size)

    val majorFinding = ledger.first { it.severity == "major" }
    assertEquals("src/main/kotlin/OrderService.kt:42", majorFinding.location)
    assertEquals("behavior_correctness", majorFinding.issueCategory)

    val blockerFinding = ledger.first { it.severity == "blocker" }
    assertEquals("CheckoutService.kt:17", blockerFinding.location)
    assertEquals("security_privacy", blockerFinding.issueCategory)
  }

  @Test
  fun `verification boundary paths union repository path location suffix and citation paths`() {
    val finding = StructuredGoalReviewFinding(
      severity = "major",
      message = "Mismatch between cited evidence and primary location.",
      issueCategory = "behavior_correctness",
      location = "runtime-kotlin/runtime-application/src/Primary.kt:17",
      compactLabel = "Review",
      repositoryPath = "runtime-kotlin/runtime-application/src/Primary.kt",
      citations = listOf(ReviewFindingCitation("runtime-kotlin/runtime-application/src/Cited.kt", 42)),
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-application/src/Primary.kt",
        "runtime-kotlin/runtime-application/src/Cited.kt",
      ),
      GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding),
    )
  }

  @Test
  fun `verification boundary paths accept root level locations without slash`() {
    val finding = StructuredGoalReviewFinding(
      severity = "minor",
      message = "Root-level file reference.",
      issueCategory = "other",
      location = "Foo.kt",
      compactLabel = "Review",
    )
    assertEquals(listOf("Foo.kt"), GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding))
  }

  @Test
  fun `structured findings carry repository_path from review envelope`() {
    val output = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "major",
            "message" to "Writer-produced finding",
            "location" to "src/Feature.kt:42",
            "repository_path" to "src/Feature.kt",
          ),
        ),
      ),
    )
    assertEquals(
      "src/Feature.kt",
      GoalSubtaskReviewSummaryReducer.structuredFindings(output).single().repositoryPath,
    )
  }

  @Test
  fun `citationsOf rejects absolute and traversal citation paths before discovery`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewFindingFieldCodec.citationsOf(listOf(mapOf("path" to "/etc/passwd", "line" to 1)))
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewFindingFieldCodec.citationsOf(listOf(mapOf("path" to "../secret.kt", "line" to 1)))
    }
  }

  @Test
  fun `verification boundary paths reject absolute and traversal location suffixes`() {
    val absolute = StructuredGoalReviewFinding(
      severity = "major",
      message = "Absolute location must not widen discovery.",
      issueCategory = "other",
      location = "/runtime-kotlin/runtime-application/src/Primary.kt:17",
      compactLabel = "Review",
      repositoryPath = "runtime-kotlin/runtime-application/src/Primary.kt",
    )
    assertEquals(
      listOf("runtime-kotlin/runtime-application/src/Primary.kt"),
      GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(absolute),
    )

    val traversal = StructuredGoalReviewFinding(
      severity = "major",
      message = "Traversal location must not widen discovery.",
      issueCategory = "other",
      location = "../runtime-kotlin/runtime-application/src/Primary.kt:17",
      compactLabel = "Review",
    )
    assertEquals(emptyList(), GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(traversal))
  }

  @Test
  fun `structured findings drop invalid repository_path values`() {
    val output = mapOf(
      "produced_outputs" to mapOf(
        "findings" to listOf(
          mapOf(
            "severity" to "major",
            "message" to "Invalid primary path",
            "location" to "src/Feature.kt:42",
            "repository_path" to "/etc/passwd",
          ),
        ),
      ),
    )
    assertNull(GoalSubtaskReviewSummaryReducer.structuredFindings(output).single().repositoryPath)
  }
}
