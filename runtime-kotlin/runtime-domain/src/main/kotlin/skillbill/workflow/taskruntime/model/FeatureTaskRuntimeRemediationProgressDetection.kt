package skillbill.workflow.taskruntime.model

const val UNPROVEN_REPOSITORY_FINGERPRINT: String = "<unproven>"

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

fun detectAuditRepairNonProgress(
  previousHadGaps: Boolean,
  currentHasGaps: Boolean,
  previousRepositoryFingerprint: String,
  currentRepositoryFingerprint: String,
  previousCriterionRefs: Set<String> = emptySet(),
  currentCriterionRefs: Set<String> = emptySet(),
): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (!currentHasGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  if (!previousHadGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  if (previousCriterionRefs.isNotEmpty() && currentCriterionRefs.isNotEmpty()) {
    val unchanged = previousCriterionRefs == currentCriterionRefs
    return FeatureTaskRuntimeAuditRepairProgressDecision(
      blocked = unchanged,
      reason = if (unchanged) {
        "Audit made no progress: the envelope verdict is still gaps_found and the unresolved criterion " +
          "set is unchanged."
      } else {
        null
      },
    )
  }
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val previousUnproven = previousRepositoryFingerprint == UNPROVEN_REPOSITORY_FINGERPRINT
  val blocked = repositoryUnchanged || previousUnproven
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit made no progress: the envelope verdict is still gaps_found, the unresolved criterion " +
        "set is unchanged, and the repository fingerprint is unchanged."
    } else {
      null
    },
  )
}
