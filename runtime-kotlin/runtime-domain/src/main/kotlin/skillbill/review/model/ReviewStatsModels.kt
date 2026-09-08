package skillbill.review.model

data class ReviewFindingDetail(
  val findingId: String,
  val severity: String,
  val confidence: String,
  val issueCategory: String,
  val location: String,
  val description: String,
  val outcomeType: String,
  val note: String = "",
)

data class ReviewFindingStats(
  val totalFindings: Int,
  val acceptedFindings: Int,
  val rejectedFindings: Int,
  val unresolvedFindings: Int,
  val acceptedRate: Double,
  val rejectedRate: Double,
  val latestOutcomeCounts: Map<String, Int>,
  val acceptedSeverityCounts: Map<String, Int>,
  val rejectedSeverityCounts: Map<String, Int>,
  val unresolvedSeverityCounts: Map<String, Int>,
  val acceptedFindingDetails: List<ReviewFindingDetail>,
  val rejectedFindingsWithNotes: Int,
  val rejectedFindingDetails: List<ReviewFindingDetail>,
)

data class ReviewHealthStats(
  val totalReviewPayloadRecords: Int,
  val includedReviewPayloadRecords: Int,
  val standaloneReviewPayloadRecords: Int,
  val embeddedReviewPayloadRecords: Int,
  val malformedReviewPayloadRecords: Int,
  val dataQualityDebtRecords: Int,
  val totalFindings: Int,
  val averageFindings: Double,
  val medianFindings: Double,
  val p90Findings: Double,
  val acceptedFindings: Int,
  val rejectedFindings: Int,
  val unresolvedFindings: Int,
  val acceptedRate: Double,
  val rejectedRate: Double,
  val unresolvedRate: Double,
  val severityCounts: Map<String, Int>,
  val confidenceCounts: Map<String, Int>,
  val latestOutcomeCounts: Map<String, Int>,
  val issueCategoryCounts: Map<String, Int>,
  val categorySeverityCounts: Map<String, Map<String, Int>>,
  val platformCounts: Map<String, Int>,
  val scopeCounts: Map<String, Int>,
  val sourceCounts: Map<String, Int>,
)

data class ReviewFinishedFindingStats(
  val totalFindings: Int,
  val acceptedFindings: Int,
  val rejectedFindings: Int,
  val unresolvedFindings: Int,
  val acceptedRate: Double,
  val rejectedRate: Double,
  val acceptedFindingDetails: List<ReviewFindingDetail>,
  val rejectedFindingDetails: List<ReviewFindingDetail>,
)

data class ReviewLearningEntry(
  val reference: String?,
  val scope: String?,
  val title: String? = null,
  val ruleText: String? = null,
)

data class ReviewLearningsSummary(
  val appliedCount: Int,
  val appliedReferences: List<String>,
  val appliedSummary: String,
  val scopeCounts: Map<String, Int>,
  val entries: List<ReviewLearningEntry>,
)

data class ReviewStageVerdictDistribution(
  val confirmed: Int,
  val refuted: Int,
  val unresolved: Int,
  val inScope: Int,
  val outOfScopePreexisting: Int,
  val specDeviation: Int,
  val specAcceptedTradeoff: Int,
  val findingCount: Int,
)

data class ReviewRejectedVerdictCounts(
  val uncitedRefutations: Int,
  val uncitedDowngrades: Int,
  val findingMutations: Int,
)

data class ReviewSeverityAdjustmentCounts(
  val raised: Int,
  val lowered: Int,
)

data class ReviewStageMetrics(
  val verification: ReviewStageVerdictDistribution,
  val adjudication: ReviewStageVerdictDistribution,
  val verificationRefutationRate: Double,
  val adjudicationRefutationRate: Double,
  val rejectedVerdictCounts: ReviewRejectedVerdictCounts,
  val severityAdjustmentCounts: ReviewSeverityAdjustmentCounts,
  val resolvedTier: String,
)

