package skillbill.application.review

import skillbill.application.model.ReviewPrelaunchExpansion
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.DelegatedReviewWorkerState
import skillbill.ports.review.model.DelegatedReviewTerminalClassification
import skillbill.review.context.model.ProviderTokenThresholds
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.REVIEW_BUDGET_REGRESSION
import skillbill.review.context.model.REVIEW_CONTEXT_BUDGET_EXCEEDED
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.plan.DelegatedReviewDeadlinePolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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
    val repoRoot = java.nio.file.Files.createTempDirectory("review-context-isolation")
    java.nio.file.Files.writeString(repoRoot.resolve("AGENTS.md"), agentsBody)
    java.nio.file.Files.writeString(repoRoot.resolve("parent-briefing.md"), parentBriefing)
    val runner = reviewHarness(
      config(),
      recorder,
    )

    val result = runner.run(harnessRequest(repoRoot = repoRoot))

    recorder.nativeLaunches.forEach { launch ->
      assertFalse(launch.prompt.contains("AGENTS_BODY_SENTINEL"), "Specialist saw a project-guidance body.")
      assertFalse(launch.prompt.contains("PARENT_BRIEFING_SENTINEL"), "Specialist saw the parent briefing body.")
    }
    val summary = assertNotNull(result.accountingSummary)
    val serialized = summary.toBoundedPayload().toString()
    assertFalse(serialized.contains("AGENTS_BODY_SENTINEL"))
    assertFalse(serialized.contains("PARENT_BRIEFING_SENTINEL"))
    assertTrue(summary.aggregateCounters.launchBytes > 0, "The isolated launch projection remains measured.")
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

  @Test fun `provider-native specialist launches preflight installed logical workers`() {
    val recorder = ReviewRecorder()
    var preflightCalled = false
    val runner = reviewHarness(
      config(
        preflight = {
          preflightCalled = true
        },
      ),
      recorder,
    )

    runner.run(harnessRequest())

    assertTrue(preflightCalled)
    assertTrue(recorder.preflightRequests.isNotEmpty())
    assertTrue(recorder.nativeLaunches.isNotEmpty())
    assertTrue(recorder.nativeLaunches.all { it.logicalWorkerName != null })
  }

  @Test fun `durable lifecycle separates worker completion aggregation and terminal persistence`() {
    val recorder = ReviewRecorder()
    reviewHarness(config(), recorder).run(harnessRequest())

    val kinds = recorder.lifecycleEvents.map { it.eventKind }
    assertTrue(ReviewLifecycleEventKind.COORDINATOR_PREPARED in kinds)
    assertTrue(ReviewLifecycleEventKind.WORKER_COMPLETED in kinds)
    assertTrue(ReviewLifecycleEventKind.AGGREGATION_STARTED in kinds)
    assertTrue(ReviewLifecycleEventKind.AGGREGATION_COMPLETED in kinds)
    assertTrue(ReviewLifecycleEventKind.TERMINAL_COMPLETED in kinds)
    assertTrue(recorder.lifecycleEvents.zipWithNext().all { (first, second) -> first.sequence < second.sequence })
  }

  @Test fun `durable delegated projection preserves selected workers and wave metrics`() {
    val recorder = ReviewRecorder()

    reviewHarness(config(), recorder).run(harnessRequest())

    val projection = assertNotNull(recorder.lifecycleProjections.singleOrNull())
    assertEquals(1, projection.coordinatorSlots)
    assertEquals(projection.selectedAreaCount, projection.workers.size)
    assertEquals(projection.predictedWaveCount, projection.actualWaveCount)
    assertEquals(projection.selectedAreaCount, projection.metrics.completedAreaCount)
    assertEquals(recorder.nativeLaunches.size, projection.metrics.processCount)
    assertEquals(0, projection.metrics.mcpStartupCount)
    assertTrue(recorder.nativeLaunches.all { it.progressIdleTimeout != null })
    assertTrue(projection.workers.all { it.state == DelegatedReviewWorkerState.AGGREGATED })
  }

  @Test fun `lifecycle metrics count only explicit process and MCP startup observations`() {
    val recorder = ReviewRecorder()
    reviewHarness(
      config { RecordedWorkerResponse(processStarted = false, mcpStartupObserved = true) },
      recorder,
    ).run(harnessRequest())

    val projection = assertNotNull(recorder.lifecycleProjections.singleOrNull())
    assertEquals(0, projection.metrics.processCount)
    assertEquals(recorder.nativeLaunches.size, projection.metrics.mcpStartupCount)
  }

  @Test fun `lifecycle metrics exclude workers whose launcher never crossed process start`() {
    val recorder = ReviewRecorder()
    reviewHarness(
      config { RecordedWorkerResponse(stdout = "", processStarted = false, spawnFailed = true) },
      recorder,
    ).run(harnessRequest())

    val projection = assertNotNull(recorder.lifecycleProjections.lastOrNull())
    assertEquals(0, projection.metrics.processCount)
    assertEquals(0, projection.metrics.mcpStartupCount)
    assertTrue(recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.WORKER_UNAVAILABLE })
  }

  @Test fun `startup deadline blocks before provider launch and keeps its scope in durable evidence`() {
    val recorder = ReviewRecorder()
    var clockCalls = 0
    reviewHarness(
      config(
        deadlinePolicy = DelegatedReviewDeadlinePolicy(
          startupMs = 1,
          progressIdleMs = 1_000,
          perWorkerMs = 1_000,
          aggregationMs = 1_000,
          wholeReviewMs = 1_000,
        ),
        monotonicNowNanos = { if (clockCalls++ == 0) 0L else 2_000_000L },
      ),
      recorder,
    ).run(harnessRequest())

    assertTrue(recorder.nativeLaunches.isEmpty())
    assertTrue(
      recorder.lifecycleEvents
        .filter { it.eventKind == ReviewLifecycleEventKind.WORKER_TIMED_OUT }
        .all { it.diagnostic?.summary?.contains("scope=startup") == true },
    )
    assertTrue(recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.TERMINAL_TIMED_OUT })
  }

  @Test fun `worker timeout persists its deadline scope in bounded diagnostics`() {
    val recorder = ReviewRecorder()
    reviewHarness(
      config { RecordedWorkerResponse(timedOut = true) },
      recorder,
    ).run(harnessRequest())

    val timeoutEvents = recorder.lifecycleEvents.filter {
      it.eventKind == ReviewLifecycleEventKind.WORKER_TIMED_OUT
    }
    assertTrue(timeoutEvents.isNotEmpty())
    assertTrue(timeoutEvents.all { it.diagnostic?.summary?.contains("scope=per_worker") == true })
  }

  @Test fun `aggregation blocks when a completed worker has no admitted result envelope`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      config { RecordedWorkerResponse(stdout = "") },
      recorder,
    ).run(harnessRequest())

    assertFalse(result.lane1.success)
    assertTrue(recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED })
    assertTrue(recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.TERMINAL_FAILED })
  }

  @Test fun `coordinator capacity produces global multi-wave membership`() {
    val recorder = ReviewRecorder()
    val manyAreas = (1..8).map { "area-$it" }
    reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", manyAreas, routingSignals = listOf("*.kt", "*.md"))),
        diff = diffForPaths("src/Repo.kt"),
      ),
      recorder,
    ).run(harnessRequest())

    val projection = assertNotNull(recorder.lifecycleProjections.singleOrNull())
    assertEquals(2, projection.predictedWaveCount)
    assertEquals(2, projection.actualWaveCount)
    assertEquals(listOf(6, 2), projection.waves.map { it.workerIds.size })
    assertTrue(projection.waves.all { it.workerIds.size <= projection.selectedAreaCount - projection.coordinatorSlots })
    assertEquals(
      listOf(1, 2),
      recorder.lifecycleEvents
        .filter { it.eventKind == ReviewLifecycleEventKind.WORKER_LAUNCHED }
        .mapNotNull { it.waveNumber }
        .distinct(),
    )
  }

  @Test fun `workers in one wave cross the launch boundary concurrently`() {
    val recorder = ReviewRecorder()
    val entered = CountDownLatch(2)
    val inFlight = AtomicInteger(0)
    val maximumInFlight = AtomicInteger(0)
    val runner = reviewHarness(
      config {
        val active = inFlight.incrementAndGet()
        maximumInFlight.updateAndGet { current -> maxOf(current, active) }
        entered.countDown()
        entered.await(1, TimeUnit.SECONDS)
        inFlight.decrementAndGet()
        RecordedWorkerResponse()
      },
      recorder,
    )

    runner.run(harnessRequest())

    assertTrue(maximumInFlight.get() > 1)
    assertEquals(
      recorder.nativeLaunches.size,
      recorder.lifecycleEvents.count { it.eventKind == ReviewLifecycleEventKind.WORKER_LAUNCHED },
    )
  }

  @Test fun `interruption before launch records one durable before-launch classification`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      config(interruptionProbe = { true }),
      recorder,
    ).run(harnessRequest())

    assertFalse(result.lane1.success)
    assertTrue(recorder.nativeLaunches.isEmpty())
    assertEquals(
      DelegatedReviewTerminalClassification.INTERRUPTED_BEFORE_LAUNCH,
      recorder.lifecycleProjections.last().terminalClassification,
    )
    assertEquals(1, recorder.lifecycleEvents.count { it.eventKind == ReviewLifecycleEventKind.TERMINAL_CANCELLED })
  }

  @Test fun `interruption between waves cancels only the unlaunched wave`() {
    val recorder = ReviewRecorder()
    var probeCalls = 0
    reviewHarness(
      config(
        diff = diffForPaths("src/Repo.kt"),
        interruptionProbe = { ++probeCalls >= 3 },
      ).let { harnessConfig ->
        harnessConfig.copy(
          manifests = listOf(
            reviewPack(
              "kotlin",
              (1..8).map { "area-$it" },
              routingSignals = listOf("*.kt", "*.md"),
            ),
          ),
        )
      },
      recorder,
    ).run(harnessRequest())

    assertEquals(
      DelegatedReviewTerminalClassification.INTERRUPTED_BETWEEN_WAVES,
      recorder.lifecycleProjections.last().terminalClassification,
    )
    assertEquals(1, recorder.lifecycleProjections.last().actualWaveCount)
    assertTrue(recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.WORKER_CANCELLED })
  }

  @Test fun `interrupted coordinator closes the durable worker lifecycle before rethrowing`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config { throw InterruptedException("coordinator interrupted") },
      recorder,
    )

    val result = runner.run(harnessRequest())
    Thread.interrupted()

    assertTrue(result.lane1.success.not())
    assertTrue(
      recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.WORKER_CANCELLED },
      "An interrupted blocking execution must not leave only a durable WORKER_RUNNING event.",
    )
    assertTrue(recorder.lifecycleEvents.any { it.eventKind == ReviewLifecycleEventKind.TERMINAL_CANCELLED })
  }

  @Test fun `replaying a terminal lifecycle does not append conflicting evidence`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(config(), recorder)
    val request = harnessRequest()

    runner.run(request)
    val firstEvents = recorder.lifecycleEvents.toList()
    runner.run(request)

    assertEquals(firstEvents, recorder.lifecycleEvents)
    assertEquals(
      1,
      recorder.lifecycleEvents.count {
        it.eventKind == ReviewLifecycleEventKind.TERMINAL_COMPLETED
      },
    )
  }

  @Test fun `recovery reuses durable aggregation completion when terminal persistence was interrupted`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(config(), recorder)
    val request = harnessRequest(reviewRunId = "review-recovery-terminal")

    runner.run(request)
    val persistedAggregation = recorder.lifecycleEvents.single {
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED
    }
    recorder.lifecycleEvents.removeAll { it.eventKind == ReviewLifecycleEventKind.TERMINAL_COMPLETED }

    val result = reviewHarness(config(), recorder).run(request)

    assertTrue(result.lane1.success && result.lane2.success)
    assertEquals(
      1,
      recorder.lifecycleEvents.count { it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED },
    )
    val terminal = recorder.lifecycleEvents.single { it.eventKind == ReviewLifecycleEventKind.TERMINAL_COMPLETED }
    assertEquals(persistedAggregation.terminalCompletion, terminal.terminalCompletion)
  }

  @Test fun `excessive lane output terminates only the affected lane with a typed outcome`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config(
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneResultBytes = 512),
      ) { request ->
        if (request.broker.accounting().lane.endsWith("security")) {
          RecordedWorkerResponse(stdout = "x".repeat(4_096))
        } else {
          RecordedWorkerResponse(stdout = finding("src/Repo.kt", specialist = request.broker.accounting().lane))
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
        if (request.broker.accounting().lane.endsWith("testing")) {
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

    val summary = assertNotNull(
      runner.run(
        harnessRequest(
          prelaunchExpansions = assignedExpansion("src/Repo.kt"),
        ),
      ).accountingSummary,
    )

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

    val summary = assertNotNull(
      runner.run(
        harnessRequest(
          prelaunchExpansions = assignedExpansion("src/Repo.kt") + assignedExpansion("src/Other.kt"),
        ),
      ).accountingSummary,
    )

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
        if (request.broker.accounting().lane.endsWith("architecture")) {
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
    deadlinePolicy: DelegatedReviewDeadlinePolicy = DelegatedReviewDeadlinePolicy.DEFAULT,
    monotonicNowNanos: () -> Long = System::nanoTime,
    interruptionProbe: () -> Boolean = { false },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", areas, routingSignals = listOf("*.kt", "*.md"))),
    diff = diff,
    response = response,
    budget = budget,
    preflight = preflight,
    delegatedReviewDeadlinePolicy = deadlinePolicy,
    monotonicNowNanos = monotonicNowNanos,
    interruptionProbe = interruptionProbe,
  )

  private fun assignedExpansion(path: String) = listOf(
    ReviewPrelaunchExpansion(
      lane = "parallel-code-review",
      path = path,
      reachabilityReason = "The test explicitly exercises an assigned complete-file expansion.",
    ),
  )
}
