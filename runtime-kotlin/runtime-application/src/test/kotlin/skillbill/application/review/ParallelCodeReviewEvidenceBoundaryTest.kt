package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.REVIEW_EVIDENCE_MAX_REQUESTS
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.scaffold.model.ReviewLaneCondition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewEvidenceBoundaryTest {
  @Test
  fun `preparation delivers a non-primary lane whole-file expansion through the final broker`() {
    for (scope in listOf(ParallelReviewScope.BRANCH, ParallelReviewScope.STAGED, ParallelReviewScope.UNSTAGED)) {
      val recorder = ReviewRecorder()
      val initial = harnessRequest(
        scope = scope,
        reviewRunId = "rvw-236-prelaunch",
        prelaunchExpansions = listOf(
          ReviewPrelaunchExpansion("bill-kotlin-code-review-security", "src/secure/Auth.kt", "inspect direct caller"),
        ),
      )
      val resolver = reviewFileSystemDiffResolver()
      fun git(vararg args: String) = assertNotNull(
        resolver.runProcess(
          listOf("git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false") + args,
          initial.repoRoot,
        ),
      )
      val (base, head) = prepareExpansionRepository(initial, resolver)
      val expected = if (scope == ParallelReviewScope.UNSTAGED) "worktree caller body" else "indexed caller body"
      val request = initial.copy(baseRevision = base, headRevision = head)
      val result = reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(
            reviewPack(
              "kotlin",
              listOf("architecture", "security"),
              routingSignals = listOf("*.kt"),
            ).copy(
              laneConditions = mapOf(
                "architecture" to ReviewLaneCondition(path = listOf("src/core/")),
                "security" to ReviewLaneCondition(path = listOf("src/secure/")),
              ),
            ),
          ),
          diff = when (scope) {
            ParallelReviewScope.STAGED -> git("diff", "--cached")
            ParallelReviewScope.UNSTAGED -> git("diff")
            else -> git("diff", base, head)
          },
          simulateEvidenceReads = false,
          diffResolver = resolver,
          response = { launch ->
            val protocol = launch.skillRunRequest.nativeReviewOperations
              ?: return@ReviewHarnessConfig RecordedWorkerResponse(stdout = "NO_FINDINGS")
            val broker = assertNotNull(launch.skillRunRequest.reviewEvidenceBroker)
            assertExpandedEvidence(protocol, broker, expected)
            RecordedWorkerResponse(stdout = "verdict: approved")
          },
        ),
        recorder,
      ).run(request)
      assertTrue(result.lane1.success)
      assertTrue(assertNotNull(result.lane1.accounting).remainingEvidence.isEmpty())
    }
  }

  @Test
  fun `inline prelaunch expansion authorizes on the single parent broker`() {
    val fixture = prepareChunkedInlineRepository("review-chunked-expansion")
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = chunkedInlineArchitectureSecurityManifests(),
        diff = fixture.git("diff", "--cached"),
        diffResolver = fixture.resolver,
        simulateEvidenceReads = true,
      ),
      fixture.recorder,
    ).run(
      harnessRequest(
        repoRoot = fixture.repoRoot,
        reviewRunId = "rvw-236-chunked-expansion",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        scope = ParallelReviewScope.STAGED,
        prelaunchExpansions = listOf(
          ReviewPrelaunchExpansion("bill-kotlin-code-review-security", fixture.expansionPath, "inspect caller"),
        ),
      ).copy(baseRevision = fixture.revision, headRevision = fixture.revision),
    )
    assertEquals(1, fixture.recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" })
    val expansionChunks = fixture.recorder.parentLaunches
      .filter { it.skillRunRequest.issueKey == "code-review" }
      .count { launch ->
      val broker = assertNotNull(launch.skillRunRequest.reviewEvidenceBroker)
      discoveryHasAuthorizedExpansion(BrokerBackedNativeReviewOperationProtocol(broker))
    }
    assertEquals(1, expansionChunks)
    assertTrue(result.lane1.success)
    assertTrue(assertNotNull(result.coverage).isCleanCoverage)
  }

  @Test
  fun `inline parent stays incomplete when required evidence remains undelivered`() {
    val fixture = prepareChunkedInlineRepository("review-chunked-partial")
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = chunkedInlineArchitectureSecurityManifests(),
        diff = fixture.git("diff", "--cached"),
        diffResolver = fixture.resolver,
        simulateEvidenceReads = false,
        response = { _ ->
          RecordedWorkerResponse(stdout = "verdict: approved")
        },
      ),
      fixture.recorder,
    ).run(
      harnessRequest(
        repoRoot = fixture.repoRoot,
        reviewRunId = "rvw-236-inline-partial",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        scope = ParallelReviewScope.STAGED,
        prelaunchExpansions = listOf(
          ReviewPrelaunchExpansion("bill-kotlin-code-review-security", fixture.expansionPath, "inspect caller"),
        ),
      ).copy(baseRevision = fixture.revision, headRevision = fixture.revision),
    )

    assertEquals(1, fixture.recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" })
    assertFalse(result.lane1.success)
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, accounting.reviewDisposition)
    assertEquals("incomplete", accounting.terminalStatus)
    assertEquals(null, accounting.terminalOutcome)
    assertTrue(accounting.requiredEvidenceUnits > accounting.deliveredEvidenceUnits)
    assertFalse(assertNotNull(result.coverage).isCleanCoverage)
  }


  private fun discoveryHasAuthorizedExpansion(protocol: NativeReviewOperationProtocol): Boolean {
    var cursor: String? = null
    do {
      val page = protocol.discover(ReviewEvidenceDiscoveryRequest(cursor))
      if (page.entries.any { it.expansionId != null }) return true
      cursor = page.nextCursor
    } while (cursor != null)
    return false
  }

  private fun prepareExpansionRepository(
    initial: ParallelCodeReviewRequest,
    resolver: DiffResolverPort,
  ): Pair<String, String> {
    val file = initial.repoRoot.resolve("src/secure/Auth.kt")
    Files.createDirectories(file.parent)
    fun git(vararg args: String) = assertNotNull(
      resolver.runProcess(
        listOf("git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false") + args,
        initial.repoRoot,
      ),
    )
    git("init", "--quiet")
    val core = initial.repoRoot.resolve("src/core/Core.kt")
    Files.createDirectories(core.parent)
    Files.writeString(core, "committed core")
    Files.writeString(file, "committed caller body")
    Files.createSymbolicLink(initial.repoRoot.resolve("unrelated-link"), file)
    val submodule = initial.repoRoot.resolve("unrelated-submodule")
    Files.createDirectories(submodule)
    git("-C", submodule.toString(), "init", "--quiet")
    git(
      "-C", submodule.toString(), "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
      "commit", "--allow-empty", "--quiet", "-m", "submodule",
    )
    git("add", ".")
    git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "base")
    val base = git("rev-parse", "HEAD").trim()
    Files.writeString(core, "indexed core")
    Files.writeString(file, "indexed caller body")
    git("add", ".")
    if (initial.scope == ParallelReviewScope.BRANCH) {
      git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "change")
    }
    val head = git("rev-parse", "HEAD").trim()
    Files.writeString(core, "worktree core")
    Files.writeString(file, "worktree caller body")
    return base to head
  }

  private fun assertExpandedEvidence(
    protocol: NativeReviewOperationProtocol,
    broker: ReviewEvidenceBroker,
    expected: String,
  ) {
    val lane = broker.accounting().lane
    val page = protocol.discover(ReviewEvidenceDiscoveryRequest())
    val expansion = page.entries.single { it.expansionId != null }
    assertTrue(expansion.owners.single().lane.endsWith("bill-kotlin-code-review-security"))
    assertNotEquals(lane, expansion.owners.single().lane)
    val authorization = assertNotNull(protocol.expansionById(assertNotNull(expansion.expansionId)))
    assertEquals(page.assignmentDigest, authorization.assignmentDigest)
    assertNotEquals(page.assignmentDigest, expansion.owners.single().assignmentDigest)
    page.entries.forEach { entry ->
      val response = protocol.read(
        ReviewEvidenceBatchRequest.of(
          ReviewEvidenceRequest(
            lane,
            entry.path,
            selector = entry.selector,
            authorizedExpansion = entry.expansionId?.let(protocol::expansionById),
            reachabilityReason = entry.expansionId?.let(protocol::expansionById)?.reachabilityReason,
          ),
        ),
      )
      if (entry == expansion) assertEquals(expected, response.results.single().content)
      protocol.confirmDelivery(assertNotNull(response.deliveryReceipt))
    }
  }

  @Test
  fun `preparation rejects a tracked unavailable entry replaced between checkpoint snapshots`() {
    val request = harnessRequest(scope = ParallelReviewScope.UNSTAGED)
    val resolver = reviewFileSystemDiffResolver()
    fun git(vararg args: String) = assertNotNull(
      resolver.runProcess(
        listOf("git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false") + args,
        request.repoRoot,
      ),
    )
    git("init", "--quiet")
    Files.writeString(request.repoRoot.resolve("A.kt"), "old")
    val link = request.repoRoot.resolve("unrelated-link")
    Files.createSymbolicLink(link, Path.of("missing-target"))
    git("add", ".")
    git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "base")
    val head = git("rev-parse", "HEAD").trim()
    Files.writeString(request.repoRoot.resolve("A.kt"), "changed")
    var snapshots = 0
    val changingResolver = object : DiffResolverPort by resolver {
      override fun reviewWorktreeFileIdentities(
        root: Path,
        paths: List<String>,
      ): Map<
        String,
        ReviewCheckpointFileIdentity,
        > {
        val identities = resolver.reviewWorktreeFileIdentities(root, paths)
        if (snapshots++ == 0) {
          Files.delete(link)
          Files.writeString(link, "replacement")
        }
        return identities
      }
    }
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("security"), routingSignals = listOf("*.kt"))),
        diff = git("diff"),
        diffResolver = changingResolver,
      ),
      recorder,
    )
    val failure = assertFailsWith<DiffResolutionException> {
      runner.run(request.copy(baseRevision = head, headRevision = head))
    }
    assertTrue(failure.message.orEmpty().contains("checkpoint changed"))
    assertTrue(recorder.parentLaunches.isEmpty())
  }

  @Test
  fun `approved worker prose settles only after every shared and unique obligation is delivered`() {
    for (scenario in listOf("none", "refused", "partial", "full", "recovered")) {
      val recorder = ReviewRecorder()
      val complete = scenario in setOf("full", "recovered")
      val result = reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(
            reviewPack(
              "kotlin",
              listOf("architecture", "security"),
              routingSignals = listOf("*.kt"),
            ).copy(
              laneConditions = mapOf(
                "architecture" to ReviewLaneCondition(path = listOf("src/shared/", "src/core/")),
                "security" to ReviewLaneCondition(path = listOf("src/shared/", "src/secure/")),
              ),
            ),
          ),
          diff = diffForPaths("src/shared/Shared.kt", "src/core/Core.kt", "src/secure/Auth.kt"),
          simulateEvidenceReads = false,
          response = { request ->
            val protocol = request.skillRunRequest.nativeReviewOperations
              ?: return@ReviewHarnessConfig RecordedWorkerResponse(stdout = "NO_FINDINGS")
            val lane = assertNotNull(request.skillRunRequest.reviewEvidenceBroker).accounting().lane
            deliverScenarioEvidence(protocol, lane, scenario)
            RecordedWorkerResponse(stdout = "verdict: approved")
          },
        ),
        recorder,
      ).run(
        harnessRequest(reviewRunId = "rvw-236-$scenario", codeReviewMode = CodeReviewExecutionMode.INLINE),
      )
      assertEquals(complete, result.lane1.success, scenario)
      assertEquals(complete, assertNotNull(result.coverage).isCleanCoverage, scenario)
      assertTrue(recorder.durableLanes.isNotEmpty())
      assertTrue(
        recorder.durableLanes.all { it.reviewDisposition == if (complete) "complete" else "incomplete" },
        scenario,
      )
      val accounting = assertNotNull(result.lane1.accounting)
      assertEquals(4, accounting.requiredEvidenceUnits)
      assertEquals(
        if (complete) {
          4
        } else if (scenario == "partial") {
          2
        } else {
          0
        },
        accounting.deliveredEvidenceUnits,
      )
      assertTrue(recorder.savedAccounting.isNotEmpty())
      if (scenario == "recovered") assertTrue(accounting.refusedOperationCount > 0)
    }
  }

  @Test
  fun `terminal broker failure after full delivery keeps every lane resumable`() {
    val recorder = ReviewRecorder()
    var exhaustBudget = true
    val config = ReviewHarnessConfig(
      manifests = listOf(reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt"))),
      diff = diffForPaths("src/shared/Shared.kt"),
      simulateEvidenceReads = false,
      response = { launch ->
        val protocol = launch.skillRunRequest.nativeReviewOperations
          ?: return@ReviewHarnessConfig RecordedWorkerResponse(stdout = "NO_FINDINGS")
        val broker = assertNotNull(launch.skillRunRequest.reviewEvidenceBroker)
        val lane = broker.accounting().lane
        deliverScenarioEvidence(protocol, lane, "full")
        if (exhaustBudget) {
          repeat(REVIEW_EVIDENCE_MAX_REQUESTS + 1) {
            protocol.read(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest(lane, "Outside.kt")))
          }
        }
        RecordedWorkerResponse(stdout = "verdict: approved")
      },
    )
    val request = harnessRequest(
      reviewRunId = "rvw-236-terminal-resume",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    val failed = reviewHarness(config, recorder).run(request)
    val accounting = assertNotNull(failed.lane1.accounting)
    assertEquals("evidence_requests", assertNotNull(accounting.terminalOutcome).budgetKind)
    assertEquals(2, accounting.requiredEvidenceUnits)
    assertEquals(accounting.requiredEvidenceUnits, accounting.deliveredEvidenceUnits)
    assertTrue(accounting.remainingEvidence.isEmpty())
    assertFalse(failed.lane1.success)
    assertEquals(2, assertNotNull(failed.coverage).incompleteLanes.size)
    assertEquals(2, recorder.durableLanes.size)
    assertTrue(recorder.durableLanes.all { it.reviewDisposition == "incomplete" })
    val launchesBeforeResume = recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" }

    exhaustBudget = false
    val resumed = reviewHarness(config, recorder).run(request)
    assertEquals(
      launchesBeforeResume + 1,
      recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" },
    )
    assertTrue(resumed.lane1.success)
    assertTrue(assertNotNull(resumed.coverage).isCleanCoverage)
    assertTrue(recorder.durableLanes.all { it.reviewDisposition == "complete" })
  }

  private fun deliverScenarioEvidence(protocol: NativeReviewOperationProtocol, lane: String, scenario: String) {
    if (scenario in setOf("refused", "recovered")) {
      protocol.read(ReviewEvidenceBatchRequest.of(ReviewEvidenceRequest(lane, "Outside.kt")))
    }
    val entries = protocol.discover(ReviewEvidenceDiscoveryRequest()).entries
    val reads = when (scenario) {
      "partial" -> entries.filter { it.path == "src/shared/Shared.kt" }
      "full", "recovered" -> entries
      else -> emptyList()
    }
    reads.forEach { entry ->
      val response = protocol.read(
        ReviewEvidenceBatchRequest.of(
          ReviewEvidenceRequest(lane, entry.path, selector = entry.selector),
        ),
      )
      protocol.confirmDelivery(assertNotNull(response.deliveryReceipt))
    }
  }

  @Test
  fun `complete governed delivery clears an unreviewable launch bundle`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForChanges("src/Repo.kt" to "x".repeat(50_000)),
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneLaunchBytes = 40_000),
        simulateEvidenceReads = false,
        response = { launch ->
          val protocol = launch.skillRunRequest.nativeReviewOperations
            ?: return@ReviewHarnessConfig RecordedWorkerResponse(stdout = "verdict: approved")
          val broker = assertNotNull(launch.skillRunRequest.reviewEvidenceBroker)
          val lane = broker.accounting().lane
          deliverScenarioEvidence(protocol, lane, "full")
          RecordedWorkerResponse(stdout = "verdict: approved")
        },
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-236-unreviewable-launch", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertTrue(result.lane1.success)
    assertTrue(assertNotNull(result.coverage).isCleanCoverage)
    assertTrue(assertNotNull(result.lane1.accounting).remainingEvidence.isEmpty())
  }

  @Test
  fun `successful governed run with no broker refusal stays complete for lane evidence bytes`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-201-broker-clean", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertTrue(result.lane1.success)
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals(ReviewLaneReviewDisposition.COMPLETE, accounting.reviewDisposition)
    assertTrue(accounting.unreviewedUnits.isEmpty())
    assertEquals(null, accounting.budgetDimension)
    assertTrue(assertNotNull(result.coverage).isCleanCoverage)
  }
}
