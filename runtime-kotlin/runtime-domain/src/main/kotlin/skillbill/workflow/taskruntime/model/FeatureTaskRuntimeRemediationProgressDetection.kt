package skillbill.workflow.taskruntime.model

const val UNPROVEN_REPOSITORY_FINGERPRINT: String = "<unproven>"

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

data class FeatureTaskRuntimeAuditRepairSnapshot(
  val hasGaps: Boolean,
  val repositoryFingerprint: String,
  val criterionRefs: Set<String> = emptySet(),
)

fun detectAuditRepairNonProgress(
  previous: FeatureTaskRuntimeAuditRepairSnapshot,
  current: FeatureTaskRuntimeAuditRepairSnapshot,): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (!current.hasGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  if (!previous.hasGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  val criterionSetUnchanged = previous.criterionRefs.isNotEmpty() &&
    current.criterionRefs.isNotEmpty() &&
    previous.criterionRefs == current.criterionRefs
  val repositoryUnchanged = previous.repositoryFingerprint == current.repositoryFingerprint
  val previousUnproven = previous.repositoryFingerprint == UNPROVEN_REPOSITORY_FINGERPRINT
  val criterionRefsAvailable = previous.criterionRefs.isNotEmpty() && current.criterionRefs.isNotEmpty()
  val blocked = previousUnproven || (repositoryUnchanged && (!criterionRefsAvailable || criterionSetUnchanged))
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      if (criterionSetUnchanged) {
        "Audit made no progress: the envelope verdict is still gaps_found, the unresolved criterion " +
          "set is unchanged, and the repository fingerprint is unchanged."
      } else {
        "Audit made no progress: the envelope verdict is still gaps_found and the repository fingerprint " +          "is unchanged."
      }
    } else {
      null
    },
  )
}