data class ReviewFinishedTelemetry(
  val findingStats: ReviewFinishedFindingStats,
  val reviewRunId: String,
  val reviewSessionId: String,
  val routedSkill: String?,
  val reviewSubskills: List<String>,
  val reviewScope: String,
  val reviewPlatform: String?,
  val detectedStack: String,
  val detectedStackDetail: String?,
  val fallback: Boolean,
  val fallbackReason: String?,
  val platformSlug: String,
  val scopeType: String,
  val executionMode: String?,
  val reviewFinishedAt: String?,
  val learnings: ReviewLearningsSummary,
  val stageMetrics: ReviewStageMetrics,
)

data class FeatureTaskRuntimeWorkflowStats(
  val totalRuns: Int,
  val finishedRuns: Int,
  val inProgressRuns: Int,
  val featureSizeCounts: Map<String, Int>,
  val completionStatusCounts: Map<String, Int>,
  val phaseOutcomeCounts: Map<String, Int>,
  val completedRuns: Int,
  val completedRate: Double,
  val blockedRuns: Int,
  val blockedRate: Double,
  val decomposedRuns: Int,
  val decomposedRate: Double,
  val errorRuns: Int,
  val errorRate: Double,
  val averageCompletedPhaseCount: Double,
  val estimatedTokenRunsWithValue: Int,
  val averageEstimatedTotalTokens: Double,
)

data class FeatureVerifyWorkflowStats(
  val totalRuns: Int,
  val finishedRuns: Int,
  val inProgressRuns: Int,
  val completionStatusCounts: Map<String, Int>,
  val auditResultCounts: Map<String, Int>,
  val rolloutRelevantRuns: Int,
  val rolloutRelevantRate: Double,
  val featureFlagAuditPerformedRuns: Int,
  val featureFlagAuditPerformedRate: Double,
  val historyReadRuns: Int,
  val historyReadRate: Double,
  val historyRelevantRuns: Int,
  val historyRelevantRate: Double,
  val historyHelpfulRuns: Int,
  val historyHelpfulRate: Double,
  val historyRelevanceCounts: Map<String, Int>,
  val historyHelpfulnessCounts: Map<String, Int>,
  val runsWithGapsFound: Int,
  val averageAcceptanceCriteriaCount: Double,
  val averageReviewIterations: Double,
  val averageDurationSeconds: Double,
)

data class GoalModeStats(
  val totalRuns: Int,
  val finishedRuns: Int,
  val inProgressRuns: Int,
  val completedRuns: Int,
  val completedRate: Double,
  val blockedRuns: Int,
  val blockedRate: Double,
  val averageRunDurationMs: Double,
)

data class GoalBlockedSubtaskSummary(
  val subtaskId: Int,
  val subtaskName: String,
  val issueKey: String,
  val blockedReason: String,
  val attemptCount: Int,
)

// SKILL-66 Subtask 2: per-run summary used for the most-recent-run lookup in
// goal stats. `finishedAt`/`status` are blank until the run finishes.
data class GoalRunSummary(
  val workflowId: String,
  val issueKey: String,
  val featureName: String,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val resumed: Boolean,
  val subtaskTotal: Int,
)

// SKILL-66 Subtask 2: goal-run aggregate covering AC#1's four read needs —
// run counts, terminal-status counts, duration aggregates, per-subtask outcome
// breakdown — plus the most-recent-run lookup (`null` on an empty store).
data class GoalWorkflowStats(
  val totalRuns: Int,
  val finishedRuns: Int,
  val inProgressRuns: Int,
  val completionStatusCounts: Map<String, Int>,
  val completedRuns: Int,
  val completedRate: Double,
  val blockedRuns: Int,
  val blockedRate: Double,
  val subtaskOutcomeCounts: Map<String, Int>,
  val totalSubtaskEvents: Int,
  val averageRunDurationMs: Double,
  val averageSubtaskDurationMs: Double,
  val averageAttemptCount: Double,
  val mostRecentRun: GoalRunSummary?,
  val topBlockedSubtasks: List<GoalBlockedSubtaskSummary>,
  val byMode: Map<String, GoalModeStats> = emptyMap(),
)
