package skillbill.infrastructure.sqlite

import skillbill.db.core.reconcileStaleTelemetrySessions
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.db.workflow.WorkflowStateStore
import skillbill.db.worklist.SQLiteWorkListRepository
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.infrastructure.sqlite.goal.UnaddressedFindingsRuntime
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.review.TriageRuntime
import skillbill.infrastructure.sqlite.review.existingReviewSummary
import skillbill.infrastructure.sqlite.review.replaceFindings
import skillbill.infrastructure.sqlite.review.reviewSummaryChanged
import skillbill.infrastructure.sqlite.review.upsertReviewRun
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnaddressedFindingsRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.WorkflowStatsRepository
import skillbill.ports.persistence.model.LearningResolution
import skillbill.ports.persistence.model.ReviewRepositoryStatsSnapshot
import skillbill.ports.persistence.model.TelemetryReconciliationRequest
import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.GoalWorkflowStats
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFinishedTelemetry
import java.nio.file.Path
import java.sql.Connection

class SQLiteUnitOfWork(
  private val connection: Connection,
  override val dbPath: Path,
) : UnitOfWork {
  override val reviews: ReviewRepository = SQLiteReviewRepository(connection)
  override val learnings: LearningRepository = SQLiteLearningRepository(connection)
  override val lifecycleTelemetry: LifecycleTelemetryRepository = LifecycleTelemetryStore(connection)
  override val telemetryReconciliation: TelemetryReconciliationRepository = SQLiteTelemetryReconciliationRepository(
    connection,
  )
  override val telemetryOutbox: TelemetryOutboxRepository = TelemetryOutboxStore(connection)
  override val workflowStates: WorkflowStateRepository = WorkflowStateStore(connection)
  override val workList: WorkListRepository = SQLiteWorkListRepository(connection)
  override val goalPlanningPreparations: skillbill.ports.persistence.GoalPlanningPreparationRepository =
    skillbill.db.workflow.GoalPlanningPreparationStore(connection)
  override val unaddressedFindings: UnaddressedFindingsRepository = SQLiteUnaddressedFindingsRepository(connection)
}

class SQLiteUnaddressedFindingsRepository(connection: Connection) : UnaddressedFindingsRepository {
  private val runtime = UnaddressedFindingsRuntime(connection)

  override fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) =
    runtime.replaceLedgerForPass(workflowId, reviewPassNumber, findings)

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> = runtime.fetchLedger(issueKey)

  override fun issueExists(issueKey: String): Boolean = runtime.issueExists(issueKey)
}

class SQLiteTelemetryReconciliationRepository(
  private val connection: Connection,
) : TelemetryReconciliationRepository {
  override fun reconcileStaleSessions(level: String) = reconcileStaleTelemetrySessions(connection, level)

  override fun reconcileStaleSessions(request: TelemetryReconciliationRequest) =
    reconcileStaleTelemetrySessions(connection, request)
}

class SQLiteWorkflowStatsRepository(
  private val connection: Connection,
) : WorkflowStatsRepository {
  override fun featureImplementStats(): FeatureImplementWorkflowStats =
    ReviewStatsRuntime.featureImplementStats(connection)

  override fun featureVerifyStats(): FeatureVerifyWorkflowStats = ReviewStatsRuntime.featureVerifyStats(connection)

  override fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats =
    ReviewStatsRuntime.featureTaskRuntimeStats(connection)

  override fun goalStats(): GoalWorkflowStats = ReviewStatsRuntime.goalStats(connection)
}

