package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.FeedbackRequest
import skillbill.review.NumberedFinding
import skillbill.review.ReviewInputReader
import skillbill.review.ReviewParser
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
    return linkedMapOf(
      "review_run_id" to review.reviewRunId,
      "review_session_id" to review.reviewSessionId,
      "finding_count" to review.findings.size,
      "routed_skill" to review.routedSkill,
      "detected_scope" to review.detectedScope,
      "detected_stack" to review.detectedStack,
      "execution_mode" to review.executionMode,
    )
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
      linkedMapOf(
        "db_path" to unitOfWork.dbPath.toString(),
        "review_run_id" to review.reviewRunId,
        "review_session_id" to review.reviewSessionId,
        "finding_count" to review.findings.size,
        "routed_skill" to review.routedSkill,
        "detected_scope" to review.detectedScope,
        "detected_stack" to review.detectedStack,
        "execution_mode" to review.executionMode,
      )
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
    linkedMapOf(
      "db_path" to unitOfWork.dbPath.toString(),
      "review_run_id" to runId,
      "outcome_type" to event,
      "recorded_findings" to findings.size,
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
      val findings = numberedFindings.map(::findingPayload)
      TriageResult(
        payload =
        linkedMapOf(
          "db_path" to unitOfWork.dbPath.toString(),
          "review_run_id" to runId,
          "findings" to findings,
        ),
        findings = findings,
      )
    }
  } else {
    database.transaction(dbOverride) { unitOfWork ->
      val numberedFindings = unitOfWork.reviews.fetchNumberedFindings(runId)
      val applied = applyTriageDecisions(unitOfWork.reviews, runId, numberedFindings, decisions)
      TriageResult(
        payload =
        linkedMapOf(
          "db_path" to unitOfWork.dbPath.toString(),
          "review_run_id" to runId,
          "recorded" to applied.recorded,
        ),
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
        linkedMapOf(
          "number" to decision.number,
          "finding_id" to decision.findingId,
          "outcome_type" to decision.outcomeType,
          "note" to decision.note,
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
  val findings: List<Map<String, Any?>> = emptyList(),
  val recorded: List<Map<String, Any?>> = emptyList(),
  val telemetryPayload: Map<String, Any?>? = null,
)

private data class AppliedTriageDecisions(
  val recorded: List<Map<String, Any?>>,
  val telemetryPayload: Map<String, Any?>?,
)
