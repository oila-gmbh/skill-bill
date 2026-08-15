package skillbill.application.review

import skillbill.application.model.ParallelCodeReviewResult
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.ProviderTokenUsage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recording end-to-end proof over the production parallel-review composition: a small Kotlin diff
 * and a layered KMP diff, each driven through real preparation, flattening, inline lane launch, and
 * accounting seams.
 */
class ParallelCodeReviewEndToEndTest {
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("platform-correctness", "ui")

  @Test fun `kotlin diff discovers once and launches exactly the two inline parent lanes`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      kotlinConfig { RecordedWorkerResponse(stdout = finding("src/Repo.kt", KOTLIN_ARCHITECTURE)) },
      recorder,
    )

    val result = runner.run(harnessRequest())

    assertEquals(
      1,
      recorder.diffCommands.count { it.contains("diff") },
      "Scope discovery must happen once for the whole review, not once per lane.",
    )
    assertEquals(
      2,
      recorder.parentLaunches.filter { it.skillRunRequest.issueKey == "code-review-parallel" }.size,
      "The surviving fan-out runs exactly two parent lanes.",
    )
    assertEquals(
      listOf("claude", "codex"),
      recorder.parentLaunches
        .filter { it.skillRunRequest.issueKey == "code-review-parallel" }
        .map { it.invokedAgentId }
        .sorted(),
      "parallel-review keeps a second lane on a distinct agent.",
    )
    recorder.parentLaunches
      .filter { it.skillRunRequest.issueKey == "code-review-parallel" }
      .mapNotNull { it.skillRunRequest.promptOverride }
      .forEach { prompt ->
        kotlinAreas.forEach { area ->
          assertTrue(
            prompt.contains("bill-kotlin-code-review-$area"),
            "Inline prompt dropped routed rubric identity '$area'.",
          )
        }
      }
    assertTrue(result.lane1.success && result.lane2.success)
  }

  @Test fun `inline prompts carry no rediscovery affordance`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig(), recorder).run(harnessRequest())

    assertTrue(recorder.parentPrompts.isNotEmpty())
    recorder.parentPrompts.forEach { prompt ->
      listOf("git diff", "gh pr", "merge-base", "AGENTS.md", "platform-packs/").forEach { affordance ->
        assertTrue(!prompt.contains(affordance), "Inline prompt leaked '$affordance'.")
      }
    }
  }

  @Test fun `assigned evidence is carried only in prompt hunk envelopes`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig(), recorder).run(harnessRequest())

    recorder.parentPrompts.forEach { prompt ->
      assertTrue(prompt.contains("## Assigned bundle:"))
      assertTrue(prompt.contains("\"src/Repo.kt\""))
      assertTrue(prompt.contains("Use the assigned bundle evidence below as authoritative"))
    }
  }

  @Test fun `layered kmp composition expands directly to kmp and required kotlin rubrics`() {
    val recorder = ReviewRecorder()

    reviewHarness(kmpConfig(), recorder).run(harnessRequest())

    val expected = kmpAreas.map { "bill-kmp-code-review-$it" } + kotlinAreas.map { "bill-kotlin-code-review-$it" }
    recorder.parentPrompts.forEach { prompt ->
      expected.forEach { specialist ->
        assertTrue(prompt.contains(specialist), "Composed inline prompt dropped '$specialist'.")
      }
    }
  }

  @Test fun `repeated layered runs produce identical findings and accounting`() {
    val repoRoot = Files.createTempDirectory("review-e2e-determinism")
    fun run(): ParallelCodeReviewResult {
      val recorder = ReviewRecorder()
      return reviewHarness(
        kmpConfig {
          RecordedWorkerResponse(
            stdout = finding("src/main/kotlin/App.kt", KOTLIN_ARCHITECTURE),
            usage = ProviderTokenUsage(1_000, 400, 200, 50, 1_200),
          )
        },
        recorder,
      ).run(harnessRequest(repoRoot = repoRoot))
    }

    val first = run()
    val second = run()

    assertEquals(first.mergeResult.formattedOutput, second.mergeResult.formattedOutput)
    val firstSummary = assertNotNull(first.accountingSummary)
    val secondSummary = assertNotNull(second.accountingSummary)
    assertEquals(firstSummary.toBoundedPayload(), secondSummary.toBoundedPayload())
    assertEquals(firstSummary.lanes.map { it.lane }, secondSummary.lanes.map { it.lane })
  }

  @Test fun `accounting reports every measured dimension and aggregates without double counting`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      kotlinConfig {
        RecordedWorkerResponse(
          stdout = finding("src/Repo.kt", KOTLIN_ARCHITECTURE),
          usage = ProviderTokenUsage(1_000, 400, 200, 50, 1_200),
        )
      },
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val lanes = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(2, lanes.size, "Each inline parent lane owns exactly one accounting node.")
    lanes.forEach { lane ->
      assertTrue(lane.counters.launchBytes > 0, "Lane '${lane.lane}' reported no launch bytes.")
      assertEquals(0, lane.counters.evidenceBytes, "Assigned hunk envelopes require no filesystem evidence reads.")
      assertTrue(lane.counters.resultBytes > 0)
      assertEquals(1_000, lane.directUsage.inputTokens)
      assertEquals(400, lane.directUsage.cachedInputTokens)
      assertEquals(800, lane.directUsage.freshTokenApproximation)
      assertEquals("completed", lane.terminalOutcome)
    }
    assertEquals(lanes.size * 1_000L, summary.aggregateDirectUsage.inputTokens)
    assertEquals(lanes.size * 800L, summary.aggregateDirectUsage.freshTokenApproximation)
    assertEquals(summary.aggregateDirectUsage.inputTokens, summary.aggregateInclusiveUsage.inputTokens)
    assertEquals(lanes.sumOf { it.counters.launchBytes }, summary.aggregateCounters.launchBytes)
    assertEquals(summary.aggregateCounters, summary.parent.inclusiveCounters)
  }

  @Test fun `durable accounting is persisted exactly once per review`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig { RecordedWorkerResponse(usage = ProviderTokenUsage(10, 2, 3)) }, recorder)
      .run(harnessRequest())

    val record = recorder.savedAccounting.single()
    assertEquals("accounting_summary", record.boundedPayload["kind"])
    assertTrue(record.reviewId.isNotBlank() && record.packetDigest.isNotBlank())
  }

  @Test fun `durable accounting is keyed by the caller review run id telemetry resolves`() {
    val recorder = ReviewRecorder()
    val reviewRunId = "rvw-20260722-101500-ab12"

    reviewHarness(kotlinConfig { RecordedWorkerResponse(usage = ProviderTokenUsage(10, 2, 3)) }, recorder)
      .run(harnessRequest(reviewRunId = reviewRunId))

    val record = recorder.savedAccounting.single()
    assertEquals(reviewRunId, record.reviewId)
    assertEquals(reviewRunId, record.boundedPayload["review_id"])
  }

  @Test fun `accounting falls back to the packet review id when no run id is supplied`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig { RecordedWorkerResponse(usage = ProviderTokenUsage(10, 2, 3)) }, recorder)
      .run(harnessRequest())

    assertTrue(recorder.savedAccounting.single().reviewId.startsWith("code-review-parallel-"))
  }

  private fun kotlinConfig(
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))),
    diff = diffForPaths("src/Repo.kt"),
    response = response,
  )

  private fun kmpConfig(
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ) = ReviewHarnessConfig(
    manifests = listOf(
      reviewPack(
        "kmp",
        kmpAreas,
        layers = listOf(reviewLayer("kotlin")),
        routingSignals = listOf("*.kt", "commonMain"),
        contentSignals = listOf("expect", "actual"),
      ),
      reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt")),
    ),
    diff = diffForChanges(
      "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
      "src/main/kotlin/App.kt" to "actual fun platformName(): String = \"jvm\"",
    ),
    response = response,
  )
}

private const val KOTLIN_ARCHITECTURE = "bill-kotlin-code-review-architecture"

internal fun finding(path: String, specialist: String? = null): String {
  val attribution = specialist?.let { "specialist=$it | " }.orEmpty()
  return "- [F-001] Major | High | $attribution" + "path=\"$path\" | line=1 | Bounded specialist finding"
}
