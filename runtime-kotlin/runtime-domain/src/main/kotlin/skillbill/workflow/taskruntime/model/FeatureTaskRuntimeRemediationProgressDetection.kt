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
  current: FeatureTaskRuntimeAuditRepairSnapshot,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (!current.hasGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  if (!previous.hasGaps) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  val criteriaReduced = current.criterionRefs.isNotEmpty() &&
    current.criterionRefs.size < previous.criterionRefs.size &&
    previous.criterionRefs.containsAll(current.criterionRefs)
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = !criteriaReduced,
    reason = if (criteriaReduced) {
      null
    } else {
      "Audit made no progress: the unresolved acceptance criteria did not strictly decrease. " +
        "Repository changes alone do not establish criterion closure."
    },
  )
}

fun AuditRepairAssessment.progressSince(
  previous: AuditRepairAssessment,
): FeatureTaskRuntimeAuditRepairProgressDecision = detectAuditRepairNonProgress(
  previous = FeatureTaskRuntimeAuditRepairSnapshot(
    hasGaps = previous.unmetCriterionRefs.isNotEmpty(),
    repositoryFingerprint = previous.checkpoint.repositoryFingerprint,
    criterionRefs = previous.unmetCriterionRefs,
  ),
  current = FeatureTaskRuntimeAuditRepairSnapshot(
    hasGaps = unmetCriterionRefs.isNotEmpty(),
    repositoryFingerprint = checkpoint.repositoryFingerprint,
    criterionRefs = unmetCriterionRefs,
  ),
)
