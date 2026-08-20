package skillbill.workflow.taskruntime.model

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

data class FeatureTaskRuntimeAuditRepairGapIdentities(
  val gapIds: Set<String>,
  val criterionRefs: Set<String>,
)

fun detectAuditRepairNonProgress(
  previous: FeatureTaskRuntimeAuditRepairGapIdentities,
  current: FeatureTaskRuntimeAuditRepairGapIdentities,
  previousRepositoryFingerprint: String,
  currentRepositoryFingerprint: String,
  newlyResolvedRepairItemCount: Int,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  val equivalentGapIds = previous.gapIds == current.gapIds
  val equivalentCriteria = previous.criterionRefs.isNotEmpty() && previous.criterionRefs == current.criterionRefs
  val equivalentGaps = equivalentGapIds || equivalentCriteria
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val blocked = equivalentGaps && (repositoryUnchanged || newlyResolvedRepairItemCount == 0)
  val identityDetail = if (equivalentGapIds) {
    "unresolved gap identities are unchanged"
  } else {
    "the unresolved acceptance criteria are unchanged (${current.criterionRefs.sorted()}) under renamed gap ids"
  }
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit repair made no progress: $identityDetail and " +
        if (repositoryUnchanged) "the repository fingerprint is unchanged." else "no repair item was newly resolved."
    } else {
      null
    },
  )
}
