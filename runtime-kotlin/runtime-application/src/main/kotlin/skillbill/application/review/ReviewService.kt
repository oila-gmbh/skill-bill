package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureImplementStatsResult
import skillbill.application.model.FeatureTaskRuntimeStatsResult
import skillbill.application.model.FeatureVerifyStatsResult
import skillbill.application.model.GoalStatsResult
import skillbill.application.model.ImportedReviewResult
import skillbill.application.model.ReviewFeedbackResult
import skillbill.application.model.ReviewPreviewResult
import skillbill.application.model.ReviewStatsResult
import skillbill.application.model.TriageResult
import skillbill.application.model.TriageResultKind
import skillbill.application.telemetry.feedbackTelemetryOptions
import skillbill.application.telemetry.telemetrySettingsOrNull
import skillbill.model.EnvironmentContext
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.ReviewParser
import skillbill.review.TriageDecisionParser
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.TriageDecision

@Suppress("TooManyFunctions")
@Inject
class ReviewService(
  private val context: EnvironmentContext,
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val reviewInputSource: ReviewInputSource,
  private val reviewAttributionPort: ReviewAttributionPort,
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
    val review = ReviewParser.parseReview(text)
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
  ): TriageResult = if (listOnly || (decisions.isEmpty() && listWhenNoDecisions)) {
    database.read(dbOverride) { unitOfWork ->
      val numberedFindings = unitOfWork.reviews.fetchNumberedFindings(runId)
      TriageResult(
        kind = TriageResultKind.LIST,
        dbPath = unitOfWork.dbPath.toString(),
        reviewRunId = runId,
        findings = numberedFindings,
      )
    }
  } else {
    database.transaction(dbOverride) { unitOfWork ->
      val numberedFindings = unitOfWork.reviews.fetchNumberedFindings(runId)
      val applied = applyTriageDecisions(
        settingsProvider,
        unitOfWork.reviews,
        runId,
        numberedFindings,
        decisions,
        reviewAttributionPort.routedSkillPlatformSlugs(),
      )
      TriageResult(
        kind = TriageResultKind.RECORDED,
        dbPath = unitOfWork.dbPath.toString(),
        reviewRunId = runId,
        recorded = applied.recorded,
        telemetry = applied.telemetry,
      )
    }
  }

  fun reviewStats(runId: String?, dbOverride: String?): ReviewStatsResult =
    reviewStatsResult(database, dbOverride) { reviewRepository -> reviewRepository.reviewStats(runId) }

  fun featureImplementStats(dbOverride: String?): FeatureImplementStatsResult =
    featureImplementStatsResult(database, dbOverride, ReviewRepository::featureImplementStats)

  fun featureVerifyStats(dbOverride: String?): FeatureVerifyStatsResult =
    featureVerifyStatsResult(database, dbOverride, ReviewRepository::featureVerifyStats)

  fun featureTaskRuntimeStats(dbOverride: String?): FeatureTaskRuntimeStatsResult =
    featureTaskRuntimeStatsResult(database, dbOverride, ReviewRepository::featureTaskRuntimeStats)

  fun goalStats(dbOverride: String?): GoalStatsResult =
    goalStatsResult(database, dbOverride, ReviewRepository::goalStats)
}

@Suppress("LongParameterList")
private fun applyTriageDecisions(
  settingsProvider: TelemetrySettingsProvider,
  reviewRepository: ReviewRepository,
  runId: String,
  numberedFindings: List<NumberedFinding>,
  decisions: List<String>,
  routedSkillPlatformSlugs: Map<String, String>,
): AppliedTriageDecisions {
  val parsedDecisions = TriageDecisionParser.parseTriageDecisions(decisions, numberedFindings)
  var telemetry: ReviewFinishedTelemetry? = null
  parsedDecisions.forEach { decision ->
    val returnedTelemetry =
      reviewRepository.recordFeedback(
        FeedbackRequest(runId, listOf(decision.findingId), decision.outcomeType, decision.note),
        feedbackTelemetryOptions(settingsProvider),
        routedSkillPlatformSlugs = routedSkillPlatformSlugs,
      )
    if (returnedTelemetry != null) {
      telemetry = returnedTelemetry
    }
  }
  return AppliedTriageDecisions(
    recorded =
    parsedDecisions.map { decision ->
      TriageDecision(
        number = decision.number,
        findingId = decision.findingId,
        outcomeType = decision.outcomeType,
        note = decision.note,
      )
    },
    telemetry = telemetry,
  )
}

private fun reviewStatsResult(
  database: DatabaseSessionFactory,
  dbOverride: String?,
  statsBuilder: (ReviewRepository) -> ReviewRepositoryStatsSnapshot,
): ReviewStatsResult = database.read(dbOverride) { unitOfWork ->
  val snapshot = statsBuilder(unitOfWork.reviews)
  ReviewStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    reviewRunId = snapshot.reviewRunId,
    stats = snapshot.stats,
    health = snapshot.health,
  )
}

private fun featureImplementStatsResult(
  database: DatabaseSessionFactory,
  dbOverride: String?,
  statsBuilder: (ReviewRepository) -> FeatureImplementWorkflowStats,
): FeatureImplementStatsResult = database.read(dbOverride) { unitOfWork ->
  FeatureImplementStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}

private fun featureVerifyStatsResult(
  database: DatabaseSessionFactory,
  dbOverride: String?,
  statsBuilder: (ReviewRepository) -> FeatureVerifyWorkflowStats,
): FeatureVerifyStatsResult = database.read(dbOverride) { unitOfWork ->
  FeatureVerifyStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}

private fun featureTaskRuntimeStatsResult(
  database: DatabaseSessionFactory,
  dbOverride: String?,
  statsBuilder: (ReviewRepository) -> FeatureTaskRuntimeWorkflowStats,
): FeatureTaskRuntimeStatsResult = database.read(dbOverride) { unitOfWork ->
  FeatureTaskRuntimeStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}

private fun goalStatsResult(
  database: DatabaseSessionFactory,
  dbOverride: String?,
  statsBuilder: (ReviewRepository) -> GoalWorkflowStats,
): GoalStatsResult = database.read(dbOverride) { unitOfWork ->
  GoalStatsResult(
    dbPath = unitOfWork.dbPath.toString(),
    stats = statsBuilder(unitOfWork.reviews),
  )
}

private data class AppliedTriageDecisions(
  val recorded: List<TriageDecision>,
  val telemetry: ReviewFinishedTelemetry?,
)
