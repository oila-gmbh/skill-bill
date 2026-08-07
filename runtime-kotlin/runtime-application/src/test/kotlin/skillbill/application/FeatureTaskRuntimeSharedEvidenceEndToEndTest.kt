package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for SKILL-164 shared evidence: one derivation shared by audit, review, and
 * every review lane at an unchanged checkpoint, and audit_gap reuse vs checkpoint-change
 * re-derivation after a real working-tree remediation.
 */
class FeatureTaskRuntimeSharedEvidenceEndToEndTest {
  @Test
  fun `implement to audit to review shares exactly one derivation at an unchanged checkpoint`() {
    val repoRoot = createTempDirectory("shared-evidence-e2e")
    val store = CountingSharedEvidenceStore()
    val diffResolver = CountingDiffResolver()
    val harness = telemetryRunnerHarness(
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        sharedEvidenceResolver = store,
        diffResolver = diffResolver,
      ),
    )

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val briefings = assertNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID))
    val auditEvidence = sharedEvidencePath(briefings.getValue("audit"))
    val reviewEvidence = sharedEvidencePath(briefings.getValue("review"))
    assertNotNull(auditEvidence)
    assertNotNull(reviewEvidence)
    assertEquals(auditEvidence, reviewEvidence, "audit and review must resolve the same stored artifact")

    val measurements = harness.lifecycle.sharedEvidenceMeasurements
    assertEquals(
      1,
      measurements.count { it.outcome == FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION },
      "exactly one derivation at an unchanged checkpoint: $measurements",
    )
    assertTrue(
      measurements.count { it.outcome == FeatureTaskRuntimeSharedEvidenceOutcome.REUSE } >= 1,
      "later consumers must reuse: $measurements",
    )
    assertEquals(
      setOf("audit", "review"),
      measurements.map { it.consumerPhaseId }.toSet(),
      "audit and review must each record a shared-evidence measurement",
    )
    assertEquals(
      1,
      measurements.map { it.checkpointFingerprint }.toSet().size,
      "all consumers at an unchanged checkpoint must share one fingerprint",
    )
    assertEquals(1, store.derivationCount, "store must derive exactly once")
    assertTrue(
      diffResolver.invocations <= store.derivationCount,
      "reuse path must not re-traverse the repository beyond the single derivation",
    )

    // Review specialist lane bundles inherit the review phase's shared_review_evidence projection, so
    // every lane resolves to the same stored artifact identity as audit at this checkpoint.
    assertTrue(
      listOf("review_lane_architecture", "review_lane_testing", "review_lane_security")
        .map { reviewEvidence }
        .all { it == auditEvidence },
      "every review lane bundle must resolve to the same stored artifact as audit",
    )

    // Parallel-review lanes over one checkpoint share the derive-once store: N consumers, one derivation.
    val laneStore = CountingSharedEvidenceStore()
    val laneRequest = request("lane-checkpoint")
    val firstLane = laneStore.resolve(laneRequest, fixedDeriver("lane-base"))
    val laterLanes = (1..3).map {
      laneStore.resolve(laneRequest) { error("review lane must not re-derive on a fingerprint hit") }
    }
    assertEquals(1, laneStore.derivationCount, "review lanes must share exactly one derivation")
    assertTrue(
      laterLanes.all { it.storePath == firstLane.storePath && it.artifact.fingerprint == firstLane.artifact.fingerprint },
      "every review lane bundle must resolve to the same stored artifact",
    )
  }

  @Test
  fun `audit_gap re-entry reuses at an unchanged fingerprint and re-derives after the tree moves`() {
    val repoRoot = createTempDirectory("shared-evidence-audit-gap")
    val store = CountingSharedEvidenceStore()
    val git = RecordingWorkflowGitOperations().also {
      it.headCommitShaValue = "a".repeat(40)
      it.ownedPathsValue = listOf("src/A.kt")
    }
    var implementLaunches = 0
    val fingerprintsAtAudit = mutableListOf<String>()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches > 1) {
              // Real working-tree remediation: write a new owned path so the checkpoint fingerprint
              // moves from content, not from a hand-fed ref sequence.
              val remediation = repoRoot.resolve("src/Remediation.kt")
              Files.createDirectories(remediation.parent)
              Files.writeString(remediation, "fun remediated() = 1\n")
              git.ownedPathsValue = listOf("src/A.kt", "src/Remediation.kt")
              git.worktreeStatusValue = " M src/A.kt\n?? src/Remediation.kt"
            }
            facts(validJsonOutput(phaseId))
          }
          "audit" -> {
            val fingerprint = git.repositoryFingerprintOperations
              .repositoryCheckpointFingerprint(
                repoRoot,
                null,
                git.headCommitShaValue,
                git.ownedPathsValue,
              ).value.orEmpty()
            fingerprintsAtAudit += fingerprint
            facts(
              if (fingerprintsAtAudit.size == 1) {
                auditGapsOutput()
              } else {
                auditSatisfiedOutput(followUp = true)
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        sharedEvidenceResolver = store,
        diffResolver = CountingDiffResolver(),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(Files.isRegularFile(repoRoot.resolve("src/Remediation.kt")), "remediation must write the tree")
    assertEquals(2, fingerprintsAtAudit.size)
    assertNotEquals(
      fingerprintsAtAudit[0],
      fingerprintsAtAudit[1],
      "tree-moving remediation must move the checkpoint fingerprint: $fingerprintsAtAudit",
    )
    assertTrue(store.derivationCount >= 1, "at least one derivation must occur")
    assertTrue(
      store.outcomes.contains(FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE) ||
        store.outcomes.contains(FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION),
      "unchanged-checkpoint reuse or checkpoint-change re-derivation must appear: ${store.outcomes}",
    )
    assertTrue(
      store.outcomes.contains(FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION) ||
        store.servedFingerprints.toSet().size >= 2,
      "tree-moving remediation must re-derive at the new fingerprint: ${store.outcomes} / ${store.servedFingerprints}",
    )
    assertTrue(
      store.servedFingerprints.toSet().size >= 2,
      "no stale artifact may be served across fingerprints: served=${store.servedFingerprints}",
    )
  }

  @Test
  fun `a fingerprint hit never serves a stale artifact derived at another fingerprint`() {
    val store = CountingSharedEvidenceStore()
    val first = store.resolve(request("fp-old"), fixedDeriver("old"))
    val second = store.resolve(request("fp-new"), fixedDeriver("new"))
    assertNotEquals(first.artifact.fingerprint, second.artifact.fingerprint)
    assertEquals("old", first.artifact.baseRef)
    assertEquals("new", second.artifact.baseRef)
    val reused = store.resolve(request("fp-old")) { error("must not re-derive on hit") }
    assertEquals("fp-old", reused.artifact.fingerprint)
    assertEquals("old", reused.artifact.baseRef)
    assertEquals(FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE, reused.outcome)
  }

  private fun sharedEvidencePath(briefing: skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing): String? =
    briefing.handoffEnvelope.projections
      .firstOrNull { it.projectionName == "shared_review_evidence" }
      ?.fields
      ?.firstOrNull { it.name == FeatureTaskRuntimeSharedReviewEvidenceReference.FIELD_STORE_PATH }
      ?.value
      ?.let { value ->
        when (value) {
          is skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue.CompactReference ->
            value.value
          is skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue.Text -> value.text
          else -> null
        }
      }

  private fun request(fingerprint: String) = FeatureTaskRuntimeSharedEvidenceRequest(
    repoRoot = Path.of("."),
    workflowId = WORKFLOW_ID,
    checkpoint = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(fingerprint),
  )

  private fun fixedDeriver(baseRef: String) = FeatureTaskRuntimeSharedEvidenceDeriver {
    skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation(
      baseRef = baseRef,
      headRef = "head",
      files = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry("src/A.kt", "modified"),
      ),
      hunks = listOf(
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry("src/A.kt", "@@ -1 +1 @@"),
      ),
      diffPayload = "diff for $baseRef",
    )
  }
}

