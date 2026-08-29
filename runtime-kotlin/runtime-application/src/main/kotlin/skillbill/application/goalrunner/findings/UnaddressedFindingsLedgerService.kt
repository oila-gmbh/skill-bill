package skillbill.application.goalrunner.findings

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_CATEGORIES
import skillbill.goalrunner.model.UNADDRESSED_FINDING_SEVERITIES
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

@Inject
class UnaddressedFindingsLedgerService(private val database: DatabaseSessionFactory) {
  fun ledger(issueKey: String, dbOverride: String? = null): UnaddressedFindingsLedger =
    database.read(dbOverride) { unitOfWork ->
      if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
        throw UnaddressedFindingsLedgerAbsentError("No goal exists for issue key '$issueKey'.")
      }
      val findings = unitOfWork.unaddressedFindings.fetchLedger(issueKey)
      findings.forEach { finding ->
        if (!isValidFinding(issueKey, finding)) {
          throw InvalidUnaddressedFindingsLedgerSchemaError(
            "Malformed unaddressed-findings ledger row for issue '$issueKey'.",
          )
        }
      }
      UnaddressedFindingsLedger(issueKey, findings)
    }

  fun verificationDispositions(
    issueKey: String,
    dbOverride: String? = null,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> = database.read(dbOverride) { unitOfWork ->
    if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
      throw UnaddressedFindingsLedgerAbsentError("No goal exists for issue key '$issueKey'.")
    }
    unitOfWork.unaddressedFindings.workflowIdsForIssue(issueKey).flatMap { workflowId ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@flatMap emptyList()
      val artifacts = decodeArtifacts(record.artifactsJson)
      val artifactKey = when {
        artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY] != null ->
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
        artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY] != null ->
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
        else -> return@flatMap emptyList()
      }
      val raw = artifacts[artifactKey] ?: return@flatMap emptyList()
      runCatching {
        FeatureTaskRuntimeFindingVerificationDisposition.parseList(
          raw,
          artifactKey,
        )
      }.getOrDefault(emptyList())
    }
  }

  fun repairLedgersByWorkflow(
    issueKey: String,
    dbOverride: String? = null,
  ): Map<String, FeatureTaskRuntimeRepairLedger> = database.read(dbOverride) { unitOfWork ->
    if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
      throw UnaddressedFindingsLedgerAbsentError("No goal exists for issue key '$issueKey'.")
    }
    unitOfWork.unaddressedFindings.workflowIdsForIssue(issueKey).mapNotNull { workflowId ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@mapNotNull null
      val state = runCatching {
        GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(decodeArtifacts(record.artifactsJson))
      }.getOrNull() ?: return@mapNotNull null
      runCatching { state.repairLedger }.getOrNull()
        ?.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
        ?.let { workflowId to it }
    }.toMap()
  }

  private fun isValidFinding(issueKey: String, finding: UnaddressedFinding): Boolean = finding.issueKey == issueKey &&
    finding.workflowId.isNotBlank() &&
    finding.subtaskId > 0 &&
    finding.reviewPassNumber > 0 &&
    finding.findingOrdinal > 0 &&
    finding.location.isNotBlank() &&
    finding.summary.isNotBlank() &&
    finding.severity in UNADDRESSED_FINDING_SEVERITIES &&
    finding.issueCategory in UNADDRESSED_FINDING_CATEGORIES
}
