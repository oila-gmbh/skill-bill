package skillbill.workflow.taskruntime.model

data class FeatureTaskAuditRepairStageRequest(
  val identity: AuditRepairIdentity,
  val expectedRevision: Int,
  val revision: AuditRepairRevision,
  val criterionRefs: List<String> = emptyList(),
  val legacyEvidence: String? = null,
)

fun FeatureTaskAuditRepairStageRequest.initialCycle(
  authoritativeCriterionRefs: List<String> = criterionRefs,
): AuditRepairCycle {
  require(revision.revision == 0) { "Diagnosis must use revision 0." }
  return AuditRepairCycle(
    identity = identity,
    criterionRefs = authoritativeCriterionRefs,
    revisions = listOf(revision),
    legacyEvidence = legacyEvidence,
  )
}
