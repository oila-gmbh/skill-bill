package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.model.GoalRunnerAppliedRepair
import skillbill.application.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.model.GoalRunnerWedgeClass
import skillbill.application.model.GoalRunnerWedgeFinding
import skillbill.application.workflow.WorkflowFamily
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder
import java.nio.file.Path
import java.time.Instant

/** Append-only operator repair evidence; additionalProperties:true so no workflow-state schema bump. */
internal const val GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY: String = "goal_child_repair_evidence"

internal const val PASSED_VALIDATION_DEPTH: String = "validation_depth_present"
internal const val PASSED_REVIEW_BASE: String = "review_base_reachable"
internal const val PASSED_REMEDIATION_BASE: String = "remediation_base_reachable_or_absent"
internal const val PASSED_CONTINUATION_OUTCOME: String = "continuation_outcome_corroborated_or_absent"

/**
 * Shared diagnosis/repair helpers for [WorkflowGoalRunnerOutcomeStore]. Reachability uses
 * [WorkflowGitOperations.isCommitAncestor] (subtask 2); stale blocked outcomes use
 * [derivedTerminalOutcomeFor] / [nonCompleteStoredOutcomeIsCorroborated] (subtask 4).
 */
internal class GoalRunnerChildRepairOperations(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
) {
  fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis {
    val record = WorkflowFamily.TASK_RUNTIME.get(workflowStates, workflowId)
      ?: return healthyDiagnosis(subtaskId, workflowId)
    val artifacts = decodeArtifacts(record.artifactsJson)
    val wedges = mutableListOf<GoalRunnerWedgeFinding>()
    val passed = mutableListOf<String>()

    diagnoseValidationDepth(artifacts, wedges, passed)
    diagnoseReviewBases(artifacts, repoRoot, wedges, passed)
    diagnoseStaleBlockedOutcome(record, artifacts, issueKey, subtaskId, wedges, passed)

    return GoalRunnerChildWedgeDiagnosis(
      subtaskId = subtaskId,
      workflowId = workflowId,
      wedges = wedges,
      passedChecks = passed,
    )
  }

  fun apply(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    wedgeClasses: List<GoalRunnerWedgeClass>,
    subtasks: List<DecompositionSubtask>,
    repoRoot: Path,
  ): List<GoalRunnerAppliedRepair> {
    if (wedgeClasses.isEmpty()) return emptyList()
    val record = WorkflowFamily.TASK_RUNTIME.get(workflowStates, workflowId) ?: return emptyList()
    val artifacts = decodeArtifacts(record.artifactsJson)
    val patch = linkedMapOf<String, Any?>()
    val applied = mutableListOf<GoalRunnerAppliedRepair>()
    val evidenceEntries = mutableListOf<Map<String, Any?>>()
    val priorEvidence = (artifacts[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as? List<*>).orEmpty()

    var workingContinuation = continuationArtifact(artifacts)
    var workingReview = GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state

    for (wedgeClass in wedgeClasses.distinct()) {
      when (wedgeClass) {
        GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH -> {
          val continuation = workingContinuation ?: continue
          if (continuation.validationDepth != null) continue
          val depth = validationDepthForSubtask(subtasks, subtaskId)
          val healed = continuation.copy(validationDepth = depth)
          workingContinuation = healed
          patch[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] = healed.toArtifactMap()
          val repair = GoalRunnerAppliedRepair(
            subtaskId = subtaskId,
            workflowId = workflowId,
            wedgeClass = wedgeClass,
            field = wedgeClass.durableField,
            priorValue = null,
            newValue = depth.wireValue,
          )
          applied += repair
          evidenceEntries += repairEvidenceMap(repair)
        }
        GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE,
        GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE,
        -> {
          val review = workingReview ?: continue
          val continuation = workingContinuation ?: continue
          val goalBranch = continuation.goalBranch
          val failedSha = when (wedgeClass) {
            GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE -> review.reviewBaseSha
            GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE -> review.remediationBaseSha
          } ?: continue
          if (!isUnreachable(repoRoot, failedSha)) continue
          val recovered = gitOperations.recoverGoalSubtaskReviewBaseline(
            repoRoot,
            GoalSubtaskReviewBaselineRecoveryRequest(
              unreachableSha = failedSha,
              failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
              baselineUntrackedPaths = review.baselineUntrackedPaths,
            ),
            goalBranch,
          )
          if (!recovered.ok) {
            error(
              "Goal repair could not recover unreachable ${wedgeClass.durableField} " +
                "'$failedSha' on branch '$goalBranch': ${recovered.error}",
            )
          }
          val recoveredBaseline = requireNotNull(recovered.baseline)
          val replacement = recoveredBaseline.reviewBaseSha
          val healed = when (wedgeClass) {
            GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE -> review.copy(
              reviewBaseSha = replacement,
              baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths,
            )
            GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE ->
              review.copy(remediationBaseSha = replacement)
          }
          workingReview = healed
          patch[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = healed.toArtifactMap()
          val recoveryEvidence = linkedMapOf<String, Any?>(
            "original_sha" to failedSha,
            "replacement_sha" to replacement,
            "repointed_field" to wedgeClass.durableField,
            "failure_reason" to "base_not_ancestor",
            "failure_message" to "Operator goal repair repointed unreachable ${wedgeClass.durableField}.",
            "goal_branch" to goalBranch,
          )
          val priorRecoveries = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
          val existingRecoveries =
            (patch[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>) ?: priorRecoveries
          patch[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] = existingRecoveries + recoveryEvidence
          val repair = GoalRunnerAppliedRepair(
            subtaskId = subtaskId,
            workflowId = workflowId,
            wedgeClass = wedgeClass,
            field = wedgeClass.durableField,
            priorValue = failedSha,
            newValue = replacement,
          )
          applied += repair
          evidenceEntries += repairEvidenceMap(repair)
        }
        GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME -> {
          val continuation = workingContinuation ?: continue
          val identity = GoalContinuation(
            issueKey = continuation.issueKey,
            subtaskId = continuation.subtaskId,
            suppressPr = continuation.suppressPr,
            goalBranch = continuation.goalBranch,
          )
          val stored = goalContinuationOutcome(
            artifacts,
            issueKey,
            subtaskId,
            continuation.suppressPr,
          )?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED } ?: continue
          val derived = derivedTerminalOutcomeFor(record, artifacts, identity) { null }
          if (
            nonCompleteStoredOutcomeIsCorroborated(
              stored.copy(workflowId = workflowId),
              derived,
              record,
            )
          ) {
            continue
          }
          patch["goal_continuation_outcome"] = null
          val repair = GoalRunnerAppliedRepair(
            subtaskId = subtaskId,
            workflowId = workflowId,
            wedgeClass = wedgeClass,
            field = wedgeClass.durableField,
            priorValue = stored.blockedReason,
            newValue = null,
          )
          applied += repair
          evidenceEntries += repairEvidenceMap(repair)
        }
      }
    }

    if (applied.isEmpty()) return emptyList()
    patch[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] = priorEvidence + evidenceEntries
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = patch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
    return applied
  }

  private fun healthyDiagnosis(subtaskId: Int, workflowId: String) = GoalRunnerChildWedgeDiagnosis(
    subtaskId = subtaskId,
    workflowId = workflowId,
    passedChecks = listOf(
      PASSED_VALIDATION_DEPTH,
      PASSED_REVIEW_BASE,
      PASSED_REMEDIATION_BASE,
      PASSED_CONTINUATION_OUTCOME,
    ),
  )

  private fun diagnoseValidationDepth(
    artifacts: Map<String, Any?>,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val continuation = continuationArtifact(artifacts)
    if (continuation == null || continuation.validationDepth != null) {
      passed += PASSED_VALIDATION_DEPTH
      return
    }
    wedges += GoalRunnerWedgeFinding(
      wedgeClass = GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
      field = GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH.durableField,
      currentValue = null,
    )
  }

  private fun diagnoseReviewBases(
    artifacts: Map<String, Any?>,
    repoRoot: Path,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val review = GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state
    if (review == null) {
      passed += PASSED_REVIEW_BASE
      passed += PASSED_REMEDIATION_BASE
      return
    }
    if (isUnreachable(repoRoot, review.reviewBaseSha)) {
      wedges += GoalRunnerWedgeFinding(
        wedgeClass = GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE,
        field = GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE.durableField,
        currentValue = review.reviewBaseSha,
      )
    } else {
      passed += PASSED_REVIEW_BASE
    }
    val remediation = review.remediationBaseSha
    when {
      remediation == null -> passed += PASSED_REMEDIATION_BASE
      isUnreachable(repoRoot, remediation) -> wedges += GoalRunnerWedgeFinding(
        wedgeClass = GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE,
        field = GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE.durableField,
        currentValue = remediation,
      )
      else -> passed += PASSED_REMEDIATION_BASE
    }
  }

  private fun diagnoseStaleBlockedOutcome(
    record: skillbill.workflow.model.WorkflowStateSnapshot,
    artifacts: Map<String, Any?>,
    issueKey: String,
    subtaskId: Int,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val identity = goalContinuation(artifacts)
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    if (identity == null) {
      passed += PASSED_CONTINUATION_OUTCOME
      return
    }
    val stored = goalContinuationOutcome(artifacts, issueKey, subtaskId, identity.suppressPr)
      ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
    if (stored == null) {
      passed += PASSED_CONTINUATION_OUTCOME
      return
    }
    val derived = derivedTerminalOutcomeFor(record, artifacts, identity) { null }
    if (
      nonCompleteStoredOutcomeIsCorroborated(
        stored.copy(workflowId = record.workflowId),
        derived,
        record,
      )
    ) {
      passed += PASSED_CONTINUATION_OUTCOME
    } else {
      wedges += GoalRunnerWedgeFinding(
        wedgeClass = GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
        field = GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME.durableField,
        currentValue = stored.blockedReason,
      )
    }
  }

  private fun isUnreachable(repoRoot: Path, sha: String): Boolean {
    val head = gitOperations.headCommitSha(repoRoot)
    if (!head.ok || head.value.isBlank()) return false
    val ancestry = gitOperations.isCommitAncestor(repoRoot, sha, head.value.trim())
    // Only claim a wedge when ancestry is definitively false. An adapter that cannot measure git must
    // not invent unreachable-base findings (same closed set as subtask 2's definitive non-ancestor).
    return ancestry.ok && ancestry.value != "true"
  }

  private fun continuationArtifact(
    artifacts: Map<String, Any?>,
  ): FeatureTaskRuntimeGoalContinuationArtifact? {
    val raw = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] as? Map<*, *> ?: return null
    @Suppress("UNCHECKED_CAST")
    return FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(raw as Map<String, Any?>)
  }

  private fun repairEvidenceMap(repair: GoalRunnerAppliedRepair): Map<String, Any?> = linkedMapOf(
    "wedge_class" to repair.wedgeClass.wireValue,
    "field" to repair.field,
    "prior_value" to repair.priorValue,
    "new_value" to repair.newValue,
    "subtask_id" to repair.subtaskId,
    "workflow_id" to repair.workflowId,
    "repaired_at" to Instant.now().toString(),
  )
}