class SQLiteReviewRepository(
  private val connection: Connection,
) : ReviewRepository, WorkflowStatsRepository by SQLiteWorkflowStatsRepository(connection) {
  private companion object {
    const val REJECTED_OUTCOME_FIRST_PARAM_INDEX: Int = 3
  }

  override fun saveImportedReview(review: ImportedReview, sourcePath: String?) {
    val existingReviewSummary = existingReviewSummary(connection, review.reviewRunId)
    val existingFindings = ReviewRuntime.fetchImportedFindings(connection, review.reviewRunId)
    val summarySnapshotChanged = reviewSummaryChanged(existingReviewSummary, review, existingFindings)
    upsertReviewRun(connection, review, sourcePath)
    if (summarySnapshotChanged) {
      ReviewStatsRuntime.clearReviewFinishedTelemetryState(connection, review.reviewRunId)
    }
    if (existingFindings != review.findings) {
      replaceFindings(connection, review)
    }
  }

  override fun markOrchestrated(runId: String) {
    connection.prepareStatement(
      "UPDATE review_runs SET orchestrated_run = 1 WHERE review_run_id = ?",
    ).use { statement ->
      statement.setString(1, runId)
      statement.executeUpdate()
    }
  }

  override fun updateReviewFinishedTelemetryState(
    runId: String,
    enabled: Boolean,
    level: String,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = ReviewStatsRuntime.updateReviewFinishedTelemetryState(
    connection = connection,
    reviewRunId = runId,
    enabled = enabled,
    level = level,
    routedSkillPlatformSlugs = routedSkillPlatformSlugs,
  )

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
    routedSkillPlatformSlugs: Map<String, String>,
  ): ReviewFinishedTelemetry? = TriageRuntime.recordFeedbackWithoutTransaction(
    connection,
    request,
    telemetryOptions.copy(routedSkillPlatformSlugs = routedSkillPlatformSlugs),
  )

  override fun fetchNumberedFindings(runId: String): List<NumberedFinding> =
    ReviewRuntime.fetchNumberedFindings(connection, runId)

  override fun findingExists(runId: String, findingId: String): Boolean =
    ReviewRuntime.findingExists(connection, runId, findingId)

  override fun latestRejectedLearningSourceOutcome(runId: String, findingId: String): RejectedLearningSourceOutcome? {
    val placeholders = LearningsRuntime.rejectedFindingOutcomeTypes.joinToString(", ") { "?" }
    return connection.prepareStatement(
      """
      SELECT event_type, note
      FROM feedback_events
      WHERE review_run_id = ? AND finding_id = ? AND event_type IN ($placeholders)
      ORDER BY id DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, runId)
      statement.setString(2, findingId)
      LearningsRuntime.rejectedFindingOutcomeTypes.forEachIndexed { index, value ->
        statement.setString(index + REJECTED_OUTCOME_FIRST_PARAM_INDEX, value)
      }
      statement.executeQuery().use { resultSet ->
        if (resultSet.next()) {
          RejectedLearningSourceOutcome(
            eventType = resultSet.getString("event_type"),
            note = resultSet.getString("note").orEmpty(),
          )
        } else {
          null
        }
      }
    }
  }

  override fun reviewStats(runId: String?): ReviewRepositoryStatsSnapshot =
    ReviewStatsRuntime.statsSnapshot(connection, runId)
}

class SQLiteLearningRepository(
  private val connection: Connection,
) : LearningRepository {
  override fun list(status: String): List<LearningRecord> = SQLiteLearningStore.listLearnings(connection, status)

  override fun get(id: Int): LearningRecord = SQLiteLearningStore.getLearning(connection, id)

  override fun resolve(repoScopeKey: String?, skillName: String?): LearningResolution {
    val (resolvedRepoScopeKey, resolvedSkillName, rows) =
      SQLiteLearningStore.resolveLearnings(connection, repoScopeKey, skillName)
    return LearningResolution(
      repoScopeKey = resolvedRepoScopeKey,
      skillName = resolvedSkillName,
      records = rows,
    )
  }

  override fun saveSessionLearnings(reviewSessionId: String, learningsJson: String) {
    SQLiteLearningStore.saveSessionLearnings(connection, reviewSessionId, learningsJson)
  }

  override fun add(request: CreateLearningRequest, sourceValidation: LearningSourceValidation): Int =
    SQLiteLearningStore.addLearning(connection, request, sourceValidation)

  override fun edit(request: UpdateLearningRequest): LearningRecord =
    SQLiteLearningStore.editLearning(connection, request)

  override fun setStatus(id: Int, status: String): LearningRecord =
    SQLiteLearningStore.setLearningStatus(connection, id, status)

  override fun delete(id: Int) {
    SQLiteLearningStore.deleteLearning(connection, id)
  }
}
