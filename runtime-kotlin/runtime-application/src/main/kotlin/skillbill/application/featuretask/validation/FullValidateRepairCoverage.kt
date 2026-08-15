package skillbill.application.featuretask.validation

import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.taskruntime.model.FullValidateRepairPlanItem
import skillbill.workflow.taskruntime.model.FullValidateSubstantiationReceipt

object FullValidateRepairCoverage {
  const val RELAUNCH_CAP: Int = 3

  fun oneToOnePlan(findings: List<ValidationGateFinding>): List<FullValidateRepairPlanItem> =
    findings.map { finding -> FullValidateRepairPlanItem(identities = listOf(finding.identity())) }

  fun replacePlanIfUnionUnchanged(
    current: List<FullValidateRepairPlanItem>,
    launchedIdentities: Set<String>,
    supplied: List<FullValidateRepairPlanItem>?,
  ): List<FullValidateRepairPlanItem> {
    if (supplied == null) return current
    val union = supplied.flatMap { it.identities }.toSet()
    if (union != launchedIdentities) return current
    val kept = current.filter { item -> item.identities.none { it in launchedIdentities } }
    return kept + supplied
  }

  fun evaluate(
    requiredIdentities: Collection<String>,
    plan: List<FullValidateRepairPlanItem>,
    receipts: List<FullValidateSubstantiationReceipt>,
  ): Evaluation {
    if (requiredIdentities.isEmpty()) {
      return Evaluation(accepted = true, reason = "")
    }
    val required = requiredIdentities.toSet()
    val planned = plan.flatMap { it.identities }.toSet()
    if (!required.all { it in planned }) {
      return Evaluation(
        accepted = false,
        reason = "FULL validate repair plan must list every launched discovery identity.",
      )
    }
    val missing = required.filter { identity -> receipts.none { it.covers(identity) } }
    if (missing.isNotEmpty()) {
      return Evaluation(
        accepted = false,
        reason = "FULL validate repair coverage rejected: a substantiation receipt naming the " +
          "identity, a root cause, and changed paths or symbols is required for every discovery " +
          "identity before confirmation.",
      )
    }
    return Evaluation(accepted = true, reason = "")
  }

  data class Evaluation(
    val accepted: Boolean,
    val reason: String,
  )
}
