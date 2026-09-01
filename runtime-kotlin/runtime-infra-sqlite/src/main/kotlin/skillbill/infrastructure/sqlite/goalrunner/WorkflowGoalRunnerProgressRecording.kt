package skillbill.infrastructure.sqlite.goalrunner
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.persistence.AttemptLedgerAccumulator
import skillbill.ports.goalrunner.persistence.WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY
import skillbill.ports.goalrunner.persistence.WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT
import skillbill.ports.goalrunner.persistence.backwardEdgeCountsFromLedger
import skillbill.ports.goalrunner.persistence.declaredProgressEventFrom
import skillbill.ports.goalrunner.persistence.decodeWorkflowSteps
import skillbill.ports.goalrunner.persistence.goalContinuation
import skillbill.ports.goalrunner.persistence.maxHistorySequence
import skillbill.ports.goalrunner.persistence.model.HistoryArtifactAppend
import skillbill.ports.goalrunner.persistence.progressEventFrom
import skillbill.ports.goalrunner.persistence.progressToken
import skillbill.ports.goalrunner.persistence.summary
import skillbill.ports.goalrunner.persistence.toArtifactMap
import skillbill.ports.goalrunner.persistence.toProgressEvent
import skillbill.ports.goalrunner.persistence.workflowFamilyFor
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.phaseartifacts.phaseRecordsFrom
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.persistence.GoalObservabilityArtifacts
import skillbill.ports.workflow.persistence.model.GoalObservabilityRuntimeEventInput
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.model.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts

