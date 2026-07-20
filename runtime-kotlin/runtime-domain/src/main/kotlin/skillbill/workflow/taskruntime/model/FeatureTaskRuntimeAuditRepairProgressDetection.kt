package skillbill.workflow.taskruntime.model

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

data class FeatureTaskRuntimeAuditRepairGapIdentities(
  val gapIds: Set<String>,
  val criterionRefs: Set<String>,
)

// Gap-id equality alone is defeatable: dispositioning a gap resolved and re-reporting the same criterion
// under the next generation churns every identifier while the defect set is untouched. The criterion-ref
// set is the churn-proof key, so either an unchanged identity set or an unchanged criterion set counts as
// an equivalent gap set.
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
