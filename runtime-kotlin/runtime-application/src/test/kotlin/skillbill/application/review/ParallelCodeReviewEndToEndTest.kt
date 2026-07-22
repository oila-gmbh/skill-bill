package skillbill.application.review

import skillbill.application.model.ParallelCodeReviewResult
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.review.context.model.ProviderTokenUsage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recording end-to-end proof over the production parallel-review composition: a small Kotlin diff
 * and a layered KMP diff, each driven through real preparation, flattening, preflight, broker, and
 * accounting seams.
 */
class ParallelCodeReviewEndToEndTest {
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("platform-correctness", "ui")

  @Test fun `kotlin diff discovers once and launches exactly the direct specialist lanes`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(kotlinConfig { RecordedWorkerResponse(stdout = finding("src/Repo.kt")) }, recorder)

    val result = runner.run(harnessRequest())

    assertEquals(
      1,
      recorder.diffCommands.count { it.contains("diff") },
      "Scope discovery must happen once for the whole review, not once per lane or specialist.",
    )
    assertEquals(
      kotlinAreas.map { "bill-kotlin-code-review-$it" }.sorted(),
      recorder.launchedSpecialists.distinct().sorted(),
    )
    assertEquals(kotlinAreas.size * 2, recorder.nativeLaunches.size, "Each top-level lane runs every specialist once.")
    assertTrue(
      recorder.nativeLaunches.none { it.logicalWorkerName == "bill-kotlin-code-review" },
      "A flattened plan never launches the baseline skill as a nested orchestrator.",
    )
    assertTrue(result.lane1.success && result.lane2.success)
  }

  @Test fun `every codex specialist launches with fork turns none in a fresh context`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig(), recorder).run(harnessRequest())

    assertTrue(recorder.nativeLaunches.isNotEmpty())
    recorder.nativeLaunches.forEach { launch ->
      assertEquals(ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE, launch.isolation)
      assertEquals("none", launch.isolation.forkTurns)
    }
  }

  @Test fun `child prompts carry no rediscovery affordance and forbidden child operations are refused`() {
    val recorder = ReviewRecorder()
    val forbidden = listOf(
      shellOperation("git diff main...HEAD"),
      shellOperation("gh pr view"),
      shellOperation("./gradlew check"),
      searchOperation("rg TODO", scopes = listOf("src/Other.kt")),
      mcpOperation("resolve_learnings"),
      mcpOperation("stack_routing"),
      fileOperation("AGENTS.md"),
      fileOperation("platform-packs/kotlin/platform.yaml"),
    )
    val runner = reviewHarness(
      kotlinConfig { RecordedWorkerResponse(childOperations = forbidden) },
      recorder,
    )

    runner.run(harnessRequest())

    assertEquals(
      forbidden.size * recorder.nativeLaunches.size,
      recorder.refusedOperations.size,
      "Every forbidden child operation must be refused by the governed policy.",
    )
    assertTrue(
      recorder.refusedOperations.map { it.category }.containsAll(
        listOf(
          "diff_recomputation",
          "review_scope",
          "build_test_fact_discovery",
          "broad_repository_search",
          "learnings_resolution",
          "dominant_stack_routing",
          "project_guidance_traversal",
          "platform_pack_and_addon_resolution",
        ),
      ),
    )
    recorder.nativeLaunches.forEach { launch ->
      listOf("git diff", "gh pr", "merge-base", "AGENTS.md", "platform-packs/").forEach { affordance ->
        assertTrue(
          !launch.prompt.contains(affordance),
          "Specialist prompt for '${launch.logicalWorkerName}' leaked '$affordance'.",
        )
      }
    }
  }

  @Test fun `evidence is requested in one bounded batch per specialist lane`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig(), recorder).run(harnessRequest())

    assertEquals(recorder.nativeLaunches.size, recorder.evidenceBatches.size)
    recorder.evidenceBatches.forEach { batch ->
      assertTrue(batch.requests.isNotEmpty())
      assertEquals(batch.requests.map { it.path }.distinct(), batch.requests.map { it.path })
      assertTrue(batch.requests.all { it.lane == batch.lane })
    }
  }

  @Test fun `layered kmp composition expands directly to kmp and required kotlin specialists`() {
    val recorder = ReviewRecorder()

    reviewHarness(kmpConfig(), recorder).run(harnessRequest())

    val expected = (
      kmpAreas.map { "bill-kmp-code-review-$it" } +
        kotlinAreas.map { "bill-kotlin-code-review-$it" }
      ).sorted()
    assertEquals(expected, recorder.launchedSpecialists.distinct().sorted())
    assertTrue(
      recorder.nativeLaunches.none { it.logicalWorkerName in setOf("bill-kmp-code-review", "bill-kotlin-code-review") },
      "A composed root never launches a baseline review skill as a nested orchestrator.",
    )
  }

  @Test fun `native agent preflight verifies every assignment before any launch`() {
    val recorder = ReviewRecorder()

    reviewHarness(
      kmpConfig(preflight = { assertTrue(recorder.nativeLaunches.isEmpty(), "Preflight must precede every launch.") }),
      recorder,
    ).run(harnessRequest())

    val preflight = recorder.preflightRequests.single()
    assertEquals(
      recorder.launchedSpecialists.distinct().sorted(),
      preflight.assignments.map { it.logicalName }.distinct().sorted(),
    )
    assertEquals(setOf("codex", "claude"), preflight.assignments.map { it.agentId }.toSet())
  }

  @Test fun `repeated layered runs produce identical findings and accounting`() {
    val repoRoot = Files.createTempDirectory("review-e2e-determinism")
    fun run(): ParallelCodeReviewResult {
      val recorder = ReviewRecorder()
      return reviewHarness(
        kmpConfig { request ->
          RecordedWorkerResponse(
            stdout = finding("src/main/kotlin/App.kt", specialist = request.logicalWorkerName),
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
          stdout = finding("src/Repo.kt"),
          usage = ProviderTokenUsage(1_000, 400, 200, 50, 1_200),
        )
      },
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val specialists = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(kotlinAreas.size * 2, specialists.size)
    specialists.forEach { lane ->
      assertTrue(lane.counters.launchBytes > 0, "Lane '${lane.lane}' reported no launch bytes.")
      assertTrue(lane.counters.evidenceBytes > 0)
      assertTrue(lane.counters.resultBytes > 0)
      assertTrue(lane.counters.modelTurns > 0)
      assertEquals(1_000, lane.directUsage.inputTokens)
      assertEquals(400, lane.directUsage.cachedInputTokens)
      assertEquals(800, lane.directUsage.freshTokenApproximation)
      assertEquals("completed", lane.terminalOutcome)
    }
    assertEquals(specialists.size * 1_000L, summary.aggregateDirectUsage.inputTokens)
    assertEquals(specialists.size * 800L, summary.aggregateDirectUsage.freshTokenApproximation)
    assertEquals(summary.aggregateDirectUsage.inputTokens, summary.aggregateInclusiveUsage.inputTokens)
    assertEquals(specialists.sumOf { it.counters.launchBytes }, summary.aggregateCounters.launchBytes)
    assertEquals(specialists.sumOf { it.counters.modelTurns }, summary.aggregateCounters.modelTurns)
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
    preflight: (skillbill.ports.review.model.ReviewNativeAgentPreflightRequest) -> Unit = {},
    response: (skillbill.ports.review.model.NativeReviewWorkerRequest) -> RecordedWorkerResponse = {
      RecordedWorkerResponse()
    },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))),
    diff = diffForPaths("src/Repo.kt"),
    response = response,
    preflight = preflight,
  )

  private fun kmpConfig(
    preflight: (skillbill.ports.review.model.ReviewNativeAgentPreflightRequest) -> Unit = {},
    response: (skillbill.ports.review.model.NativeReviewWorkerRequest) -> RecordedWorkerResponse = {
      RecordedWorkerResponse()
    },
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
    preflight = preflight,
  )
}

internal fun finding(path: String, specialist: String? = null): String {
  val attribution = specialist?.let { "specialist=$it | " }.orEmpty()
  return "- [F-001] Major | High | $attribution" + "path=\"$path\" | line=1 | Bounded specialist finding"
}

internal fun shellOperation(command: String) = skillbill.review.context.model.ReviewRequestedOperation(
  skillbill.review.context.model.ReviewOperationKind.SHELL_COMMAND,
  command,
)

internal fun searchOperation(command: String, scopes: List<String>) =
  skillbill.review.context.model.ReviewRequestedOperation(
    skillbill.review.context.model.ReviewOperationKind.SEARCH,
    command,
    searchScopes = scopes,
  )

internal fun mcpOperation(tool: String) = skillbill.review.context.model.ReviewRequestedOperation(
  skillbill.review.context.model.ReviewOperationKind.MCP_TOOL,
  tool,
)

internal fun fileOperation(path: String) = skillbill.review.context.model.ReviewRequestedOperation(
  skillbill.review.context.model.ReviewOperationKind.FILE_READ,
  path,
)
