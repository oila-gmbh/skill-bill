package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

private const val AUDIT_RECORD_STATUS_COMPLETED = "completed"

/**
 * The one derivation of audit-loop progress, shared by finished telemetry and the operator status
 * projection so a single workflow cannot report convergence to one and not the other.
 *
 * First-pass convergence means an audit ran to completion and settled every blocking criterion on
 * that first pass. All three conditions are load-bearing: a zero `audit_gap` iteration count alone
 * also describes a run that never audited, and a completed record alone also describes an audit that
 * found gaps whose loop edge is not on the ledger yet, or never reached it because the process died
 * between the two writes.
 */
object FeatureTaskRuntimeAuditConvergence {
  fun progressFrom(
    auditRecord: FeatureTaskRuntimePhaseRecord?,
    auditGapIterationCount: Int,
  ): FeatureTaskRuntimeAuditProgress = FeatureTaskRuntimeAuditProgress(
    firstPassConvergence = auditGapIterationCount == 0 && auditSettledSatisfied(auditRecord),
    auditGapIterationCount = auditGapIterationCount,
  )

  /**
   * The audit->implement re-entry count: the highest per-edge iteration on the `audit_gap` LOOP_EDGE
   * ledger. LOOP_EDGE is the authority because a resume watermark also carries the loop id and would
   * otherwise count a re-entry that no edge recorded.
   */
  fun auditGapIterationCount(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>): Int = ledger
    .filter {
      it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
        it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID
    }
    .mapNotNull { it.edgeIteration }
    .maxOrNull()
    ?: 0

  private fun auditSettledSatisfied(record: FeatureTaskRuntimePhaseRecord?): Boolean {
    val artifact = record
      ?.takeIf { it.status == AUDIT_RECORD_STATUS_COMPLETED }
      ?.outputArtifact
      ?: return false
    val envelope = JsonSupport.parseObjectOrNull(artifact)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return false
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      envelope,
    )
    return verdict == FeatureTaskRuntimeVerdict.SATISFIED
  }
}
