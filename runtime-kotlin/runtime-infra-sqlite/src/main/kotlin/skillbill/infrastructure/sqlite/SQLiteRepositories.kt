package skillbill.infrastructure.sqlite

import skillbill.db.TelemetryOutboxStore
import skillbill.db.WorkflowStateStore
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.review.TriageRuntime
import skillbill.infrastructure.sqlite.review.existingReviewSummary
import skillbill.infrastructure.sqlite.review.replaceFindings
import skillbill.infrastructure.sqlite.review.reviewSummaryChanged
import skillbill.infrastructure.sqlite.review.upsertReviewRun
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningRecord
import skillbill.learnings.LearningSourceValidation
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.RejectedLearningSourceOutcome
import skillbill.learnings.UpdateLearningRequest
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LearningResolution
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.review.FeedbackRequest
import skillbill.review.FeedbackTelemetryOptions
import skillbill.review.ImportedReview
import skillbill.review.NumberedFinding
import java.nio.file.Path
import java.sql.Connection

class SQLiteUnitOfWork(
  private val connection: Connection,
  override val dbPath: Path,
) : UnitOfWork {
  override val reviews: ReviewRepository = SQLiteReviewRepository(connection)
  override val learnings: LearningRepository = SQLiteLearningRepository(connection)
  override val telemetryOutbox: TelemetryOutboxRepository = TelemetryOutboxStore(connection)
  override val workflowStates: WorkflowStateRepository = WorkflowStateStore(connection)
}

class SQLiteReviewRepository(
  private val connection: Connection,
) : ReviewRepository {
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

  override fun updateReviewFinishedTelemetryState(runId: String, enabled: Boolean, level: String): Map<String, Any?>? =
    ReviewStatsRuntime.updateReviewFinishedTelemetryState(
      connection = connection,
      reviewRunId = runId,
      enabled = enabled,
      level = level,
    )

  override fun recordFeedback(
    request: FeedbackRequest,
    telemetryOptions: FeedbackTelemetryOptions,
  ): Map<String, Any?>? = TriageRuntime.recordFeedbackWithoutTransaction(connection, request, telemetryOptions)

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

  override fun reviewStatsPayload(runId: String?): Map<String, Any?> =
    ReviewStatsRuntime.statsPayload(connection, runId)

  override fun featureImplementStatsPayload(): Map<String, Any?> =
    ReviewStatsRuntime.featureImplementStatsPayload(connection)

  override fun featureVerifyStatsPayload(): Map<String, Any?> = ReviewStatsRuntime.featureVerifyStatsPayload(connection)
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
