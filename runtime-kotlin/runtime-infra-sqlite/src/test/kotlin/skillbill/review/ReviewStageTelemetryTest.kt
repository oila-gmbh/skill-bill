package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.SQLiteReviewRunCompletenessRepository
import skillbill.infrastructure.sqlite.review.ReviewFinishedPayloadBuildRequest
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.review.TriageRuntime
import skillbill.infrastructure.sqlite.review.persistLegacyTelemetryRewrites
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.context.model.ReviewClaimVerdictAdmission
import skillbill.review.context.model.ReviewSpecAdjudicationAdmission
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.REVIEW_FINISHED_LEGACY_CONTRACT_VERSION
import skillbill.review.model.REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_EVENT_NAME
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewStageDegradationSelectionRequest
import skillbill.review.model.ReviewStageReached
import skillbill.tempDbConnection
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewStageTelemetryTest {
  @Test
  fun `review finished and statsSnapshot counts match durable rows and ignore worker self-report`() {
    val (_, connection) = tempDbConnection("stage-metrics-mixed")
    connection.use {
      val review = importReviewedSample(it)
      setExecutionMode(it, review.reviewRunId, "inline")
      seedMixedVerdicts(it, review.reviewRunId)
      val workerSelfReport = mapOf(
        "confirmed" to 99,
        "refuted" to 0,
        "refutation_rate" to 0.01,
      )
      val payload = ReviewStatsRuntime.buildReviewFinishedPayload(
        ReviewFinishedPayloadBuildRequest(connection = it, reviewRunId = review.reviewRunId),
      )
        .toReviewFinishedTelemetryPayload()
        .toPayload()
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, review.reviewRunId)
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "confirmed"))
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "refuted"))
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "unresolved"))
      assertEquals(1, payload.nestedInt("rejected_verdict_counts", "uncited_refutations"))
      assertEquals(1, payload.nestedInt("rejected_verdict_counts", "uncited_downgrades"))
      assertEquals(1, payload.nestedInt("rejected_verdict_counts", "finding_mutations"))
      assertEquals(1, payload.nestedInt("severity_adjustment_counts", "raised"))
      assertEquals(1, payload.nestedInt("severity_adjustment_counts", "lowered"))
      assertEquals("inline", payload["resolved_tier"])
      assertEquals(0.5, payload.nestedDouble("refutation_rate_by_stage", "verification"))
      assertEquals(
        snapshot.stageMetrics?.verification?.confirmed,
        payload.nestedInt("verification", "claim_verdict", "confirmed"),
      )
      assertEquals(snapshot.stageMetrics?.rejectedVerdictCounts?.uncitedRefutations, 1)
      assertTrue(workerSelfReport["confirmed"] != payload.nestedInt("verification", "claim_verdict", "confirmed"))
    }
  }

  @Test
  fun `inline and delegated refutation rates stay split by resolved tier`() {
    val (_, connection) = tempDbConnection("stage-metrics-tiers")
    connection.use {
      seedTierRun(it, "rvw-inline", "inline", refuted = 1, total = 2)
      seedTierRun(it, "rvw-delegated", "delegated", refuted = 2, total = 2)
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, reviewRunId = null)
      val inlineRate = snapshot.stageMetricsByTier.getValue("inline").verificationRefutationRate
      val delegatedRate = snapshot.stageMetricsByTier.getValue("delegated").verificationRefutationRate
      assertEquals(0.5, inlineRate)
      assertEquals(1.0, delegatedRate)
      assertTrue(inlineRate != delegatedRate)
    }
  }

  @Test
  fun `spec_context none writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-spec-none")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it)
      repository.recordSpecProjectionReference(
        "rvw-spec-none",
        ReviewSpecProjectionReference(absenceReason = "no_spec_found"),
      )
      emitDegradations(it, "rvw-spec-none")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.SPEC_CONTEXT_NONE.wireValue in reasons)
    }
  }

  @Test
  fun `skipped adjudication writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-adj-skip")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it)
      repository.recordSpecProjectionReference(
        "rvw-adj-skip",
        ReviewSpecProjectionReference(specPath = "spec.md", contentDigest = "abc"),
      )
      repository.recordStageBoundary(
        "rvw-adj-skip",
        ReviewStageBoundary(ReviewStage.VERIFICATION, ReviewStageReached.NOT_REACHED, "2026-08-14T08:00:00Z"),
      )
      emitDegradations(it, "rvw-adj-skip")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.ADJUDICATION_SKIPPED.wireValue in reasons)
    }
  }

  @Test
  fun `worker launch or return failure writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-worker")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it)
      repository.recordFindingVerdicts(
        "rvw-worker-fail",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = "agent process failed to spawn",
          ),
        ),
      )
      emitDegradations(it, "rvw-worker-fail")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED.wireValue in reasons)
    }
  }

  @Test
  fun `unparseable verification output is a worker failure and unsettled admission is not`() {
    val (_, connection) = tempDbConnection("stage-degrade-unparseable")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it)
      repository.recordFindingVerdicts(
        "rvw-unparseable",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = "unparseable verification output",
          ),
        ),
      )
      emitDegradations(it, "rvw-unparseable")
      assertTrue(ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED.wireValue in degradationReasons(it))
      repository.recordFindingVerdicts(
        "rvw-unsettled",
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.UNRESOLVED,
            recordedAt = "2026-08-14T08:00:00Z",
            rejectionReason = ReviewClaimVerdictAdmission.UNSETTLED,
          ),
        ),
      )
      emitDegradations(it, "rvw-unsettled")
      val unsettledReasons = TelemetryOutboxStore(it).listPending(null)
        .filter { record -> record.eventName == REVIEW_STAGE_DEGRADATION_EVENT_NAME }
        .mapNotNull { record ->
          val payload = JsonSupport.parseObjectOrNull(record.payloadJson)
            ?.let { node -> JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(node)) }
            ?: return@mapNotNull null
          if (payload["review_run_id"] != "rvw-unsettled") return@mapNotNull null
          payload["reason"] as? String
        }
      assertTrue(ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED.wireValue !in unsettledReasons)
    }
  }

  @Test
  fun `a stage without a reached boundary writes a degradation outbox row`() {
    val (_, connection) = tempDbConnection("stage-degrade-boundary")
    connection.use {
      val repository = SQLiteReviewRunCompletenessRepository(it)
      repository.recordReviewPassClaims(
        "rvw-boundary",
        listOf(claim("F-001")),
      )
      emitDegradations(it, "rvw-boundary")
      val reasons = degradationReasons(it)
      assertTrue(ReviewStageDegradationReason.STAGE_BOUNDARY_UNREACHED.wireValue in reasons)
    }
  }

  @Test
  fun `legacy 1_8_0 review_finished is rewritten in band from durable rows`() {
    val (_, connection) = tempDbConnection("stage-legacy-rewrite")
    connection.use {
      val review = importReviewedSample(it)
      setExecutionMode(it, review.reviewRunId, "delegated")
      seedMixedVerdicts(it, review.reviewRunId)
      TelemetryOutboxStore(it).enqueue(
        "skillbill_review_finished",
        JsonSupport.mapToJsonString(
          mapOf(
            "event_name" to "skillbill_review_finished",
            "contract_version" to REVIEW_FINISHED_LEGACY_CONTRACT_VERSION,
            "review_run_id" to review.reviewRunId,
            "total_findings" to 2,
            "accepted_findings" to 1,
            "rejected_findings" to 1,
            "unresolved_findings" to 0,
          ),
        ),
      )
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, review.reviewRunId)
      assertTrue(snapshot.health.malformedReviewPayloadRecords == 0)
      assertEquals(1, snapshot.stageMetrics?.verification?.confirmed)
      assertEquals("delegated", snapshot.stageMetrics?.resolvedTier)
      val storedAfterRead = TelemetryOutboxStore(it).listPending(null).single {
        it.eventName == "skillbill_review_finished"
      }
      val storedAfterReadPayload = JsonSupport.parseObjectOrNull(storedAfterRead.payloadJson)
        ?.let { node -> JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(node)) }
        ?: emptyMap()
      assertEquals(REVIEW_FINISHED_LEGACY_CONTRACT_VERSION, storedAfterReadPayload["contract_version"])
      persistLegacyTelemetryRewrites(it)
      val rewritten = TelemetryOutboxStore(it).listPending(null).single {
        it.eventName == "skillbill_review_finished"
      }
      val payload = JsonSupport.parseObjectOrNull(rewritten.payloadJson)
        ?.let { node -> JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(node)) }
        ?: emptyMap()
      assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, payload["contract_version"])
      assertEquals(1, payload.nestedInt("verification", "claim_verdict", "confirmed"))
      assertEquals("delegated", payload["resolved_tier"])
      val companion = TelemetryOutboxStore(it).listPending(null).single { record ->
        record.eventName == REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME
      }
      val companionPayload = JsonSupport.parseObjectOrNull(companion.payloadJson)
        ?.let { node -> JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(node)) }
        ?: emptyMap()
      assertEquals(REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME, companionPayload["event_name"])
      assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, companionPayload["contract_version"])
      assertEquals(review.reviewRunId, companionPayload["review_run_id"])
      assertEquals(REVIEW_FINISHED_LEGACY_CONTRACT_VERSION, companionPayload["from_version"])
      assertEquals(REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION, companionPayload["to_version"])
    }
  }

  @Test
  fun `legacy finished payload with an unknown review_run_id is quarantined instead of crashing stats`() {
    val (_, connection) = tempDbConnection("stage-legacy-unknown-run")
    connection.use {
      TelemetryOutboxStore(it).enqueue(
        "skillbill_review_finished",
        JsonSupport.mapToJsonString(
          mapOf(
            "event_name" to "skillbill_review_finished",
            "contract_version" to REVIEW_FINISHED_LEGACY_CONTRACT_VERSION,
            "review_run_id" to "rvw-missing",
            "total_findings" to 1,
          ),
        ),
      )
      val snapshot = ReviewStatsRuntime.statsSnapshot(it, reviewRunId = null)
      assertEquals(1, snapshot.health.malformedReviewPayloadRecords)
      persistLegacyTelemetryRewrites(it)
      val leftover = TelemetryOutboxStore(it).listPending(null).single { record ->
        record.eventName == "skillbill_review_finished"
      }
      val leftoverPayload = JsonSupport.parseObjectOrNull(leftover.payloadJson)
        ?.let { node -> JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(node)) }
        ?: emptyMap()
      assertEquals(REVIEW_FINISHED_LEGACY_CONTRACT_VERSION, leftoverPayload["contract_version"])
      assertTrue(
        TelemetryOutboxStore(it).listPending(null).none { record ->
          record.eventName == REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME
        },
      )
    }
  }

  private fun emitDegradations(connection: Connection, reviewRunId: String) {
    val repository = SQLiteReviewRunCompletenessRepository(connection)
    val store = LifecycleTelemetryStore(connection)
    ReviewStageDegradationSelection.select(
      ReviewStageDegradationSelectionRequest(
        reviewRunId = reviewRunId,
        spec = repository.fetchSpecProjectionReference(reviewRunId),
        boundaries = repository.fetchStageBoundaries(reviewRunId),
        verdicts = repository.fetchFindingVerdicts(reviewRunId),
        claims = repository.fetchReviewPassClaims(reviewRunId),
      ),
    ).forEach(store::reviewStageDegradation)
  }

  private fun degradationReasons(connection: Connection): List<String> =
    TelemetryOutboxStore(connection).listPending(null)
      .filter { it.eventName == REVIEW_STAGE_DEGRADATION_EVENT_NAME }
      .map { record ->
        Regex(""""reason"\s*:\s*"([^"]+)"""").find(record.payloadJson)?.groupValues?.get(1).orEmpty()
      }

  private fun seedMixedVerdicts(connection: Connection, reviewRunId: String) {
    SQLiteReviewRunCompletenessRepository(connection).recordFindingVerdicts(reviewRunId, mixedVerdicts())
  }

  private fun mixedVerdicts(): List<ReviewFindingVerdict> = listOf(
    ReviewFindingVerdict(
      stage = ReviewStage.VERIFICATION,
      findingRef = "F-001",
      claimVerdict = ReviewClaimVerdict.CONFIRMED,
      recordedAt = "2026-08-14T08:00:00Z",
    ),
    ReviewFindingVerdict(
      stage = ReviewStage.VERIFICATION,
      findingRef = "F-002",
      claimVerdict = ReviewClaimVerdict.REFUTED,
      citations = listOf(ReviewFindingCitation("src/Main.kt", 1)),
      recordedAt = "2026-08-14T08:00:00Z",
    ),
    ReviewFindingVerdict(
      stage = ReviewStage.VERIFICATION,
      findingRef = "F-003",
      claimVerdict = ReviewClaimVerdict.UNRESOLVED,
      recordedAt = "2026-08-14T08:00:00Z",
      rejectionReason = ReviewClaimVerdictAdmission.UNCITED_REFUTATION,
    ),
    ReviewFindingVerdict(
      stage = ReviewStage.ADJUDICATION,
      findingRef = "F-001",
      claimVerdict = ReviewClaimVerdict.CONFIRMED,
      scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
      recordedAt = "2026-08-14T08:01:00Z",
      rejectionReason = ReviewSpecAdjudicationAdmission.UNCITED_DOWNGRADE,
    ),
    ReviewFindingVerdict(
      stage = ReviewStage.ADJUDICATION,
      findingRef = "F-004",
      claimVerdict = ReviewClaimVerdict.UNRESOLVED,
      recordedAt = "2026-08-14T08:01:00Z",
      rejectionReason = ReviewClaimVerdictAdmission.ALTERED_CLAIM,
    ),
    ReviewFindingVerdict(
      stage = ReviewStage.ADJUDICATION,
      findingRef = "F-005",
      claimVerdict = ReviewClaimVerdict.CONFIRMED,
      scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
      severityAdjustment = ReviewSeverityAdjustment(
        ReviewSeverityAdjustmentDirection.RAISE,
        "constraint",
      ),
      recordedAt = "2026-08-14T08:01:00Z",
    ),
    ReviewFindingVerdict(
      stage = ReviewStage.ADJUDICATION,
      findingRef = "F-006",
      claimVerdict = ReviewClaimVerdict.CONFIRMED,
      scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
      severityAdjustment = ReviewSeverityAdjustment(
        ReviewSeverityAdjustmentDirection.LOWER,
        "non-goal",
      ),
      recordedAt = "2026-08-14T08:01:00Z",
    ),
  )

  private fun seedTierRun(connection: Connection, runId: String, mode: String, refuted: Int, total: Int) {
    val repository = SQLiteReviewRunCompletenessRepository(connection)
    val verdicts = (1..total).map { index ->
      ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = "F-00$index",
        claimVerdict = if (index <= refuted) ReviewClaimVerdict.REFUTED else ReviewClaimVerdict.CONFIRMED,
        citations = if (index <= refuted) listOf(ReviewFindingCitation("src/Main.kt", 1)) else emptyList(),
        recordedAt = "2026-08-14T08:00:00Z",
      )
    }
    repository.recordFindingVerdicts(runId, verdicts)
    repository.recordReviewPassClaims(runId, (1..total).map { claim("F-00$it") })
    setExecutionMode(connection, runId, mode)
  }

  private fun importReviewedSample(connection: Connection): ImportedReview {
    val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
    ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
    TriageRuntime.recordFeedback(
      connection = connection,
      request = FeedbackRequest(
        reviewRunId = review.reviewRunId,
        findingIds = listOf("F-001"),
        eventType = "finding_accepted",
        note = "",
      ),
      telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
    )
    TriageRuntime.recordFeedback(
      connection = connection,
      request = FeedbackRequest(
        reviewRunId = review.reviewRunId,
        findingIds = listOf("F-002"),
        eventType = "fix_rejected",
        note = "Intentional wording",
      ),
      telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
    )
    return review
  }

  private fun setExecutionMode(connection: Connection, reviewRunId: String, mode: String) {
    connection.prepareStatement(
      "UPDATE review_runs SET execution_mode = ? WHERE review_run_id = ?",
    ).use { statement ->
      statement.setString(1, mode)
      statement.setString(2, reviewRunId)
      statement.executeUpdate()
    }
  }

  private fun claim(findingRef: String) = ParallelReviewMergedFinding(
    fNumber = findingRef,
    agentIds = listOf("codex"),
    severity = ParallelReviewSeverity.MAJOR,
    confidence = "High",
    location = "src/Main.kt:1",
    description = "finding",
    repositoryPath = "src/Main.kt",
    line = 1,
  )
}

private fun Map<String, Any?>.nestedInt(vararg keys: String): Int {
  var current: Any? = this
  keys.forEach { key ->
    current = (current as? Map<*, *>)?.get(key)
  }
  return (current as Number).toInt()
}

private fun Map<String, Any?>.nestedDouble(vararg keys: String): Double {
  var current: Any? = this
  keys.forEach { key ->
    current = (current as? Map<*, *>)?.get(key)
  }
  return (current as Number).toDouble()
}
