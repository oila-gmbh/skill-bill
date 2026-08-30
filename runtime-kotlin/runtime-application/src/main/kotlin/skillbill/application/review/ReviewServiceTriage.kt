package skillbill.application.review

import skillbill.application.review.model.TriageResult
import skillbill.application.review.model.TriageResultKind
import skillbill.application.telemetry.feedbackTelemetryOptions
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.TriageDecisionParser
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.TriageDecision

internal data class TriageReviewRequest(
  val database: DatabaseSessionFactory,
  val settingsProvider: TelemetrySettingsProvider,
  val runId: String,
  val decisions: List<String>,
  val listOnly: Boolean,
  val dbOverride: String?,
  val listWhenNoDecisions: Boolean,
  val routedSkillPlatformSlugs: Map<String, String>,
)

internal fun triageReview(request: TriageReviewRequest): TriageResult =
  if (request.listOnly || (request.decisions.isEmpty() && request.listWhenNoDecisions)) {
    request.database.read(request.dbOverride) { unitOfWork ->
    val numberedFindings = unitOfWork.reviews.fetchNumberedFindings(request.runId)
    TriageResult(
      kind = TriageResultKind.LIST,
      dbPath = unitOfWork.dbPath.toString(),
      reviewRunId = request.runId,
      findings = numberedFindings,
    )
  }
} else {
    request.database.transaction(request.dbOverride) { unitOfWork ->
    val numberedFindings = unitOfWork.reviews.fetchNumberedFindings(request.runId)
    val applied = applyTriageDecisions(
      TriageDecisionsRequest(
        settingsProvider = request.settingsProvider,
        reviewRepository = unitOfWork.reviews,
        runId = request.runId,
        numberedFindings = numberedFindings,
        decisions = request.decisions,
        routedSkillPlatformSlugs = request.routedSkillPlatformSlugs,
      ),
    )
    TriageResult(
      kind = TriageResultKind.RECORDED,
      dbPath = unitOfWork.dbPath.toString(),
      reviewRunId = request.runId,
      recorded = applied.recorded,
      telemetry = applied.telemetry,
    )
  }
}

internal data class TriageDecisionsRequest(
  val settingsProvider: TelemetrySettingsProvider,
  val reviewRepository: ReviewRepository,
  val runId: String,
  val numberedFindings: List<NumberedFinding>,
  val decisions: List<String>,
  val routedSkillPlatformSlugs: Map<String, String>,
)

internal fun applyTriageDecisions(request: TriageDecisionsRequest): AppliedTriageDecisions {
  val parsedDecisions = TriageDecisionParser.parseTriageDecisions(request.decisions, request.numberedFindings)
  var telemetry: ReviewFinishedTelemetry? = null
  parsedDecisions.forEach { decision ->
    val returnedTelemetry =
      request.reviewRepository.recordFeedback(
        FeedbackRequest(request.runId, listOf(decision.findingId), decision.outcomeType, decision.note),
        feedbackTelemetryOptions(request.settingsProvider),
        routedSkillPlatformSlugs = request.routedSkillPlatformSlugs,
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

internal data class AppliedTriageDecisions(
  val recorded: List<TriageDecision>,
  val telemetry: ReviewFinishedTelemetry?,
)
