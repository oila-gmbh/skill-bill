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

fun detectAuditRepairNonProgress(
  previousCriterionRefs: Set<String>,
  currentCriterionRefs: Set<String>,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (currentCriterionRefs.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(
      blocked = true,
      reason = "Audit made no progress: the current unresolved criterion set is unavailable.",
    )
  }
  val previousRefs = previousCriterionRefs - FeatureTaskRuntimeAuditGapProgress.HAD_GAPS_MARKER
  if (previousRefs.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(
      blocked = true,
      reason = "Audit made no progress: the previous unresolved criterion set is unavailable.",
    )
  }
  val blocked = (previousRefs - currentCriterionRefs).isEmpty()
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit made no progress: the unresolved criterion set did not shrink."
    } else {
      null
    },
  )
}
