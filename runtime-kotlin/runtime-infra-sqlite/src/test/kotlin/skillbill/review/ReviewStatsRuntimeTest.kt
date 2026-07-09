package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.db.telemetry.listJson
import skillbill.infrastructure.sqlite.SQLiteLearningStore
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.review.TriageRuntime
import skillbill.learnings.learningPayload
import skillbill.learnings.learningSummaryPayload
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.RejectedLearningSourceOutcome
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedReview
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.tempDbConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewStatsRuntimeTest {
  @Test
  fun `statsSnapshot summarizes latest outcomes`() {
    val (_, connection) = tempDbConnection("review-stats")
    connection.use {
      val review = importReviewedSample(connection)

      val stats = ReviewStatsRuntime.statsSnapshot(connection, review.reviewRunId).stats
      assertEquals(2, stats.totalFindings)
      assertEquals(1, stats.acceptedFindings)
      assertEquals(1, stats.rejectedFindings)
    }
  }

  @Test
  fun `statsSnapshot aggregates standalone and embedded review health`() {
    val (_, connection) = tempDbConnection("review-health-stats")
    connection.use {
      val review = importReviewedSample(connection)
      seedMixedReviewHealth(connection, review.reviewRunId)

      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(3, health.totalReviewPayloadRecords)
      assertEquals(2, health.includedReviewPayloadRecords)
      assertEquals(1, health.standaloneReviewPayloadRecords)
      assertEquals(1, health.embeddedReviewPayloadRecords)
      assertEquals(1, health.malformedReviewPayloadRecords)
      assertEquals(4, health.totalFindings)
      assertEquals(2.0, health.averageFindings)
      assertEquals(2.0, health.medianFindings)
      assertEquals(2.0, health.p90Findings)
      assertEquals(2, health.acceptedFindings)
      assertEquals(1, health.rejectedFindings)
      assertEquals(1, health.unresolvedFindings)
      assertEquals(0.5, health.acceptedRate)
      assertEquals(0.25, health.rejectedRate)
      assertEquals(0.25, health.unresolvedRate)
      assertEquals(2, health.severityCounts["Major"])
      assertEquals(2, health.confidenceCounts["High"])
      assertEquals(2, health.latestOutcomeCounts["finding_accepted"])
      assertEquals(1, health.latestOutcomeCounts["fix_rejected"])
      assertEquals(1, health.issueCategoryCounts["testing"])
      assertEquals(2, health.platformCounts["kotlin"])
      assertEquals(1, health.scopeCounts["branch_diff"])
      assertEquals(mapOf("standalone" to 1, "embedded" to 1, "malformed" to 1), health.sourceCounts)

      val runHealth = ReviewStatsRuntime.statsSnapshot(connection, review.reviewRunId).health
      assertEquals(1, runHealth.totalReviewPayloadRecords)
      assertEquals(1, runHealth.includedReviewPayloadRecords)
      assertEquals(0, runHealth.malformedReviewPayloadRecords)
      assertEquals(mapOf("standalone" to 1, "embedded" to 0, "malformed" to 0), runHealth.sourceCounts)
    }
  }

  @Test
  fun `statsSnapshot derives missing latest outcome counts from finding details`() {
    val (_, connection) = tempDbConnection("review-health-detail-outcomes")
    connection.use {
      TelemetryOutboxStore(connection).enqueue(
        "skillbill_review_finished",
        JsonSupport.mapToJsonString(
          mapOf(
            "review_run_id" to "rvw-detail-outcomes",
            "platform_slug" to "kotlin",
            "scope_type" to "branch_diff",
            "total_findings" to 2,
            "accepted_findings" to 1,
            "rejected_findings" to 1,
            "unresolved_findings" to 0,
            "accepted_finding_details" to listOf(
              mapOf(
                "finding_id" to "F-001",
                "issue_category" to "behavior_correctness",
                "severity" to "Major",
                "confidence" to "Medium",
                "outcome_type" to "fix_applied",
              ),
            ),
            "rejected_finding_details" to listOf(
              mapOf(
                "finding_id" to "F-002",
                "issue_category" to "testing_quality_gate",
                "severity" to "Minor",
                "confidence" to "Low",
                "outcome_type" to "false_positive",
              ),
            ),
          ),
        ),
      )

      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(1, health.latestOutcomeCounts["fix_applied"])
      assertEquals(1, health.latestOutcomeCounts["false_positive"])
      assertEquals(0, health.latestOutcomeCounts["finding_accepted"])
      assertEquals(0, health.latestOutcomeCounts["fix_rejected"])
    }
  }

  @Test
  fun `statsSnapshot returns zero health defaults for empty store`() {
    val (_, connection) = tempDbConnection("review-health-empty")
    connection.use {
      val health = ReviewStatsRuntime.statsSnapshot(connection, reviewRunId = null).health

      assertEquals(0, health.totalReviewPayloadRecords)
      assertEquals(0, health.includedReviewPayloadRecords)
      assertEquals(0.0, health.acceptedRate)
      assertEquals(mapOf("standalone" to 0, "embedded" to 0, "malformed" to 0), health.sourceCounts)
      assertEquals(mapOf("Blocker" to 0, "Major" to 0, "Minor" to 0), health.severityCounts)
    }
  }

  @Test
  fun `review-finished payload includes cached learnings and full finding details`() {
    val (_, connection) = tempDbConnection("review-finished-payload")
    connection.use {
      val review = importReviewedSample(connection)
      cacheSkillLearning(connection, review.reviewRunId, review.reviewSessionId)

      val anonymousPayload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "anonymous",
        )
      val fullPayload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "full",
        )

      assertEquals(1, anonymousPayload.learnings.appliedCount)
      assertEquals("L-001", anonymousPayload.learnings.appliedSummary)
      assertEquals("bill-kotlin-code-review", anonymousPayload.routedSkill)
      assertEquals("unstaged changes", anonymousPayload.reviewScope)
      assertEquals(review.reviewRunId, anonymousPayload.reviewRunId)
      assertEquals(review.reviewRunId, fullPayload.reviewRunId)
      assertEquals("kotlin", anonymousPayload.platformSlug)
      assertEquals("unstaged_changes", anonymousPayload.scopeType)
      assertEquals(1, anonymousPayload.findingStats.rejectedFindings)
      assertEquals(0.5, anonymousPayload.findingStats.rejectedRate)
      assertTrue(anonymousPayload.findingStats.acceptedFindingDetails.isNotEmpty())
      val anonymousRejectedFinding = anonymousPayload.findingStats.rejectedFindingDetails.single()
      val fullRejectedFinding = fullPayload.findingStats.rejectedFindingDetails.single()
      assertEquals("behavior_correctness", anonymousRejectedFinding.issueCategory)
      assertEquals("", anonymousRejectedFinding.description)
      assertEquals("", anonymousRejectedFinding.note)
      assertEquals("Installer prompt wording is inconsistent with the new flow.", fullRejectedFinding.description)
      assertEquals("Intentional wording", fullRejectedFinding.note)
      val anonymousSerializedPayload = anonymousPayload.toReviewFinishedTelemetryPayload().toPayload()
      val fullSerializedPayload = fullPayload.toReviewFinishedTelemetryPayload().toPayload()
      val anonymousSerializedRejectedFinding =
        (anonymousSerializedPayload["rejected_finding_details"] as List<*>).single() as Map<*, *>
      val fullSerializedRejectedFinding =
        (fullSerializedPayload["rejected_finding_details"] as List<*>).single() as Map<*, *>
      assertEquals(false, "description" in anonymousSerializedRejectedFinding)
      assertEquals(false, "note" in anonymousSerializedRejectedFinding)
      assertEquals("behavior_correctness", anonymousSerializedRejectedFinding["issue_category"])
      assertEquals(1, anonymousSerializedPayload["rejected_findings"])
      assertEquals(0.5, anonymousSerializedPayload["rejected_rate"])
      assertEquals("kotlin", anonymousSerializedPayload["platform_slug"])
      assertEquals("kotlin", anonymousSerializedPayload["review_platform"])
      assertEquals("kotlin", anonymousSerializedPayload["detected_stack"])
      assertEquals(false, anonymousSerializedPayload["fallback"])
      assertEquals("unstaged_changes", anonymousSerializedPayload["scope_type"])
      assertEquals(
        "Installer prompt wording is inconsistent with the new flow.",
        fullSerializedRejectedFinding["description"],
      )
      assertEquals("Intentional wording", fullSerializedRejectedFinding["note"])
      val serializedLearnings = anonymousSerializedPayload["learnings"] as Map<*, *>
      assertEquals(1, serializedLearnings["applied_count"])
      assertEquals(listOf("L-001"), serializedLearnings["applied_references"])
      assertEquals("L-001", serializedLearnings["applied_summary"])
    }
  }

  @Test
  fun `review-finished payload keeps zero finding rejected rate at zero`() {
    val (_, connection) = tempDbConnection("review-zero-finding-payload")
    connection.use {
      val review = ReviewParser.parseReview(ZERO_FINDING_REVIEW.trimIndent())
      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)

      val payload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "anonymous",
        ).toReviewFinishedTelemetryPayload().toPayload()

      assertEquals(0, payload["total_findings"])
      assertEquals(0, payload["rejected_findings"])
      assertEquals(0.0, payload["rejected_rate"])
      assertEquals(emptyList<Map<String, Any?>>(), payload["accepted_finding_details"])
      assertEquals(emptyList<Map<String, Any?>>(), payload["rejected_finding_details"])
      assertEquals("unknown", payload["platform_slug"])
      assertEquals("unknown", payload["review_platform"])
      assertEquals("unknown", payload["detected_stack"])
      assertEquals(false, payload["fallback"])
      assertEquals("branch_diff", payload["scope_type"])
    }
  }

  @Test
  fun `review-finished payload normalizes labels and preserves removed stack detail`() {
    val (_, connection) = tempDbConnection("review-finished-normalized-labels")
    connection.use {
      val review = ReviewParser.parseReview(
        SAMPLE_REVIEW
          .replace("rvw-20260402-001", "rvw-normalized-labels")
          .replace("rvs-20260402-001", "rvs-normalized-labels")
          .replace("Routed to: bill-kotlin-code-review", "Routed to: skill-bill:bill-kotlin-code-review")
          .replace("Detected stack: kotlin", "Detected stack: kotlin (Gradle JVM)")
          .trimIndent(),
      )
      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)

      val payload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "anonymous",
        ).toReviewFinishedTelemetryPayload().toPayload()

      assertEquals("bill-kotlin-code-review", payload["routed_skill"])
      assertEquals("kotlin", payload["review_platform"])
      assertEquals(payload["platform_slug"], payload["review_platform"])
      assertEquals(payload["platform_slug"], payload["detected_stack"])
      assertEquals("kotlin (Gradle JVM)", payload["detected_stack_detail"])
      assertEquals(false, payload["fallback"])
    }
  }

  @Test
  fun `review-finished payload emits structured fallback and nonblank unresolved routing defaults`() {
    val (_, connection) = tempDbConnection("review-finished-fallback-labels")
    connection.use {
      val review = ReviewParser.parseReview(
        """
        Review session ID: rvs-fallback-labels
        Review run ID: rvw-fallback-labels
        Detected review scope: branch diff
        Detected stack: kmp -> kotlin quality-check fallback
        Execution mode: inline

        ### 2. Risk Register
        No findings.
        """.trimIndent(),
      )
      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)

      val payload =
        ReviewStatsRuntime.buildReviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          level = "anonymous",
        ).toReviewFinishedTelemetryPayload().toPayload()

      assertEquals("unrouted", payload["routed_skill"])
      assertEquals("kmp", payload["review_platform"])
      assertEquals("kmp", payload["platform_slug"])
      assertEquals("kmp", payload["detected_stack"])
      assertEquals("kmp -> kotlin quality-check fallback", payload["detected_stack_detail"])
      assertEquals(true, payload["fallback"])
      assertEquals("kotlin_quality_check_fallback", payload["fallback_reason"])
    }
  }

  @Test
  fun `feature implement stats payload aggregates persisted session rows`() {
    val (_, connection) = tempDbConnection("workflow-stats")
    connection.use {
      insertFeatureImplementSession(connection)
      insertFeatureVerifySession(connection)

      val implementStats = ReviewStatsRuntime.featureImplementStats(connection)

      assertEquals(1, implementStats.totalRuns)
      assertEquals(1, implementStats.featureSizeCounts["MEDIUM"])
    }
  }

  @Test
  fun `feature implement stats separate source health data quality and duration buckets`() {
    val (_, connection) = tempDbConnection("feature-task-health-stats")
    connection.use {
      insertFeatureImplementSession(connection)
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_implement_sessions (
            session_id, source, feature_size, completion_status, started_at, finished_at
          ) VALUES
            ('fis-open', 'production', 'SMALL', '', '2026-04-23 10:00:00', NULL),
            ('fis-error', 'production', 'SMALL', 'error', '2026-04-23 10:00:00', '2026-04-23 10:01:00'),
            ('fis-plan', 'production', 'SMALL', 'abandoned_at_planning', '2026-04-23 10:00:00', '2026-04-23 10:02:00'),
            ('fis-impl', 'production', 'SMALL', 'abandoned_at_implementation', '2026-04-23 10:00:00', '2026-04-23 10:03:00'),
            ('fis-review', 'production', 'SMALL', 'abandoned_at_review', '2026-04-23 10:00:00', '2026-04-23 10:04:00'),
            ('fis-synthetic', 'synthetic', 'SMALL', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:00:00'),
            ('fis-test', 'test', 'SMALL', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:05:00'),
            ('bad-session', 'production', 'SMALL', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:05:00'),
            ('fis-unknown-source', 'fixture', 'SMALL', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:05:00'),
            ('fis-long', 'production', 'SMALL', 'completed', '2026-04-23 10:00:00', '2026-04-25 10:00:00'),
            ('fis-invalid-duration', 'production', 'SMALL', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:00:00')
          """.trimIndent(),
        )
      }

      val stats = ReviewStatsRuntime.featureImplementStats(connection)

      assertEquals(12, stats.rawRunCount)
      assertEquals(9, stats.sourceCounts["production"])
      assertEquals(1, stats.sourceCounts["test"])
      assertEquals(1, stats.sourceCounts["synthetic"])
      assertEquals(8, stats.validHealthDenominatorRuns)
      assertEquals(1, stats.openRuns)
      assertEquals(3, stats.completedRuns)
      assertEquals(1, stats.errorRuns)
      assertEquals(1, stats.abandonedAtPlanningRuns)
      assertEquals(1, stats.abandonedAtImplementationRuns)
      assertEquals(1, stats.abandonedAtReviewRuns)
      assertEquals(1, stats.malformedSessionIdRuns)
      assertEquals(1, stats.unknownSourceRuns)
      assertEquals(1, stats.syntheticZeroDurationRuns)
      assertEquals(1, stats.longRunningDurationRuns)
      assertEquals(1, stats.invalidDurationRuns)
      assertEquals(5, stats.normalDurationRuns)
      assertEquals(240.0, stats.averageDurationSeconds)
      assertEquals(180.0, stats.medianDurationSeconds)
      assertEquals(456.0, stats.p90DurationSeconds)
    }
  }

  @Test
  fun `feature implement stats report child step coverage and large feature segmentation`() {
    val (_, connection) = tempDbConnection("feature-task-size-health")
    connection.use {
      seedFeatureImplementSizeHealth(connection)

      val stats = ReviewStatsRuntime.featureImplementStats(connection)

      assertEquals(4, stats.validHealthDenominatorRuns)
      assertEquals(2, stats.childStepCoverage.runsWithChildSteps)
      assertEquals(1, stats.childStepCoverage.reviewChildStepRuns)
      assertEquals(1, stats.childStepCoverage.qualityCheckChildStepRuns)
      assertEquals(1, stats.childStepCoverage.prDescriptionChildStepRuns)
      assertEquals(1, stats.childStepCoverage.malformedChildStepRuns)
      assertEquals(0.5, stats.childStepCoverage.childStepCoverageRate)
      val largeStats = stats.featureSizeOutcomeStats.getValue("LARGE")
      assertEquals(2, largeStats.totalRuns)
      assertEquals(1, largeStats.completedRuns)
      assertEquals(1, largeStats.errorRuns)
      assertEquals(0.5, largeStats.errorRate)
      assertEquals(2, stats.largeFeatureHealth.denominatorRuns)
      assertEquals(1, stats.largeFeatureHealth.unhealthyRuns)
      assertEquals(0.5, stats.largeFeatureHealth.unhealthyRate)
      assertEquals(
        "Decompose large features or block earlier before implementation when LARGE runs show abandonment or errors.",
        stats.largeFeatureHealth.recommendation,
      )
    }
  }

  @Test
  fun `feature implement duplicate terminal calls emit updated duplicate accounting`() {
    val (_, connection) = tempDbConnection("feature-task-duplicate-terminal")
    connection.use {
      val store = LifecycleTelemetryStore(connection)
      val outbox = TelemetryOutboxStore(connection)
      store.featureImplementStarted(
        FeatureImplementStartedRecord(
          sessionId = "fis-duplicate",
          issueKeyProvided = true,
          issueKeyType = "other",
          specInputTypes = listOf("raw_text"),
          specWordCount = 100,
          featureSize = "SMALL",
          featureName = "duplicate-terminal",
          rolloutNeeded = false,
          acceptanceCriteriaCount = 1,
          openQuestionsCount = 0,
          specSummary = "Duplicate terminal test.",
        ),
        level = "anonymous",
      )
      val finished = featureImplementFinishedRecord("fis-duplicate")
      store.featureImplementFinished(finished, level = "anonymous")
      store.featureImplementFinished(finished, level = "anonymous")
      connection.createStatement().use { stmt ->
        stmt.executeUpdate(
          "UPDATE feature_implement_sessions SET started_at = finished_at WHERE session_id = 'fis-duplicate'",
        )
      }

      val pending = outbox.listPending(limit = null)
      assertEquals(
        listOf(
          "skillbill_feature_task_prose_started",
          "skillbill_feature_task_prose_finished",
          "skillbill_feature_task_prose_finished",
        ),
        pending.map { it.eventName },
      )
      val duplicatePayload = telemetryPayloads(pending, "skillbill_feature_task_prose_finished").last()
      assertEquals(1, duplicatePayload["duplicate_terminal_finished_events"])
      val stats = ReviewStatsRuntime.featureImplementStats(connection)
      assertEquals(1, stats.duplicateTerminalFinishedEvents)
      assertEquals(2, stats.dataQualityDebtRuns)
    }
  }

  @Test
  fun `feature implement finished preserves started source when finish omits source`() {
    val (_, connection) = tempDbConnection("feature-task-source-preserved")
    connection.use {
      val store = LifecycleTelemetryStore(connection)
      store.featureImplementStarted(
        FeatureImplementStartedRecord(
          sessionId = "fis-source",
          source = "test",
          issueKeyProvided = true,
          issueKeyType = "other",
          specInputTypes = listOf("raw_text"),
          specWordCount = 100,
          featureSize = "SMALL",
          featureName = "source-preserved",
          rolloutNeeded = false,
          acceptanceCriteriaCount = 1,
          openQuestionsCount = 0,
          specSummary = "Source preservation test.",
        ),
        level = "anonymous",
      )
      store.featureImplementFinished(featureImplementFinishedRecord("fis-source"), level = "anonymous")

      val stats = ReviewStatsRuntime.featureImplementStats(connection)

      assertEquals(0, stats.validHealthDenominatorRuns)
      assertEquals(0, stats.sourceCounts["production"])
      assertEquals(1, stats.sourceCounts["test"])
    }
  }

  @Test
  fun `feature verify stats payload aggregates persisted session rows`() {
    val (_, connection) = tempDbConnection("workflow-verify-stats")
    connection.use {
      insertFeatureVerifySession(connection)

      val verifyStats = ReviewStatsRuntime.featureVerifyStats(connection)

      assertEquals(1, verifyStats.totalRuns)
      assertEquals(1, verifyStats.runsWithGapsFound)
      assertEquals(1, verifyStats.historyRelevanceCounts["medium"])
    }
  }

  @Test
  fun `feature task runtime telemetry persists started then finished and enqueues each event once`() {
    val (_, connection) = tempDbConnection("feature-task-runtime-telemetry")
    connection.use {
      val store = LifecycleTelemetryStore(connection)
      val outbox = TelemetryOutboxStore(connection)
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord(
          sessionId = "ftr-1",
          featureSize = "MEDIUM",
          issueKey = "SKILL-65.1",
          featureName = "lifecycle-telemetry",
        ),
        level = "anonymous",
      )
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          sessionId = "ftr-1",
          completionStatus = "completed",
          completedPhaseIds = listOf("preplan", "plan", "implement"),
          phaseOutcomes = mapOf("preplan" to "completed", "plan" to "completed", "implement" to "completed"),
          lastIncompletePhase = "completed",
          blockedReason = "",
          resolvedBranch = "feat/SKILL-65.1",
        ),
        level = "anonymous",
      )
      // A redundant finished call (e.g. a resume) must re-save idempotently and never re-enqueue.
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          sessionId = "ftr-1",
          completionStatus = "completed",
          completedPhaseIds = listOf("preplan", "plan", "implement"),
          phaseOutcomes = mapOf("preplan" to "completed", "plan" to "completed", "implement" to "completed"),
          lastIncompletePhase = "completed",
          blockedReason = "",
          resolvedBranch = "feat/SKILL-65.1",
        ),
        level = "anonymous",
      )

      val pending = outbox.listPending(limit = null)
      assertEquals(
        listOf("skillbill_feature_task_runtime_started", "skillbill_feature_task_runtime_finished"),
        pending.map { it.eventName },
      )
      val finishedPayload = JsonSupport.parseObjectOrNull(
        pending.single { it.eventName == "skillbill_feature_task_runtime_finished" }.payloadJson,
      )
      assertEquals("completed", finishedPayload?.get("completion_status")?.let { it.toString().trim('"') })
      assertEquals("completed", finishedPayload?.get("last_incomplete_phase")?.let { it.toString().trim('"') })
      assertEquals("", finishedPayload?.get("blocked_reason")?.let { it.toString().trim('"') })

      val stats = ReviewStatsRuntime.featureTaskRuntimeStats(connection)
      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertEquals(1, stats.completedRuns)
      assertEquals(1, stats.completionStatusCounts["completed"])
      assertEquals(3, stats.phaseOutcomeCounts["completed"])
      assertEquals(1, stats.featureSizeCounts["MEDIUM"])
    }
  }

  @Test
  fun `feature task runtime stats excludes null token rows from average and counts only valued rows`() {
    val (_, connection) = tempDbConnection("feature-task-runtime-token-aggregation")
    connection.use {
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, completion_status, finished_at, estimated_total_tokens)
          VALUES
            ('ftr-token-null', 'completed', '2026-04-23 10:05:00', NULL),
            ('ftr-token-100', 'completed', '2026-04-23 10:06:00', 100),
            ('ftr-token-200', 'completed', '2026-04-23 10:07:00', 200)
          """.trimIndent(),
        )
      }

      val stats = ReviewStatsRuntime.featureTaskRuntimeStats(connection)

      assertEquals(2, stats.estimatedTokenRunsWithValue)
      assertEquals(150.0, stats.averageEstimatedTotalTokens)
    }
  }

  @Test
  fun `feature implement stats excludes null token rows from average and counts only valued rows`() {
    val (_, connection) = tempDbConnection("feature-task-token-aggregation")
    connection.use {
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_implement_sessions (session_id, source, completion_status, started_at, finished_at, estimated_total_tokens)
          VALUES
            ('fis-token-null', 'production', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:05:00', NULL),
            ('fis-token-100', 'production', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:06:00', 100),
            ('fis-token-200', 'production', 'completed', '2026-04-23 10:00:00', '2026-04-23 10:07:00', 200)
          """.trimIndent(),
        )
      }

      val stats = ReviewStatsRuntime.featureImplementStats(connection)

      assertEquals(2, stats.estimatedTokenRunsWithValue)
      assertEquals(150.0, stats.averageEstimatedTotalTokens)
    }
  }

  @Test
  fun `feature task runtime stats counts blocked and decomposed completion statuses`() {
    val (_, connection) = tempDbConnection("feature-task-runtime-stats")
    connection.use {
      val store = LifecycleTelemetryStore(connection)
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord("ftr-blocked", "SMALL", "SKILL-1", "blocked-run"),
        level = "anonymous",
      )
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          sessionId = "ftr-blocked",
          completionStatus = "blocked",
          completedPhaseIds = listOf("preplan"),
          phaseOutcomes = mapOf("preplan" to "completed", "plan" to "blocked"),
          lastIncompletePhase = "plan",
          blockedReason = "schema: gate failed",
          resolvedBranch = "",
        ),
        level = "anonymous",
      )
      store.featureTaskRuntimeStarted(
        FeatureTaskRuntimeStartedRecord("ftr-decomposed", "LARGE", "SKILL-2", "decomposed-run"),
        level = "anonymous",
      )
      store.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRecord(
          sessionId = "ftr-decomposed",
          completionStatus = "decomposed_at_planning",
          completedPhaseIds = listOf("preplan", "plan"),
          phaseOutcomes = mapOf("preplan" to "completed", "plan" to "completed"),
          lastIncompletePhase = "decomposed_at_planning",
          blockedReason = "",
          resolvedBranch = "",
        ),
        level = "anonymous",
      )

      val stats = ReviewStatsRuntime.featureTaskRuntimeStats(connection)
      assertEquals(2, stats.totalRuns)
      assertEquals(1, stats.blockedRuns)
      assertEquals(1, stats.decomposedRuns)
      assertEquals(1, stats.completionStatusCounts["blocked"])
      assertEquals(1, stats.completionStatusCounts["decomposed_at_planning"])
      assertEquals(1, stats.phaseOutcomeCounts["blocked"])

      val payloads = telemetryPayloads(
        TelemetryOutboxStore(connection).listPending(limit = null),
        "skillbill_feature_task_runtime_finished",
      )
      val blockedPayload = payloads.single { it["session_id"] == "ftr-blocked" }
      assertEquals("plan", blockedPayload["last_incomplete_phase"])
      assertEquals("schema: gate failed", blockedPayload["blocked_reason"])
      val decomposedPayload = payloads.single { it["session_id"] == "ftr-decomposed" }
      assertEquals("decomposed_at_planning", decomposedPayload["last_incomplete_phase"])
      assertEquals("", decomposedPayload["blocked_reason"])
    }
  }
}

private fun importReviewedSample(connection: java.sql.Connection): ImportedReview {
  val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())
  ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
  recordFindingOutcome(connection, review.reviewRunId, "F-001", "finding_accepted", "")
  recordFindingOutcome(connection, review.reviewRunId, "F-002", "fix_rejected", "Intentional wording")
  return review
}

private fun recordFindingOutcome(
  connection: java.sql.Connection,
  reviewRunId: String,
  findingId: String,
  eventType: String,
  note: String,
) {
  TriageRuntime.recordFeedback(
    connection = connection,
    request =
    FeedbackRequest(
      reviewRunId = reviewRunId,
      findingIds = listOf(findingId),
      eventType = eventType,
      note = note,
    ),
    telemetryOptions = FeedbackTelemetryOptions(enabled = false, level = "anonymous"),
  )
}

private fun telemetryPayloads(
  records: List<skillbill.ports.persistence.model.TelemetryOutboxRecord>,
  eventName: String,
): List<Map<String, Any?>> = records.filter { it.eventName == eventName }.map { record ->
  JsonSupport.parseObjectOrNull(record.payloadJson)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: emptyMap()
}

private fun cacheSkillLearning(connection: java.sql.Connection, reviewRunId: String, reviewSessionId: String) {
  val learningId =
    SQLiteLearningStore.addLearning(
      connection = connection,
      request =
      CreateLearningRequest(
        scope = LearningScope.SKILL,
        scopeKey = "bill-kotlin-code-review",
        title = "Match wording",
        ruleText = "Keep wording aligned with routed skill output.",
        rationale = "",
        sourceReviewRunId = reviewRunId,
        sourceFindingId = "F-002",
      ),
      sourceValidation =
      LearningSourceValidation(
        reviewRunId = reviewRunId,
        findingId = "F-002",
        rejectedOutcome = RejectedLearningSourceOutcome("fix_rejected", "Intentional wording"),
      ),
    )
  val learningPayload = learningPayload(SQLiteLearningStore.getLearning(connection, learningId))
  SQLiteLearningStore.saveSessionLearnings(
    connection = connection,
    reviewSessionId = reviewSessionId,
    learningsJson =
    JsonSupport.mapToJsonString(
      mapOf(
        "applied_learning_count" to 1,
        "applied_learning_references" to listOf(learningPayload["reference"]),
        "applied_learnings" to learningPayload["reference"],
        "scope_counts" to mapOf("global" to 0, "repo" to 0, "skill" to 1),
        "learnings" to listOf(learningSummaryPayload(learningPayload)),
      ),
    ),
  )
}

private const val ZERO_FINDING_REVIEW: String =
  """
  Routed to: bill-code-review
  Review session ID: rvs-20260402-zero
  Review run ID: rvw-20260402-zero
  Detected review scope: branch diff
  Detected stack: unknown
  Execution mode: inline

  ### 2. Risk Register
  No findings.
  """

private fun insertFeatureImplementSession(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_implement_sessions (
        session_id,
        feature_size,
        rollout_needed,
        acceptance_criteria_count,
        spec_word_count,
        completion_status,
        feature_flag_used,
        feature_flag_pattern,
        files_created,
        files_modified,
        tasks_completed,
        review_iterations,
        audit_result,
        audit_iterations,
        validation_result,
        boundary_history_written,
        boundary_history_value,
        pr_created,
        started_at,
        finished_at
      ) VALUES (
        'fis-1',
        'MEDIUM',
        1,
        3,
        200,
        'completed',
        0,
        'none',
        2,
        4,
        5,
        1,
        'all_pass',
        1,
        'pass',
        0,
        'low',
        1,
        '2026-04-23 10:00:00',
        '2026-04-23 10:10:00'
      )
      """.trimIndent(),
    )
  }
}

private fun seedMixedReviewHealth(connection: java.sql.Connection, reviewRunId: String) {
  ReviewStatsRuntime.updateReviewFinishedTelemetryState(connection, reviewRunId, enabled = true, level = "full")
  insertFeatureImplementSessionWithChildSteps(
    connection,
    FeatureImplementSessionFixture(
      sessionId = "fis-review-child",
      featureSize = "LARGE",
      completionStatus = "completed",
      childSteps = listOf(embeddedReviewChildStep()),
    ),
  )
  insertMalformedFeatureImplementChildSteps(connection, "fis-malformed-review-child")
}

private fun embeddedReviewChildStep(): Map<String, Any?> = mapOf(
  "skill" to "bill-kotlin-code-review",
  "review_run_id" to "rvw-embedded",
  "review_session_id" to "rvs-embedded",
  "platform_slug" to "kotlin",
  "scope_type" to "branch_diff",
  "total_findings" to 2,
  "accepted_findings" to 1,
  "rejected_findings" to 0,
  "unresolved_findings" to 1,
  "accepted_finding_details" to listOf(
    mapOf(
      "finding_id" to "F-EMBEDDED-1",
      "issue_category" to "testing",
      "severity" to "Major",
      "confidence" to "high",
      "outcome_type" to "finding_accepted",
    ),
  ),
  "rejected_finding_details" to emptyList<Map<String, Any?>>(),
  "latest_outcome_counts" to mapOf("finding_accepted" to 1),
)

private fun seedFeatureImplementSizeHealth(connection: java.sql.Connection) {
  insertFeatureImplementSessionWithChildSteps(
    connection,
    FeatureImplementSessionFixture(
      sessionId = "fis-large-completed",
      featureSize = "LARGE",
      completionStatus = "completed",
      childSteps = listOf(
        mapOf("skill" to "bill-code-check", "result" to "pass", "iterations" to 1),
        mapOf("skill" to "bill-pr-description", "pr_created" to true),
      ),
    ),
  )
  insertFeatureImplementSessionWithChildSteps(
    connection,
    FeatureImplementSessionFixture(
      sessionId = "fis-large-error",
      featureSize = "LARGE",
      completionStatus = "error",
      childSteps = listOf(mapOf("skill" to "bill-kotlin-code-review", "rejected_findings" to 1)),
    ),
  )
  insertFeatureImplementSessionWithChildSteps(
    connection,
    FeatureImplementSessionFixture(
      sessionId = "fis-medium-open",
      featureSize = "MEDIUM",
      completionStatus = "",
      childSteps = emptyList(),
      finishedAt = null,
    ),
  )
  insertMalformedFeatureImplementChildSteps(connection, "fis-malformed-child")
}

private data class FeatureImplementSessionFixture(
  val sessionId: String,
  val featureSize: String,
  val completionStatus: String,
  val childSteps: List<Map<String, Any?>>,
  val finishedAt: String? = "2026-04-23 10:05:00",
)

private fun insertFeatureImplementSessionWithChildSteps(
  connection: java.sql.Connection,
  fixture: FeatureImplementSessionFixture,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_implement_sessions (
      session_id,
      source,
      feature_size,
      completion_status,
      child_steps_json,
      started_at,
      finished_at
    ) VALUES (?, 'production', ?, ?, ?, '2026-04-23 10:00:00', ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, fixture.sessionId)
    statement.setString(2, fixture.featureSize)
    statement.setString(3, fixture.completionStatus)
    statement.setString(4, listJson(fixture.childSteps))
    statement.setString(5, fixture.finishedAt)
    statement.executeUpdate()
  }
}

private fun insertMalformedFeatureImplementChildSteps(connection: java.sql.Connection, sessionId: String) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_implement_sessions (
        session_id, source, feature_size, completion_status, child_steps_json, started_at, finished_at
      ) VALUES (
        '$sessionId', 'production', 'SMALL', 'completed', '{not json',
        '2026-04-23 10:00:00', '2026-04-23 10:01:00'
      )
      """.trimIndent(),
    )
  }
}

private fun featureImplementFinishedRecord(sessionId: String): FeatureImplementFinishedRecord =
  FeatureImplementFinishedRecord(
    sessionId = sessionId,
    completionStatus = "completed",
    planCorrectionCount = 0,
    planTaskCount = 1,
    planPhaseCount = 1,
    featureFlagUsed = false,
    featureFlagPattern = "none",
    filesCreated = 0,
    filesModified = 1,
    tasksCompleted = 1,
    reviewIterations = 1,
    auditResult = "all_pass",
    auditIterations = 1,
    validationResult = "pass",
    boundaryHistoryWritten = false,
    boundaryHistoryValue = "none",
    prCreated = false,
    planDeviationNotes = "",
    childSteps = emptyList(),
  )

private fun insertFeatureVerifySession(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate(
      """
      INSERT INTO feature_verify_sessions (
        session_id,
        acceptance_criteria_count,
        rollout_relevant,
        spec_summary,
        feature_flag_audit_performed,
        review_iterations,
        audit_result,
        completion_status,
        gaps_found,
        history_relevance,
        history_helpfulness,
        started_at,
        finished_at
      ) VALUES (
        'fvr-1',
        2,
        1,
        'Verify review domain.',
        1,
        2,
        'all_pass',
        'completed',
        '["gap"]',
        'medium',
        'high',
        '2026-04-23 10:00:00',
        '2026-04-23 10:05:00'
      )
      """.trimIndent(),
    )
  }
}
