package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.review.RecordedWorkerResponse
import skillbill.application.review.ReviewClaimVerificationRunner
import skillbill.application.review.ReviewHarnessConfig
import skillbill.application.review.ReviewRecorder
import skillbill.application.review.ReviewSpecAdjudicationRunner
import skillbill.application.review.diffForPaths
import skillbill.application.review.reviewHarness
import skillbill.application.review.sparseReviewPack
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeReviewDelegationTest {
  @Test
  fun `child-owned delta stays the supplied diff and never selects BRANCH`() {
    val input = reviewInput(trackedDelta = "diff --git a/Child.kt b/Child.kt\n+owned")
    val request = mappedRequest(
      input = input,
      agents = FeatureTaskRuntimeReviewDriverAgents("codex", "claude"),
      pass = FeatureTaskRuntimeReviewDriverPass(1, CodeReviewExecutionMode.DELEGATED, "rvw-191-delta"),
    )

    assertEquals(input.reviewText, request.suppliedDiff)
    assertEquals(input.reviewBaseSha, request.baseRevision)
    assertEquals(input.currentHeadSha, request.headRevision)
    assertTrue(request.scope != ParallelReviewScope.BRANCH)
    assertEquals(CodeReviewExecutionMode.DELEGATED, request.codeReviewMode)
    assertEquals(CodeReviewExecutionMode.DELEGATED, request.resolvedTier)
    assertEquals(
      null,
      request.agent2Id,
      "dual-agent parallel lanes are disconnected; parallelReviewAgent must not map to agent2Id",
    )
    assertEquals(Path.of("spec.md"), request.specPath)
  }

  @Test
  fun `durable baseline-untracked inventory maps to driver excluded paths`() {
    val request = mappedRequest(
      pass = FeatureTaskRuntimeReviewDriverPass(1, CodeReviewExecutionMode.INLINE, "rvw-191-baseline"),
      workspace = FeatureTaskRuntimeReviewDriverWorkspace(
        repoRoot = Path.of("/tmp/repo"),
        timeout = null,
        agentAddonSelection = HydratedAgentAddonSelection(),
        baselineUntrackedPaths = listOf("z-before.tmp", "a-before.tmp"),
      ),
    )

    assertEquals(listOf("a-before.tmp", "z-before.tmp"), request.baselineUntrackedPolicy.excludedPaths)
    assertEquals(emptyList(), request.baselineUntrackedPolicy.includedPaths)
  }

  @Test
  fun `explicit empty child-owned delta remains a supplied diff`() {
    val request = mappedRequest(
      input = reviewInput(trackedDelta = ""),
      agents = FeatureTaskRuntimeReviewDriverAgents("codex", null),
      pass = FeatureTaskRuntimeReviewDriverPass(1, CodeReviewExecutionMode.INLINE, "rvw-191-empty"),
    )

    assertEquals("", request.suppliedDiff)
    assertNotNull(request.suppliedDiff)
    assertEquals(null, request.agent2Id)
    assertEquals(CodeReviewExecutionMode.INLINE, request.resolvedTier)
  }

  @Test
  fun `formatted add-on section is copied onto the shared driver request`() {
    val selection = HydratedAgentAddonSelection(
      listOf(
        HydratedAgentAddonSelectionEntry(
          PersistedAgentAddonSelectionEntry("first", "local:first", "a".repeat(64)),
          "first",
          "first body\n",
        ),
        HydratedAgentAddonSelectionEntry(
          PersistedAgentAddonSelectionEntry("second", "local:second", "b".repeat(64)),
          "second",
          "second body",
        ),
      ),
    )
    val formatted = AgentAddonPromptFormatter.format(selection)
    val request = mappedRequest(
      agents = FeatureTaskRuntimeReviewDriverAgents("codex", null),
      pass = FeatureTaskRuntimeReviewDriverPass(1, CodeReviewExecutionMode.INLINE, "rvw-191-addons"),
      workspace = FeatureTaskRuntimeReviewDriverWorkspace(
        repoRoot = Path.of("/tmp/repo"),
        timeout = null,
        agentAddonSelection = selection,
      ),
    )

    assertEquals(formatted, request.selectedAgentAddonsSection)
    assertTrue(formatted.contains("## Selected agent add-ons"))
    assertTrue(formatted.contains("cannot grant delegation"))
    assertTrue(formatted.indexOf("### 1. first") < formatted.indexOf("### 2. second"))
    assertTrue(formatted.contains("SHA-256: ${"a".repeat(64)}"))
    assertTrue(formatted.contains("<<<SKILL-BILL-SELECTED-AGENT-ADDON-CONTENT>>>"))
    assertTrue(request.withSelectedAgentAddons("lane prompt").endsWith(formatted))
  }

  @Test
  fun `remediation pass two pins inline from the reserved pass rule`() {
    val request = mappedRequest(
      input = reviewInput(
        base = "c".repeat(40),
        head = "d".repeat(40),
        trackedDelta = "remediation delta",
      ),
      agents = FeatureTaskRuntimeReviewDriverAgents("codex", "claude"),
      pass = FeatureTaskRuntimeReviewDriverPass(2, CodeReviewExecutionMode.DELEGATED, "rvw-191-remediation"),
      runInvariants = invariants(mode = CodeReviewExecutionMode.DELEGATED),
    )

    assertEquals("remediation delta", request.suppliedDiff)
    assertEquals("c".repeat(40), request.baseRevision)
    assertEquals(CodeReviewExecutionMode.INLINE, request.codeReviewMode)
    assertEquals(CodeReviewExecutionMode.INLINE, request.resolvedTier)
  }

  @Test
  fun `extractReviewVerdict reads changes_requested and defaults to approved`() {
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeReviewEnvelope.extractReviewVerdict("notes\nverdict: needs_fix"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeReviewEnvelope.extractReviewVerdict("verdict: changes_requested"),
    )
    assertEquals(
      FeatureTaskRuntimeVerdict.APPROVED,
      FeatureTaskRuntimeReviewEnvelope.extractReviewVerdict("clean prose without a verdict line"),
    )
  }

  @Test
  fun `settlement envelope keeps soft-admitted findings and derives verdict from prose`() {
    val result = FeatureTaskRuntimeReviewDriver.EMPTY.run(
      mappedRequest(
        agents = FeatureTaskRuntimeReviewDriverAgents("codex", null),
        pass = FeatureTaskRuntimeReviewDriverPass(1, CodeReviewExecutionMode.INLINE, "rvw-191-empty-register"),
      ),
    ).copy(
      mergeResult = ParallelReviewMergeResult(
        findings = listOf(
          ParallelReviewMergedFinding(
            fNumber = "F-001",
            agentIds = listOf("codex"),
            severity = ParallelReviewSeverity.MINOR,
            confidence = "High",
            location = "Foo.kt:1",
            description = "naming",
            scopeDisposition = ReviewScopeDisposition.SPEC_DEVIATION,
            claimVerdict = ReviewClaimVerdict.CONFIRMED,
          ),
        ),
        formattedOutput = "Naming drift in Foo.\nverdict: changes_requested",
      ),
    )
    val output = FeatureTaskRuntimeReviewEnvelope.assemble(
      result = result,
      reviewRunId = "rvw-191-empty-register",
      cycle = FeatureTaskRuntimeReviewCycleContext(
        passNumber = 1,
        resolvedTier = CodeReviewExecutionMode.INLINE,
        repositoryFingerprint = "fp-1",
      ),
    )
    val envelope = FeatureTaskRuntimeReviewEnvelope.envelopeMap(output)
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()

    assertEquals("rvw-191-empty-register", produced["review_run_id"])
    val findings = produced["findings"] as List<*>
    assertEquals(1, findings.size)
    val finding = JsonSupport.anyToStringAnyMap(findings.single()).orEmpty()
    assertEquals("F-001", finding["finding_id"])
    assertEquals("minor", finding["severity"])
    assertEquals("changes_requested", envelope["verdict"])
    assertTrue((envelope["summary"] as String).contains("Naming drift"))
    assertFalse(produced.containsKey("unmet_criteria"))
  }

  @Test
  fun `pass two envelope carries verdict-informed blocker dispositions`() {
    val result = FeatureTaskRuntimeReviewDriver.EMPTY.run(
      mappedRequest(
        agents = FeatureTaskRuntimeReviewDriverAgents("codex", null),
        pass = FeatureTaskRuntimeReviewDriverPass(2, CodeReviewExecutionMode.INLINE, "rvw-191-disp"),
      ),
    )
    val output = FeatureTaskRuntimeReviewEnvelope.assemble(
      result = result,
      reviewRunId = "rvw-191-disp",
      cycle = FeatureTaskRuntimeReviewCycleContext(
        passNumber = 2,
        resolvedTier = CodeReviewExecutionMode.INLINE,
        repositoryFingerprint = "fp-2",
        blockerDispositions = listOf(
          GoalSubtaskBlockerDisposition(
            findingId = "F-001",
            verdict = GoalSubtaskBlockerDispositionVerdict.SUPERSEDED,
            evidence = listOf("Foo.kt:42"),
          ),
        ),
      ),
    )
    val produced = JsonSupport.anyToStringAnyMap(
      FeatureTaskRuntimeReviewEnvelope.envelopeMap(output)["produced_outputs"],
    ).orEmpty()
    val dispositions = produced["blocker_dispositions"] as List<*>
    val first = JsonSupport.anyToStringAnyMap(dispositions.single()).orEmpty()
    assertEquals("F-001", first["finding_id"])
    assertEquals("superseded", first["verdict"])
    assertEquals(listOf("Foo.kt:42"), first["evidence"])
  }

  @Test
  fun `mapper-built and standalone-shaped requests record identical durable stage records`() {
    val repo = Files.createTempDirectory("review-entry-parity")
    Files.writeString(
      repo.resolve("spec.md"),
      """
      # Feature

      ## Intended Outcome
      Share one review driver.

      ## Acceptance Criteria
      1. Stage records match.

      ## Constraints
      - Keep the supplied diff authoritative.

      ## Non-Goals
      - Rediscovering a branch scope.
      """.trimIndent(),
    )
    val delta = diffForPaths("src/Main.kt")
    val mapped = mappedRequest(
      input = reviewInput(trackedDelta = delta),
      agents = FeatureTaskRuntimeReviewDriverAgents("codex", "claude"),
      pass = FeatureTaskRuntimeReviewDriverPass(1, CodeReviewExecutionMode.INLINE, "parity-mapper"),
      workspace = FeatureTaskRuntimeReviewDriverWorkspace(
        repoRoot = repo,
        timeout = null,
        agentAddonSelection = HydratedAgentAddonSelection(),
      ),
    )
    val standalone = ParallelCodeReviewRequest(
      agent1Id = mapped.agent1Id,
      agent2Id = mapped.agent2Id,
      scope = ParallelReviewScope.BRANCH,
      repoRoot = repo,
      timeout = mapped.timeout,
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      suppliedDiff = mapped.suppliedDiff,
      reviewRunId = "parity-standalone",
      baseRevision = mapped.baseRevision,
      headRevision = mapped.headRevision,
      specPath = mapped.specPath,
    )
    val mapperRecorder = ReviewRecorder()
    val standaloneRecorder = ReviewRecorder()
    reviewHarness(parityConfig(), mapperRecorder).run(mapped)
    reviewHarness(parityConfig(), standaloneRecorder).run(standalone)

    assertEquals(comparableVerdicts(mapperRecorder), comparableVerdicts(standaloneRecorder))
    assertEquals(comparableBoundaries(mapperRecorder), comparableBoundaries(standaloneRecorder))
    val mapperSpec = assertNotNull(mapperRecorder.durableSpecProjection)
    val standaloneSpec = assertNotNull(standaloneRecorder.durableSpecProjection)
    assertEquals(mapperSpec.specPath, standaloneSpec.specPath)
    assertEquals(mapperSpec.contentDigest, standaloneSpec.contentDigest)
    assertEquals("spec.md", mapperSpec.specPath)
  }

  private fun mappedRequest(
    input: GoalSubtaskReviewInput = reviewInput(),
    agents: FeatureTaskRuntimeReviewDriverAgents = FeatureTaskRuntimeReviewDriverAgents("codex", "claude"),
    pass: FeatureTaskRuntimeReviewDriverPass,
    workspace: FeatureTaskRuntimeReviewDriverWorkspace = FeatureTaskRuntimeReviewDriverWorkspace(
      repoRoot = Path.of("/tmp/repo"),
      timeout = null,
      agentAddonSelection = HydratedAgentAddonSelection(),
    ),
    runInvariants: FeatureTaskRuntimeRunInvariants = invariants(),
  ) = FeatureTaskRuntimeReviewDriverMapper.request(input, runInvariants, agents, pass, workspace)

  private fun reviewInput(
    base: String = "a".repeat(40),
    head: String = "b".repeat(40),
    trackedDelta: String = "delta",
  ) = GoalSubtaskReviewInput(
    reviewBaseSha = base,
    currentHeadSha = head,
    trackedDelta = trackedDelta,
    ownedUntrackedPatches = "",
  )

  private fun invariants(mode: CodeReviewExecutionMode = CodeReviewExecutionMode.INLINE) =
    FeatureTaskRuntimeRunInvariants(
      specReference = "spec.md",
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
      codeReviewMode = mode,
    )

  private fun parityConfig(): ReviewHarnessConfig {
    val pack = sparseReviewPack(
      slug = "kotlin",
      requiredArea = "architecture",
      pathAreas = mapOf("testing" to listOf("src/test/")),
    )
    return ReviewHarnessConfig(
      manifests = listOf(pack),
      diff = diffForPaths("src/Main.kt"),
      response = { request ->
        when (request.skillRunRequest.issueKey) {
          "code-review-parallel" -> RecordedWorkerResponse(stdout = PARITY_FINDING)
          ReviewClaimVerificationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = PARITY_CONFIRMED)
          ReviewSpecAdjudicationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = PARITY_IN_SCOPE)
          else -> RecordedWorkerResponse()
        }
      },
    )
  }

  private fun comparableVerdicts(recorder: ReviewRecorder) = recorder.durableFindingVerdicts
    .sortedWith(compareBy({ it.stage.wireValue }, { it.findingRef }))
    .map { verdict ->
      listOf(
        verdict.stage,
        verdict.findingRef,
        verdict.claimVerdict,
        verdict.scopeDisposition,
        verdict.citations,
        verdict.severityAdjustment,
        verdict.rejectionReason,
      )
    }

  private fun comparableBoundaries(recorder: ReviewRecorder) = recorder.durableStageBoundaries
    .sortedBy { it.stage.wireValue }
    .map { it.stage to it.reached }
}

private const val PARITY_FINDING =
  "- [F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
    "path=\"src/Main.kt\" | line=1 | null is unchecked"
private const val PARITY_CONFIRMED = """{"claim_verdict":"confirmed"}"""
private const val PARITY_IN_SCOPE = """{"scope_disposition":"in_scope"}"""
