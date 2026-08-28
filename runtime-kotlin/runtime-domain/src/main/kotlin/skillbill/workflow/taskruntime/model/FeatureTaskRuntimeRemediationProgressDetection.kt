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
): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (!currentHasGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  if (!previousHadGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val previousUnproven = previousRepositoryFingerprint == UNPROVEN_REPOSITORY_FINGERPRINT
  val blocked = repositoryUnchanged || previousUnproven
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit made no progress: the envelope verdict is still gaps_found and the " +
        "repository fingerprint is unchanged."
    } else {
      null
    },
  )
}
