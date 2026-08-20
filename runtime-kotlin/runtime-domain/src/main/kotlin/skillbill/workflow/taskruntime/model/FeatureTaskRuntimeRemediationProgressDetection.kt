package skillbill.workflow.taskruntime.model

const val UNPROVEN_REPOSITORY_FINGERPRINT: String = "<unproven>"

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

data class FeatureTaskRuntimeAuditRepairGapIdentities(
  val gapIds: Set<String>,
  val criterionRefs: Set<String>,
)

fun detectAuditRepairNonProgress(
  previousCriterionRefs: Set<String>,
  currentCriterionRefs: Set<String>,
  previousRepositoryFingerprint: String,
  currentRepositoryFingerprint: String,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (currentCriterionRefs.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  if (previousCriterionRefs.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  val clearedPriorRefs = previousCriterionRefs - currentCriterionRefs
  val retainedPriorRefs = previousCriterionRefs intersect currentCriterionRefs
  val madeProgress = clearedPriorRefs.isNotEmpty() &&
    (currentCriterionRefs.size < previousCriterionRefs.size || retainedPriorRefs.isNotEmpty())
  if (madeProgress) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val previousUnproven = previousRepositoryFingerprint == UNPROVEN_REPOSITORY_FINGERPRINT
  val blocked = repositoryUnchanged || previousUnproven
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit made no progress: the unmet acceptance criteria are unchanged " +
        "(${currentCriterionRefs.sorted()}) and the repository fingerprint is unchanged."
    } else {
      null
    },
  )
}
