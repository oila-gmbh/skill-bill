package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry

internal class FeatureTaskRuntimeRunLoopSession(
  internal val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?,
  initialPendingReentry: PendingReentry?,
) {
  internal val phaseContentIdentities = mutableMapOf<String, Map<String, String>>()
  var resolvedBranch: String? = null
  var checkpointOwnershipDecided: Boolean = false
  var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  var paused: FeatureTaskRuntimeRunReport.Paused? = null
  var auditGapRetryResumePending: Boolean = false
  var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null
  var operatorBlockRetryCompleted: Boolean = false
  var pendingReentry: PendingReentry? = initialPendingReentry
  var activeReentry: PendingReentry? = initialPendingReentry
  var recordRejectionSettlementPending: Boolean = false
}