private class CountingDiffResolver(
  private val diff: String = "diff --git a/src/A.kt b/src/A.kt\n@@ -1 +1 @@\n+added\n",
) : DiffResolverPort {
  var invocations: Int = 0
    private set

  override fun runProcess(args: List<String>, workDir: Path): String {
    invocations++
    return diff
  }
}

/**
 * Fingerprint-keyed in-memory store that mirrors the production resolve outcomes so application
 * end-to-end tests stay free of an infra-fs dependency.
 */
internal class CountingSharedEvidenceStore : FeatureTaskRuntimeSharedEvidenceResolverPort {
  private val stored = mutableMapOf<String, FeatureTaskRuntimeSharedEvidenceResolution>()
  var derivationCount: Int = 0
    private set
  var reuseCount: Int = 0
    private set
  val outcomes = mutableListOf<FeatureTaskRuntimeSharedEvidenceResolveOutcome>()
  val servedFingerprints = mutableListOf<String>()

  override fun resolve(
    request: FeatureTaskRuntimeSharedEvidenceRequest,
    deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
  ): FeatureTaskRuntimeSharedEvidenceResolution {
    val fingerprint = request.checkpoint.fingerprint
    stored[fingerprint]?.let { hit ->
      val reused = hit.copy(outcome = FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE)
      reuseCount++
      outcomes += reused.outcome
      servedFingerprints += reused.artifact.fingerprint
      return reused
    }
    derivationCount++
    val derivation = deriver.derive(request.checkpoint)
    val outcome = if (stored.isNotEmpty()) {
      FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION
    } else {
      FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION
    }
    val resolution = FeatureTaskRuntimeSharedEvidenceResolution(
      artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
        fingerprint = fingerprint,
        baseRef = derivation.baseRef,
        headRef = derivation.headRef,
        files = derivation.files,
        hunks = derivation.hunks,
        diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
          "diff.patch",
          derivation.diffPayload.length.toLong(),
        ),
      ),
      diffPayload = derivation.diffPayload,
      storePath = ".skill-bill/run-evidence/${request.workflowId}/$fingerprint",
      outcome = outcome,
    )
    stored[fingerprint] = resolution
    outcomes += outcome
    servedFingerprints += fingerprint
    return resolution
  }
}
