package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.diagnoseUnsettledCompletedUpstreamPhaseId
import skillbill.application.featuretask.featureSizeFromArtifacts
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.goalrunner.model.GoalRunnerWedgeFinding
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path

internal const val PASSED_VALIDATION_DEPTH: String = "validation_depth_present"
internal const val PASSED_QUALITY_GATE_SELECTION: String = "quality_gate_selection_present"
internal const val PASSED_REVIEW_BASE: String = "review_base_reachable"
internal const val PASSED_REMEDIATION_BASE: String = "remediation_base_reachable_or_absent"
internal const val PASSED_CONTINUATION_OUTCOME: String = "continuation_outcome_corroborated_or_absent"
internal const val PASSED_UPSTREAM_OUTPUT: String = "upstream_output_present"
internal const val PASSED_PHASE_OUTPUT_CONTRACT: String = "phase_output_contract_compatible"

internal class GoalRunnerChildRepairWedgeDiagnosis(
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
    diagnoseQualityGateSelection(artifacts, wedges, passed)
    diagnoseReviewBases(artifacts, repoRoot, wedges, passed)
    diagnoseStaleBlockedOutcome(
      GoalRunnerStaleBlockedOutcomeContext(record, artifacts, issueKey, subtaskId),
      wedges,
      passed,
    )
    diagnoseCompletedUpstreamMissingOutput(artifacts, wedges, passed)
    diagnosePhaseOutputContract(artifacts, wedges, passed)

    return GoalRunnerChildWedgeDiagnosis(
      subtaskId = subtaskId,
      workflowId = workflowId,
      wedges = wedges,
      passedChecks = passed,
    )
  }

  fun isUnreachable(repoRoot: Path, sha: String): Boolean {
    val head = gitOperations.headCommitSha(repoRoot)
    if (!head.ok || head.value.isBlank()) return false
    val ancestry = gitOperations.isCommitAncestor(repoRoot, sha, head.value.trim())
    return ancestry.ok && ancestry.value != "true"
  }

  private fun healthyDiagnosis(subtaskId: Int, workflowId: String) = GoalRunnerChildWedgeDiagnosis(
    subtaskId = subtaskId,
    workflowId = workflowId,
    passedChecks = listOf(
      PASSED_VALIDATION_DEPTH,
      PASSED_QUALITY_GATE_SELECTION,
      PASSED_REVIEW_BASE,
      PASSED_REMEDIATION_BASE,
      PASSED_CONTINUATION_OUTCOME,
      PASSED_UPSTREAM_OUTPUT,
      PASSED_PHASE_OUTPUT_CONTRACT,
    ),
  )

  private fun diagnosePhaseOutputContract(
    artifacts: Map<String, Any?>,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val importArtifact = artifacts[FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY] as? Map<*, *>
    val storedVersion = importArtifact?.get("phase_output_contract_version") as? String
    if (storedVersion == null || storedVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      passed += PASSED_PHASE_OUTPUT_CONTRACT
      return
    }
    wedges += GoalRunnerWedgeFinding(
      wedgeClass = GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
      field = GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE.durableField,
      currentValue = storedVersion,
    )
  }

  private fun diagnoseCompletedUpstreamMissingOutput(
    artifacts: Map<String, Any?>,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val phaseRecords = phaseRecordsFrom(artifacts)
    val qualityGateSelection = continuationArtifact(artifacts)?.qualityGateSelection
      ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
    val resumePhaseId = diagnoseUnsettledCompletedUpstreamPhaseId(
      phaseRecords,
      featureSizeFromArtifacts(artifacts),
      qualityGateSelection,
    )
    if (resumePhaseId == null) {
      passed += PASSED_UPSTREAM_OUTPUT
      return
    }
    wedges += GoalRunnerWedgeFinding(
      wedgeClass = GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
      field = resumePhaseId,
      currentValue = "completed_without_output",
    )
  }

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

  private fun diagnoseQualityGateSelection(
    artifacts: Map<String, Any?>,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val continuation = continuationArtifact(artifacts)
    if (continuation == null || continuation.qualityGateSelection != null) {
      passed += PASSED_QUALITY_GATE_SELECTION
      return
    }
    wedges += GoalRunnerWedgeFinding(
      wedgeClass = GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION,
      field = GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION.durableField,
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

  private fun continuationArtifact(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? {
    val raw = JsonSupport.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY])
      ?: return null
    return FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(raw)
  }
}
