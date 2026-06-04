package skillbill.review.model

data class ReviewFindingDetail(
  val findingId: String,
  val severity: String,
  val confidence: String,
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

data class ReviewFinishedFindingStats(
  val totalFindings: Int,
  val acceptedFindings: Int,
  val unresolvedFindings: Int,
  val acceptedRate: Double,
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

data class ReviewFinishedTelemetry(
  val findingStats: ReviewFinishedFindingStats,
  val reviewRunId: String,
  val reviewSessionId: String,
  val routedSkill: String?,
  val reviewSubskills: List<String>,
  val reviewScope: String,
  val reviewPlatform: String?,
  val executionMode: String?,
  val reviewFinishedAt: String?,
  val learnings: ReviewLearningsSummary,
)

data class ReviewStatsSnapshot(
  val stats: ReviewFindingStats,
  val reviewRunId: String?,
)

data class FeatureImplementWorkflowStats(
  val totalRuns: Int,
  val finishedRuns: Int,
  val inProgressRuns: Int,
  val featureSizeCounts: Map<String, Int>,
  val completionStatusCounts: Map<String, Int>,
  val auditResultCounts: Map<String, Int>,
  val validationResultCounts: Map<String, Int>,
  val featureFlagPatternCounts: Map<String, Int>,
  val boundaryHistoryValueCounts: Map<String, Int>,
  val rolloutNeededRuns: Int,
  val rolloutNeededRate: Double,
  val featureFlagUsedRuns: Int,
  val featureFlagUsedRate: Double,
  val prCreatedRuns: Int,
  val prCreatedRate: Double,
  val boundaryHistoryWrittenRuns: Int,
  val boundaryHistoryWrittenRate: Double,
  val averageAcceptanceCriteriaCount: Double,
  val averageSpecWordCount: Double,
  val averageReviewIterations: Double,
  val averageAuditIterations: Double,
  val averageFilesCreated: Double,
  val averageFilesModified: Double,
  val averageTasksCompleted: Double,
  val averageDurationSeconds: Double,
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
