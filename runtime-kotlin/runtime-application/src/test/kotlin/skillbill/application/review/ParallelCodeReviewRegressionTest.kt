package skillbill.application.review

import skillbill.error.MissingInstalledNativeAgentError
import skillbill.review.context.model.ProviderTokenThresholds
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.REVIEW_BUDGET_REGRESSION
import skillbill.review.context.model.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.model.ReviewContextBudgetPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Cross-seam regressions the optimized delegated review must keep holding. */
class ParallelCodeReviewRegressionTest {
  private val areas = listOf("architecture", "security", "testing")
  private val agentsBody = "AGENTS_BODY_SENTINEL ".repeat(400)
  private val parentBriefing = "PARENT_BRIEFING_SENTINEL ".repeat(400)

  @Test fun `long parent briefing and AGENTS bodies never reach a specialist or its accounting`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        diff = diffForChanges(
          "src/Repo.kt" to "val briefing = \"$parentBriefing\"",
          "AGENTS.md" to agentsBody,
        ),
      ),
      recorder,
    )

    val result = runner.run(harnessRequest())

    recorder.nativeLaunches.forEach { launch ->
      assertFalse(launch.prompt.contains("AGENTS_BODY_SENTINEL"), "Specialist saw a project-guidance body.")
      assertFalse(launch.prompt.contains("PARENT_BRIEFING_SENTINEL"), "Specialist saw the parent briefing body.")
    }
    val summary = assertNotNull(result.accountingSummary)
    val serialized = summary.toBoundedPayload().toString()
    assertFalse(serialized.contains("AGENTS_BODY_SENTINEL"))
    assertFalse(serialized.contains("PARENT_BRIEFING_SENTINEL"))
    assertTrue(summary.aggregateCounters.launchBytes > 0, "Bodies are measured even though they are not retained.")
  }

  @Test fun `overlapping lane ownership assigns each hunk once and never doubles usage`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config { request ->
        RecordedWorkerResponse(
          stdout = finding("src/Repo.kt", specialist = request.logicalWorkerName),
          usage = ProviderTokenUsage(500, 100, 50, 10, 550),
        )
      },
      recorder,
    )

    val result = runner.run(harnessRequest())
    val summary = assertNotNull(result.accountingSummary)

    val specialists = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(
      specialists.size,
      specialists.map { it.assignmentDigest }.distinct().size,
      "Every owned lane carries its own assignment digest.",
    )
    assertEquals(specialists.size * 500L, summary.aggregateDirectUsage.inputTokens)
    assertEquals(
      summary.aggregateDirectUsage.inputTokens,
      summary.aggregateInclusiveUsage.inputTokens,
      "Overlapping ownership must not fold a session's usage in twice.",
    )
    assertTrue(result.mergeResult.formattedOutput.isNotBlank())
  }

  @Test fun `a dangling required native agent stops every launch with the governed repair command`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        preflight = {
          throw MissingInstalledNativeAgentError(
            logicalName = "bill-kotlin-code-review-security",
            provider = "codex",
            expectedPath = "~/.codex/agents/bill-kotlin-code-review-security.md",
            reason = "managed inventory entry is missing",
            repairCommand = "skill-bill install apply",
          )
        },
      ),
      recorder,
    )

    val failure = assertFailsWith<MissingInstalledNativeAgentError> { runner.run(harnessRequest()) }

    assertEquals("skill-bill install apply", failure.repairCommand)
    assertTrue(recorder.nativeLaunches.isEmpty(), "No specialist may start after a failed preflight.")
    assertTrue(recorder.parentLaunches.isEmpty(), "A failed preflight must not fall back to a generic parent review.")
  }

  @Test fun `excessive lane output terminates only the affected lane with a typed outcome`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneResultBytes = 512),
      ) { request ->
        if (request.logicalWorkerName == "bill-kotlin-code-review-security") {
          RecordedWorkerResponse(stdout = "x".repeat(4_096))
        } else {
          RecordedWorkerResponse(stdout = finding("src/Repo.kt", specialist = request.logicalWorkerName))
        }
      },
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val outcomes = summary.lanes.filter { it.children.isEmpty() }.associate { it.lane to it.terminalOutcome }
    assertTrue(outcomes.filterKeys { it.endsWith("security") }.values.all { it == REVIEW_CONTEXT_BUDGET_EXCEEDED })
    assertTrue(
      outcomes.filterKeys { !it.endsWith("security") }.values.all { it == "completed" },
      "A sibling specialist stays independent of another lane's budget termination.",
    )
  }

  @Test fun `excessive model turns terminate the lane before its result is admitted`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxSpecialistModelTurns = 2)) { request ->
        if (request.logicalWorkerName == "bill-kotlin-code-review-testing") {
          RecordedWorkerResponse(modelTurns = 5)
        } else {
          RecordedWorkerResponse()
        }
      },
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val exceeded = summary.lanes.filter { it.terminalOutcome == REVIEW_CONTEXT_BUDGET_EXCEEDED }
    assertEquals(2, exceeded.size, "Exactly the testing lane of each top-level lane terminates.")
    assertTrue(exceeded.all { it.lane.endsWith("testing") })
    assertTrue(exceeded.all { it.counters.modelTurns >= 2 })
  }

  @Test fun `a single oversized evidence file terminates the lane before any of it is admitted`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxEvidenceResultBytes = 64))
        .copy(evidenceBody = { "E".repeat(256) }),
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val specialists = summary.lanes.filter { it.children.isEmpty() }
    assertTrue(specialists.isNotEmpty())
    assertTrue(specialists.all { it.terminalOutcome == REVIEW_CONTEXT_BUDGET_EXCEEDED })
    assertTrue(
      specialists.all { it.counters.evidenceBytes == 0L },
      "An over-budget file is never read, so its bytes never enter the lane's cumulative evidence.",
    )
  }

  @Test fun `cumulative brokered evidence beyond the lane budget terminates after the admitted reads`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        diff = diffForPaths("src/Repo.kt", "src/Other.kt"),
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneEvidenceBytes = 64, maxEvidenceResultBytes = 48),
      ).copy(evidenceBody = { "E".repeat(40) }),
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val specialists = summary.lanes.filter { it.children.isEmpty() }
    assertTrue(specialists.isNotEmpty())
    assertTrue(specialists.all { it.terminalOutcome == REVIEW_CONTEXT_BUDGET_EXCEEDED })
    assertTrue(
      specialists.all { it.counters.evidenceBytes == 40L },
      "The lane keeps the bytes it was served and stops at the read that would exceed the budget.",
    )
  }

  @Test fun `an enforceable provider threshold excess terminates the lane it belongs to`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(
          providerTokenThresholds = ProviderTokenThresholds(outputTokens = 100, totalTokens = 60_000),
        ),
      ) { request ->
        if (request.logicalWorkerName == "bill-kotlin-code-review-architecture") {
          RecordedWorkerResponse(usage = ProviderTokenUsage(10, 0, 5_000, 0, 5_010), usageEnforceable = true)
        } else {
          RecordedWorkerResponse(usage = ProviderTokenUsage(10, 0, 10, 0, 20), usageEnforceable = true)
        }
      },
      recorder,
    )

    val result = runner.run(harnessRequest())
    val summary = assertNotNull(result.accountingSummary)

    val terminated = summary.lanes.filter { it.terminalOutcome == REVIEW_CONTEXT_BUDGET_EXCEEDED }
    assertTrue(terminated.isNotEmpty() && terminated.all { it.lane.endsWith("architecture") })
    assertTrue(summary.lanes.filter { it.lane.endsWith("security") }.all { it.terminalOutcome == "completed" })
    assertFalse(summary.budgetRegression, "A live-enforceable excess is not a post-run regression.")
  }

  @Test fun `cumulative cached input beyond a non-enforceable threshold reports a budget regression`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(
          providerTokenThresholds = ProviderTokenThresholds(
            inputTokens = 1_000,
            cachedInputTokens = 400,
            totalTokens = 60_000,
          ),
        ),
      ) { RecordedWorkerResponse(usage = ProviderTokenUsage(5_000, 4_000, 200, 30, 5_200), usageEnforceable = false) },
      recorder,
    )

    val result = runner.run(harnessRequest())
    val summary = assertNotNull(result.accountingSummary)

    assertTrue(summary.budgetRegression, "A post-run threshold excess is reported as a regression.")
    val specialists = summary.lanes.filter { it.children.isEmpty() }
    assertTrue(specialists.all { it.terminalOutcome == REVIEW_BUDGET_REGRESSION })
    specialists.forEach { lane ->
      assertEquals(5_000, lane.directUsage.inputTokens)
      assertEquals(4_000, lane.directUsage.cachedInputTokens, "Cached input stays reported on its own axis.")
      assertEquals(1_200, lane.directUsage.freshTokenApproximation)
    }
    assertEquals(specialists.size * 4_000L, summary.aggregateDirectUsage.cachedInputTokens)
    assertEquals(specialists.size * 1_200L, summary.aggregateDirectUsage.freshTokenApproximation)
    assertTrue(result.lane1.success, "A regression records the overrun; it never replaces or truncates the lane.")
    assertEquals(recorder.nativeLaunches.size, specialists.size, "No lane is relaunched after a regression.")
  }

  private fun config(
    diff: String = diffForPaths("src/Repo.kt"),
    budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
    preflight: (skillbill.ports.review.model.ReviewNativeAgentPreflightRequest) -> Unit = {},
    response: (skillbill.ports.review.model.NativeReviewWorkerRequest) -> RecordedWorkerResponse = {
      RecordedWorkerResponse()
    },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", areas, routingSignals = listOf("*.kt", "*.md"))),
    diff = diff,
    response = response,
    budget = budget,
    preflight = preflight,
  )
}
