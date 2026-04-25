package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.contracts.review.ReviewFeedbackContract
import skillbill.contracts.review.TriageListContract
import skillbill.contracts.review.TriageRecordedContract
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.FeedbackRequest
import skillbill.review.NumberedFinding
import skillbill.review.ReviewInputReader
import skillbill.review.ReviewParser
import skillbill.review.TriageDecision
import skillbill.review.TriageDecisionParser

@Inject
class ReviewService(
  private val context: RuntimeContext,
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) {
  fun previewImport(input: String): Map<String, Any?> {
    val (text) = ReviewInputReader.readInput(input, context.stdinText)
    val review = ReviewParser.parseReview(text)
    return review.toReviewPreviewContract().toPayload()
  }

  fun importReview(input: String, dbOverride: String?, finishZeroFindingTelemetry: Boolean = true): Map<String, Any?> {
    val (text, sourcePath) = ReviewInputReader.readInput(input, context.stdinText)
    val review = ReviewParser.parseReview(text)
    return database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.reviews.saveImportedReview(review, sourcePath)
      if (finishZeroFindingTelemetry && review.findings.isEmpty()) {
        val settings = telemetrySettingsOrNull(settingsProvider)
        unitOfWork.reviews.updateReviewFinishedTelemetryState(
          runId = review.reviewRunId,
          enabled = settings?.enabled ?: false,
          level = settings?.level ?: "off",
        )
      }
      review.toImportedReviewContract(dbPath = unitOfWork.dbPath.toString()).toPayload()
    }
  }

  fun markOrchestrated(runId: String, dbOverride: String?) {
    database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.reviews.markOrchestrated(runId)
    }
  }

  fun reviewFinishedTelemetryPayload(runId: String, dbOverride: String?): Map<String, Any?>? =
    database.transaction(dbOverride) { unitOfWork ->
      val settings = telemetrySettingsOrNull(settingsProvider)
      unitOfWork.reviews.updateReviewFinishedTelemetryState(
        runId = runId,
        enabled = settings?.enabled ?: false,
        level = settings?.level ?: "off",
      )
    }

  fun recordFeedback(
    runId: String,
    event: String,
    findings: List<String>,
    note: String,
    dbOverride: String?,
  ): Map<String, Any?> = database.transaction(dbOverride) { unitOfWork ->
    unitOfWork.reviews.recordFeedback(
      FeedbackRequest(runId, findings, event, note),
      feedbackTelemetryOptions(settingsProvider),
    )
    ReviewFeedbackContract(
      dbPath = unitOfWork.dbPath.toString(),
      reviewRunId = runId,
      outcomeType = event,
      recordedFindings = findings.size,
    ).toPayload()
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
        payload =
        TriageListContract(
          dbPath = unitOfWork.dbPath.toString(),
          reviewRunId = runId,
          findings = numberedFindings.map(NumberedFinding::toNumberedFindingContract),
        ).toPayload(),
        findings = numberedFindings,
      )
    }
  } else {
    database.transaction(dbOverride) { unitOfWork ->
      val numberedFindings = unitOfWork.reviews.fetchNumberedFindings(runId)
      val applied = applyTriageDecisions(unitOfWork.reviews, runId, numberedFindings, decisions)
      TriageResult(
        payload =
        TriageRecordedContract(
          dbPath = unitOfWork.dbPath.toString(),
          reviewRunId = runId,
          recorded = applied.recorded.map(TriageDecision::toTriageDecisionContract),
        ).toPayload(),
        recorded = applied.recorded,
        telemetryPayload = applied.telemetryPayload,
      )
    }
  }

  fun reviewStats(runId: String?, dbOverride: String?): Map<String, Any?> =
    statsPayload(database, dbOverride) { reviewRepository -> reviewRepository.reviewStatsPayload(runId) }

  fun featureImplementStats(dbOverride: String?): Map<String, Any?> =
    statsPayload(database, dbOverride, ReviewRepository::featureImplementStatsPayload)

  fun featureVerifyStats(dbOverride: String?): Map<String, Any?> =
    statsPayload(database, dbOverride, ReviewRepository::featureVerifyStatsPayload)

  private fun applyTriageDecisions(
    reviewRepository: ReviewRepository,
    runId: String,
    numberedFindings: List<NumberedFinding>,
    decisions: List<String>,
  ): AppliedTriageDecisions {
    val parsedDecisions = TriageDecisionParser.parseTriageDecisions(decisions, numberedFindings)
    var telemetryPayload: Map<String, Any?>? = null
    parsedDecisions.forEach { decision ->
      val returnedPayload =
        reviewRepository.recordFeedback(
          FeedbackRequest(runId, listOf(decision.findingId), decision.outcomeType, decision.note),
          feedbackTelemetryOptions(settingsProvider),
        )
      if (returnedPayload != null) {
        telemetryPayload = returnedPayload
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
      telemetryPayload = telemetryPayload,
    )
  }
}

private fun statsPayload(
  database: DatabaseSessionFactory,
  dbOverride: String?,
  payloadBuilder: (ReviewRepository) -> Map<String, Any?>,
): Map<String, Any?> = database.read(dbOverride) { unitOfWork ->
  linkedMapOf<String, Any?>().apply {
    putAll(payloadBuilder(unitOfWork.reviews))
    put("db_path", unitOfWork.dbPath.toString())
  }
}

data class TriageResult(
  val payload: Map<String, Any?>,
  val findings: List<NumberedFinding> = emptyList(),
  val recorded: List<TriageDecision> = emptyList(),
  val telemetryPayload: Map<String, Any?>? = null,
)

private data class AppliedTriageDecisions(
  val recorded: List<TriageDecision>,
  val telemetryPayload: Map<String, Any?>?,
)
