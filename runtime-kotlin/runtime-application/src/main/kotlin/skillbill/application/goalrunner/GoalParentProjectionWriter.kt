package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.decomposition.withParentStatus
import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.featuretask.FeatureTaskRuntimeCrashLiveness
import skillbill.application.featuretask.asPendingForOperatorResume
import skillbill.application.featuretask.phaseLedgerFrom
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.goalrunner.planning.GoalChildPlanningHydrator
import skillbill.application.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.normalizeRequiredIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentOrCorruptFallback
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.generateWorkflowId
import skillbill.application.workflow.repoRoot
import skillbill.application.workflow.requireRuntimeModeForEngineWrite
import skillbill.application.workflow.toRecord
import skillbill.application.workflow.toSnapshot
import kotlin.coroutines.cancellation.CancellationException
import skillbill.contracts.JsonSupport
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalProgressEventSchemaError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.LegacyProseWorkflowError
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.goalrunner.model.GoalChildWorkflowDeletionScope
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifacts
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalObservabilityEvent
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry


internal class GoalParentProjectionWriter(
  private val engine: WorkflowEngine,
  private val validator: DecompositionManifestValidator,
) {
  fun artifacts(manifest: DecompositionManifest, existingArtifactsJson: String? = null): Map<String, Any?> =
    LinkedHashMap(
      existingArtifactsJson
        ?.takeIf(String::isNotBlank)
        ?.let(::decodeArtifacts)
        .orEmpty(),
    ).apply {
      remove(GOAL_REVIEW_POLICY_ARTIFACT_KEY)
      remove(GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY)
      put(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
        encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
      )
    }

  fun rewrite(unitOfWork: UnitOfWork, existing: WorkflowStateSnapshot) {
    val manifest = existing.decompositionRuntime(validator)
      ?: error("Goal parent workflow '${existing.workflowId}' has no decomposition manifest.")
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existing,
      WorkflowUpdateInput(
        workflowStatus = existing.workflowStatus,
        currentStepId = existing.currentStepId,
        stepUpdates = null,
        artifactsPatch = artifacts(manifest, existing.artifactsJson),
        sessionId = existing.sessionId,
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
  }
}
