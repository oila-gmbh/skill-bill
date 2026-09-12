package skillbill.workflow.taskruntime.model

data class FeatureTaskRuntimeAuditRepairStatus(
  val firstPassConvergence: Boolean,
  val auditGapIterationCount: Int,
  val stage: String? = null,
  val unresolvedCriterionRefs: List<String> = emptyList(),
  val repairRoundCount: Int = 0,
  val lastCheckpointId: String? = null,
  val executionId: String? = null,
  val sessionId: String? = null,
  val operatorReason: String? = null,
)