internal class WorkflowGoalRunnerProgressRecording(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  private val goalProgressEventValidator: GoalProgressEventValidator,
) {
  fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    database.read(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read null
      val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      engine.snapshotView(family.definition, record)
      val steps = decodeWorkflowSteps(record.stepsJson)
      val artifacts = decodeArtifacts(record.artifactsJson)
      val finishCompleted = steps.any { step -> step.stepId == "pr" && step.status == "completed" }
      val currentStep = if (record.workflowStatus == "completed" || finishCompleted) "pr" else record.currentStepId
      val progressEvent = progressEventFrom(artifacts)
      val declaredProgressEvent = declaredProgressEventFrom(artifacts)
      val observabilityEvent = runCatching {
        goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)
      }.getOrNull()
      GoalRunnerWorkflowProgress(
        workflowId = record.workflowId,
        workflowStatus = record.workflowStatus,
        currentStepId = currentStep,
        progressToken = record.progressToken(),
        latestDurableProgressEvent = progressEvent,
        latestGoalObservabilityEvent = observabilityEvent?.toProgressEvent(),
        latestDeclaredProgressEvent = declaredProgressEvent,
        latestLivenessSignal = observabilityEvent?.compactLivenessSummary()
          ?: progressEvent?.summary()
          ?: "workflow_status=${record.workflowStatus}; step=$currentStep",
        lastSnapshotUpdatedAt = record.updatedAt,
      )
    }

  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest, dbPathOverride: String?): Boolean =
    database.transaction(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val record = family.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val observabilityPatch = GoalObservabilityArtifacts.patchForRuntimeEvent(
        input = GoalObservabilityRuntimeEventInput(
          artifacts = artifacts,
          request = request,
        ),
        validator = goalObservabilityEventValidator,
      )
      val updated = engine.updateRecord(
        family.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = observabilityPatch,
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      family.save(unitOfWork.workflowStates, updated)
      true
    }

  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean {
    val entryMap = request.event.toArtifactMap()
    goalProgressEventValidator.validate(entryMap, GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY)
    return appendHistoryArtifact(
      HistoryArtifactAppend(
        workflowId = request.workflowId,
        latestKey = GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY,
        historyKey = GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY,
        retentionLimit = GOAL_PROGRESS_HISTORY_LIMIT,
        entryMap = entryMap,
      ),
      dbPathOverride,
    )
  }

  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest, dbPathOverride: String?): Boolean =
    appendHistoryArtifact(
      HistoryArtifactAppend(
        workflowId = request.workflowId,
        latestKey = null,
        historyKey = GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY,
        retentionLimit = GOAL_ATTEMPT_LEDGER_LIMIT,
        entryMap = request.entry.toArtifactMap(),
      ),
      dbPathOverride,
    )

  fun progressEvents(workflowId: String, dbPathOverride: String?): List<Map<String, Any?>> =
    database.transaction(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId)
        ?: return@transaction emptyList()
      val record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction emptyList()
      (decodeArtifacts(record.artifactsJson)[GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY] as? List<*>)
        .orEmpty()
        .mapNotNull { item -> item as? Map<*, *> }
        .mapNotNull { item -> JsonSupport.anyToStringAnyMap(item) }
    }

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean = database.transaction(dbPathOverride) { unitOfWork ->
    val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val record = family.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existing = (artifacts[WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
      .map { item -> JsonSupport.anyToStringAnyMap(item) }
    val updatedOutcomes = (existing + outcomes.map(GoalRunnerWorkerSubtaskRequestOutcome::toArtifactMap))
      .takeLast(WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT)
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = mapOf(WORKER_SUBTASK_REQUEST_OUTCOMES_ARTIFACT_KEY to updatedOutcomes),
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(unitOfWork.workflowStates, updated)
    true
  }

  fun ledgerSequenceWatermarks(issueKey: String, dbPathOverride: String?): GoalRunnerLedgerSequenceWatermarks =
    database.read(dbPathOverride) { unitOfWork ->
      val normalizedIssueKey = issueKey.trim()
      var maxLedger: Int? = null
      var maxProgress: Int? = null
      val backwardEdgeCounts = mutableMapOf<String, Int>()
      listOf(WorkflowFamily.TASK_RUNTIME).forEach { family ->
        family.list(unitOfWork.workflowStates, Int.MAX_VALUE).forEach { snapshot ->
          val artifacts = decodeArtifacts(snapshot.artifactsJson)
          if (goalContinuation(artifacts)?.issueKey != normalizedIssueKey) {
            return@forEach
          }
          maxLedger = maxHistorySequence(artifacts, GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY, maxLedger)
          maxProgress = maxHistorySequence(artifacts, GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY, maxProgress)
          backwardEdgeCountsFromLedger(artifacts).forEach { (key, count) ->
            backwardEdgeCounts.merge(key, count, ::maxOf)
          }
        }
      }
      GoalRunnerLedgerSequenceWatermarks(
        maxLedgerSequence = maxLedger,
        maxProgressSequence = maxProgress,
        backwardEdgeCounts = backwardEdgeCounts,
      )
    }

  fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String?): Map<String, Int> =
    database.read(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, workflowId) ?: return@read emptyMap()
      val record = family.get(unitOfWork.workflowStates, workflowId) ?: return@read emptyMap()
      val artifacts = decodeArtifacts(record.artifactsJson)
      val result = mutableMapOf<String, Int>()
      phaseRecordsFrom(artifacts).values.forEach { phaseRecord ->
        val loopId = phaseRecord.loopId ?: return@forEach
        val edgeIteration = phaseRecord.edgeIteration ?: return@forEach
        result.merge(loopId, edgeIteration, ::maxOf)
      }
      result
    }

  fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String?): GoalRunnerAttemptLedgerSummary =
    database.read(dbPathOverride) { unitOfWork ->
      val normalizedIssueKey = issueKey.trim()
      val acc = AttemptLedgerAccumulator()
      listOf(WorkflowFamily.TASK_RUNTIME).forEach { family ->
        family.list(unitOfWork.workflowStates, Int.MAX_VALUE).forEach { snapshot ->
          val artifacts = decodeArtifacts(snapshot.artifactsJson)
          if (goalContinuation(artifacts)?.issueKey != normalizedIssueKey) return@forEach
          (artifacts[GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY] as? List<*>).orEmpty().forEach { item ->
            (item as? Map<*, *>)?.let(acc::accumulate)
          }
        }
      }
      acc.toSummary()
    }

  private fun appendHistoryArtifact(append: HistoryArtifactAppend, dbPathOverride: String?): Boolean =
    database.transaction(dbPathOverride) { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, append.workflowId)
        ?: return@transaction false
      val record = family.get(unitOfWork.workflowStates, append.workflowId)
        ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existing = (artifacts[append.historyKey] as? List<*>)
        .orEmpty()
        .mapNotNull { item -> item as? Map<*, *> }
        .mapNotNull { item -> JsonSupport.anyToStringAnyMap(item) }
      val updatedHistory = appendBoundedHistoryBySequence(existing, append.entryMap, append.retentionLimit)
      val patch = buildMap<String, Any?> {
        put(append.historyKey, updatedHistory)
        append.latestKey?.let { put(it, append.entryMap) }
      }
      val updated = engine.updateRecord(
        family.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = patch,
          sessionId = record.sessionId.orEmpty(),
        ),
      )
      family.save(unitOfWork.workflowStates, updated)
      true
    }
}
