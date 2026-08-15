package skillbill.application.review

import me.tatarka.inject.annotations.Inject
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
import skillbill.error.ShellContentContractException
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.ReviewParser
import skillbill.review.ReviewRunLaneResolver
import skillbill.review.TriageDecisionParser
import skillbill.review.canonicalPackSkillNames
import skillbill.review.canonicalPlatformSlugs
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.TriageDecision
import skillbill.review.packSlugFromCanonicalPackSkillName
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.review.withCanonicalAttribution

@Suppress("TooManyFunctions")
@Inject
class ReviewService(
  private val context: EnvironmentContext,
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val reviewInputSource: ReviewInputSource,
  private val reviewAttributionPort: ReviewAttributionPort,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
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
    val parsed = ReviewParser.parseReview(text).withCanonicalAttribution(
      knownPackSkillNames = reviewAttributionPort.knownPackSkillNames() + canonicalPackSkillNames,
      knownPlatformSlugs = reviewAttributionPort.knownPlatformSlugs() + canonicalPlatformSlugs,
    )
    val review = parsed.copy(planLanes = composedRunLanes(parsed))
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
      // After the telemetry branch: that branch may decline to emit, or clear a provisional
      // timestamp, and the run's terminal facts must survive either outcome.
      unitOfWork.reviews.ensureTerminalReviewState(review.reviewRunId, review.executionMode)
      review.toImportedReviewResult(dbPath = unitOfWork.dbPath.toString())
    }
  }

  /**
   * Lane attribution for a run, composed from the launch plan of the pack its canonical routed skill
   * belongs to. A run whose pack cannot be resolved still imports: its reported lanes are retained
   * and marked unresolved rather than failing the import or being guessed into a pack.
   */
  private fun composedRunLanes(review: ImportedReview): List<ReviewRunLane> {
    // The slug comes from the canonical skill name itself, not from routedSkillPlatformSlugs(): that
    // map is derived from an in-repo platform-packs directory that only exists in the skill-bill
    // source tree, so depending on it would source lanes from narration in every consumer repository.
    val routedPackSlug = packSlugFromCanonicalPackSkillName(review.routedSkillCanonical)
    if (routedPackSlug == null) {
      diagnostics.warning(
        "review lane composition: routed skill '${review.routedSkillCanonical}' names no platform pack; " +
          "run ${review.reviewRunId} imports with unresolved lanes.",
      )
      return ReviewRunLaneResolver.resolve(
        ReviewLaunchPlan(review.routedSkillCanonical, emptyList()),
        review.specialistReviews,
      )
    }
    // Composition reads the installed pack catalog, which can be partially staged or missing a
    // composed baseline layer. That is an attribution gap, not an import failure: degrade to an
    // empty plan so the lanes resolve as unresolved and the review still lands. Only a contract
    // failure degrades — anything else propagates, so a bug here cannot masquerade as a routing gap.
    val plan = try {
      reviewAttributionPort.composedLaunchPlan(routedPackSlug)
    } catch (error: ShellContentContractException) {
      diagnostics.warning(
        "review lane composition: pack '$routedPackSlug' failed to compose " +
          "(${error::class.simpleName}); run ${review.reviewRunId} imports with unresolved lanes " +
          "instead of the composed launch plan.",
        error,
      )
      ReviewLaunchPlan(routedPackSlug, emptyList())
    }
    return ReviewRunLaneResolver.resolve(plan, review.specialistReviews)
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
    stageMetrics = snapshot.stageMetrics,
    stageMetricsByTier = snapshot.stageMetricsByTier,
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
