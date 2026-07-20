package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult

internal fun auditRepairPlanToWire(plan: FeatureTaskRuntimeAuditRepairPlan): Map<String, Any?> = mapOf(
  "contract_version" to plan.contractVersion,
  "gaps" to plan.gaps.map { gap ->
    mapOf(
      "gap_id" to gap.gapId,
      "acceptance_criterion_ref" to gap.acceptanceCriterionRef,
      "acceptance_criterion_text" to gap.acceptanceCriterionText,
      "failure_evidence" to AuditEvidenceWire.toWire(gap.failureEvidence),
      "diagnosis" to gap.diagnosis,
      "affected_boundary" to gap.affectedBoundary,
      "repair_items" to gap.repairItems.map { item ->
        mapOf(
          "repair_item_id" to item.repairItemId,
          "intended_outcome" to item.intendedOutcome,
          "implementation_actions" to item.implementationActions,
          "affected_paths_or_symbols" to item.affectedPathsOrSymbols,
          "required_verification" to item.requiredVerification,
          "depends_on" to item.dependsOn,
          "status" to "pending",
        )
      },
    )
  },
)

internal fun auditRepairStateToWire(state: FeatureTaskRuntimeAuditRepairState): Map<String, Any?> = mapOf(
  "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
  "accepted_plans" to state.acceptedPlans.map(::auditRepairPlanToWire),
  "latest_plan" to auditRepairPlanToWire(state.acceptedPlans.last()),
  "execution_history" to state.repairItemResults.map(::repairItemResultToWire),
  "prior_gap_dispositions" to state.priorGapDispositions.map { disposition ->
    mapOf(
      "gap_id" to disposition.gapId,
      "status" to disposition.status.name.lowercase(),
      "evidence" to AuditEvidenceWire.toWire(disposition.evidence),
    )
  },
  "unresolved_gap_ledger" to mapOf(
    "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
    "gaps" to state.unresolvedGapLedger.unresolvedGaps.map { gap ->
      mapOf(
        "gap_id" to gap.gapId,
        "acceptance_criterion_ref" to gap.acceptanceCriterionRef,
        "generation" to gap.generation,
      )
    },
  ),
  "repository_fingerprint" to state.repositoryFingerprint,
  "progress" to mapOf(
    "first_pass_convergence" to state.progress.firstPassConvergence,
    "recurring_gap_count" to state.progress.recurringGapCount,
    "new_gap_count" to state.progress.newGapCount,
    "attempted_repair_item_count" to state.progress.attemptedRepairItemCount,
    "resolved_repair_item_count" to state.progress.resolvedRepairItemCount,
    "audit_gap_iteration_count" to state.progress.auditGapIterationCount,
  ),
)

internal fun repairItemResultToWire(result: FeatureTaskRuntimeRepairItemResult): Map<String, Any?> = mapOf(
  "repair_item_id" to result.repairItemId,
  "outcome" to result.outcome.name.lowercase(),
  "changed_paths_or_symbols" to result.changedPathsOrSymbols,
  "executed_verification" to result.executedVerification,
  "result_evidence" to AuditEvidenceWire.toWire(result.resultEvidence),
)
