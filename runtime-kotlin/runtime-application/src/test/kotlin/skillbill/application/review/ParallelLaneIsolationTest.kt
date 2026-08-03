package skillbill.application.review

import skillbill.contracts.JsonSupport
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewContextBudgetPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two top-level lanes reuse the same optimized flow without sharing state or counting each other. */
class ParallelLaneIsolationTest {
  private val areas = listOf("architecture", "security", "testing")

  @Test fun `each top-level lane crosses every governed boundary exactly once per specialist`() {
    val recorder = ReviewRecorder()

    reviewHarness(config(), recorder).run(harnessRequest())

    assertEquals(1, recorder.diffCommands.count { it.contains("diff") })
    assertEquals(areas.size * 2, recorder.preflightRequests.flatMap { it.assignments }.size)
    assertEquals(areas.sorted(), recorder.rubricResolutions.distinct().sorted().map { it.substringAfterLast('-') })
    assertEquals(areas.size, recorder.rubricResolutions.size, "Rubrics resolve once, not once per lane.")
    assertEquals(
      areas.size * 2,
      recorder.brokerBindings.map { it.assignment.digest }.distinct().size,
      "Each lane binds its own broker per specialist.",
    )
    assertTrue(recorder.evidenceBatches.isEmpty(), "Assigned hunk bodies travel in the prompt, not filesystem reads.")
    assertEquals(areas.size * 2, recorder.nativeLaunches.size)
  }

  @Test fun `no lane recursively invokes parallel review or a baseline orchestrator`() {
    val recorder = ReviewRecorder()

    reviewHarness(config(), recorder).run(harnessRequest())

    assertTrue(recorder.parentLaunches.isEmpty(), "Delegated review never starts an inline parent lane.")
    val allSpecialists = areas.map { "bill-kotlin-code-review-$it" }
    recorder.nativeLaunches.forEach { launch ->
      val envelope = requireNotNull(JsonSupport.parseObjectOrNull(launch.prompt))
        .let(JsonSupport::jsonElementToValue)
        .let { requireNotNull(JsonSupport.anyToStringAnyMap(it)) }
      val worker = requireNotNull(envelope["lane"] as? String).substringAfter(':')
      assertEquals(worker, launch.logicalWorkerName)
      assertTrue(worker in allSpecialists, "Prompt carried an unexpected specialist lane '$worker'.")
      (allSpecialists - worker).forEach { sibling ->
        assertTrue(!launch.prompt.contains(sibling), "Prompt for '$worker' named sibling lane '$sibling'.")
      }
      assertTrue(launch.prompt.contains("assigned_hunk_bodies"), "The prompt must carry its assigned hunk envelope.")
      assertTrue(
        !launch.prompt.contains("bill-kotlin-code-review\n") && !launch.prompt.contains("bill-code-review"),
        "Prompt for '$worker' referenced a baseline orchestrator.",
      )
    }
  }

  @Test fun `lane packets and assignments never leak across the two lanes`() {
    val recorder = ReviewRecorder()

    reviewHarness(config(), recorder).run(harnessRequest())

    val byAgent = recorder.brokerBindings.groupBy { it.assignment.lane.substringBefore(':') }
    assertEquals(setOf("codex", "claude"), byAgent.keys)
    val codexDigests = byAgent.getValue("codex").map { it.assignment.digest }.toSet()
    val claudeDigests = byAgent.getValue("claude").map { it.assignment.digest }.toSet()
    assertEquals(areas.size, codexDigests.size)
    assertEquals(emptySet(), codexDigests intersect claudeDigests, "Assignments are lane-private.")
    recorder.evidenceBatches.forEach { batch ->
      assertTrue(batch.requests.all { it.lane == batch.lane }, "Evidence never crosses lane identity.")
    }
    assertEquals(
      1,
      recorder.brokerBindings.map { it.assignment.packetDigest }.distinct().size,
      "Both lanes read the same authoritative parent packet rather than rebuilding one each.",
    )
  }

  @Test fun `aggregate usage counts each lane once across mixed terminal outcomes`() {
    val recorder = ReviewRecorder()
    val usage = ProviderTokenUsage(600, 200, 60, 10, 660)
    val launchIndex = AtomicInteger()
    val runner = reviewHarness(
      config(budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneResultBytes = 512)) {
        when (launchIndex.getAndIncrement() % areas.size) {
          1 ->
            RecordedWorkerResponse(stdout = "x".repeat(2_048), usage = usage)
          2 -> RecordedWorkerResponse(exitStatus = 3, usage = usage)
          else -> RecordedWorkerResponse(
            stdout = finding("src/Repo.kt"),
            usage = usage,
          )
        }
      },
      recorder,
    )

    val result = runner.run(harnessRequest())
    val summary = assertNotNull(result.accountingSummary)

    val specialists = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(areas.size * 2, specialists.size)
    assertEquals(specialists.size * 600L, summary.aggregateDirectUsage.inputTokens)
    assertEquals(specialists.size * 460L, summary.aggregateDirectUsage.freshTokenApproximation)
    assertEquals(
      summary.aggregateDirectUsage.inputTokens,
      summary.aggregateInclusiveUsage.inputTokens,
      "Descendant usage is never added to an ancestor that already contains it.",
    )
    val agentRoots = summary.lanes.filter { it.children.isNotEmpty() }
    assertEquals(2, agentRoots.size)
    agentRoots.forEach { root ->
      assertEquals(areas.size * 600L, root.inclusiveUsage.inputTokens)
      assertEquals(root.children.sumOf { it.counters.launchBytes }, root.inclusiveCounters.launchBytes)
    }
    assertEquals(setOf("partial_failure"), agentRoots.map { it.terminalOutcome }.toSet())
  }

  @Test fun `a timed out specialist fails only its own lane and is still accounted`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config {
        if (it.broker.accounting().lane.endsWith("security")) {
          RecordedWorkerResponse(exitStatus = null, timedOut = true)
        } else {
          RecordedWorkerResponse(stdout = finding("src/Repo.kt"))
        }
      },
      recorder,
    )

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val outcomes = summary.lanes.filter { it.children.isEmpty() }.associate { it.lane to it.terminalOutcome }
    assertTrue(outcomes.filterKeys { it.endsWith("security") }.values.all { it == "timeout" })
    assertTrue(outcomes.filterKeys { it.endsWith("architecture") }.values.all { it == "completed" })
  }

  @Test fun `merged findings stay deterministic and keep their lane provenance`() {
    fun merged(): String {
      val recorder = ReviewRecorder()
      return reviewHarness(
        config {
          RecordedWorkerResponse(stdout = finding("src/Repo.kt"))
        },
        recorder,
      ).run(harnessRequest()).mergeResult.formattedOutput
    }

    val first = merged()

    assertEquals(first, merged())
    areas.forEach { area ->
      assertTrue(first.contains("bill-kotlin-code-review-$area"), "Merged register lost provenance for '$area'.")
    }
  }

  private fun config(
    budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
    response: (skillbill.ports.review.model.NativeReviewWorkerRequest) -> RecordedWorkerResponse = {
      RecordedWorkerResponse()
    },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", areas, routingSignals = listOf("*.kt"))),
    diff = diffForPaths("src/Repo.kt"),
    response = response,
    budget = budget,
  )
}
