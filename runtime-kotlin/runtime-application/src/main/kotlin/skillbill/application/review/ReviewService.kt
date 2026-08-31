package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.FeatureTaskRuntimeStatsResult
import skillbill.application.review.model.FeatureVerifyStatsResult
import skillbill.application.review.model.GoalStatsResult
import skillbill.application.review.model.ImportedReviewResult
import skillbill.application.review.model.ReviewFeedbackResult
import skillbill.application.review.model.ReviewPreviewResult
import skillbill.application.review.model.ReviewStatsResult
import skillbill.application.review.model.TriageResult
import skillbill.application.telemetry.feedbackTelemetryOptions
import skillbill.application.telemetry.telemetrySettingsOrNull
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.ReviewParser
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.withCanonicalAttribution

@Inject
class ReviewService(
  private val context: EnvironmentContext,
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val reviewInputSource: ReviewInputSource,
  private val reviewAttributionPort: ReviewAttributionPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun previewImport(input: String): ReviewPreviewResult {
    val (text) = reviewInputSource.readInput(input, context.stdinText)
    val review = ReviewParser.parseReview(text)
    return review.toReviewPreviewResult()
  }

  fun importReview(
    input: String,
    dbOverride: String?,
    finishZeroFindingTelemetry: Boolean = true,
  ): ImportedReviewResult {
    val (text, sourcePath) = reviewInputSource.readInput(input, context.stdinText)
    val (knownPackSkillNames, knownPlatformSlugs) = canonicalAttributionPorts(reviewAttributionPort)
    val parsed = ReviewParser.parseReview(text).withCanonicalAttribution(
      knownPackSkillNames = knownPackSkillNames,
      knownPlatformSlugs = knownPlatformSlugs,
    )
    val review = parsed.copy(planLanes = composedRunLanes(parsed, reviewAttributionPort, diagnostics))
    return database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.reviews.saveImportedReview(review, sourcePath)
      if (finishZeroFindingTelemetry && review.findings.isEmpty()) {
        val settings = telemetrySettingsOrNull(settingsProvider)
        unitOfWork.reviews.updateReviewFinishedTelemetryState(
          runId = review.reviewRunId,
          enabled = settings?.enabled ?: false,
          level = settings?.level ?: "off",
          routedSkillPlatformSlugs = reviewAttributionPort.routedSkillPlatformSlugs(),
        )
      }
      unitOfWork.reviews.ensureTerminalReviewState(review.reviewRunId, review.executionMode)
      review.toImportedReviewResult(dbPath = unitOfWork.dbPath.toString())
    }
  }

  fun markOrchestrated(runId: String, dbOverride: String?) {
    database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.reviews.markOrchestrated(runId)
    }
  }

  fun reviewFinishedTelemetryPayload(runId: String, dbOverride: String?): ReviewFinishedTelemetry? =
    database.transaction(dbOverride) { unitOfWork ->
      val settings = telemetrySettingsOrNull(settingsProvider)
      unitOfWork.reviews.updateReviewFinishedTelemetryState(
        runId = runId,
        enabled = settings?.enabled ?: false,
        level = settings?.level ?: "off",
        routedSkillPlatformSlugs = reviewAttributionPort.routedSkillPlatformSlugs(),
      )
    }

  fun recordFeedback(
    runId: String,
    event: String,
    findings: List<String>,
    note: String,
    dbOverride: String?,
  ): ReviewFeedbackResult = database.transaction(dbOverride) { unitOfWork ->
    unitOfWork.reviews.recordFeedback(
      FeedbackRequest(runId, findings, event, note),
      feedbackTelemetryOptions(settingsProvider),
      routedSkillPlatformSlugs = reviewAttributionPort.routedSkillPlatformSlugs(),
    )
    ReviewFeedbackResult(
      dbPath = unitOfWork.dbPath.toString(),
      reviewRunId = runId,
      outcomeType = event,
      recordedFindings = findings.size,
    )
  }

  fun triage(
    runId: String,
    decisions: List<String>,
    listOnly: Boolean,
    dbOverride: String?,
    listWhenNoDecisions: Boolean = true,
  ): TriageResult = triageReview(
    TriageReviewRequest(
      database = database,
      settingsProvider = settingsProvider,
      runId = runId,
      decisions = decisions,
      listOnly = listOnly,
      dbOverride = dbOverride,
      listWhenNoDecisions = listWhenNoDecisions,
      routedSkillPlatformSlugs = reviewAttributionPort.routedSkillPlatformSlugs(),
    ),
  )

  fun reviewStats(runId: String?, dbOverride: String?): ReviewStatsResult =
    reviewStatsResult(database, dbOverride) { reviewRepository -> reviewRepository.reviewStats(runId) }

  fun featureVerifyStats(dbOverride: String?): FeatureVerifyStatsResult =
    featureVerifyStatsResult(database, dbOverride, ReviewRepository::featureVerifyStats)

  fun featureTaskRuntimeStats(dbOverride: String?): FeatureTaskRuntimeStatsResult =
    featureTaskRuntimeStatsResult(database, dbOverride, ReviewRepository::featureTaskRuntimeStats)

  fun goalStats(dbOverride: String?): GoalStatsResult =
    goalStatsResult(database, dbOverride, ReviewRepository::goalStats)
}
