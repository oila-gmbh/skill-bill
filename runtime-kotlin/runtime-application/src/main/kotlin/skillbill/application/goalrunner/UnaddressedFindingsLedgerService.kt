package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_CATEGORIES
import skillbill.goalrunner.model.UNADDRESSED_FINDING_SEVERITIES
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder

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
      state.repairLedger.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)?.let { workflowId to it }
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
