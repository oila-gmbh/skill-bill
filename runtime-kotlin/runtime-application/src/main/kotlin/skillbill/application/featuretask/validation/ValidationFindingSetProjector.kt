package skillbill.application.featuretask.validation

import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget

data class ValidationFindingSetProjection(
  val findings: List<ValidationGateFinding>,
  val droppedCount: Int,
) {
  val hasUnreportedRemainder: Boolean get() = droppedCount > 0

  fun toHandoffMaps(): List<Map<String, String?>> = findings.map { finding ->
    linkedMapOf(
      "module" to finding.module,
      "rule_or_test_id" to finding.ruleOrTestId,
      "message" to finding.message,
      "location" to finding.location,
    )
  }
}

object ValidationFindingSetProjector {
  val FINDINGS_BUDGET: FeatureTaskRuntimeHandoffProjectionBudget =
    FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 65_536, maxCollectionItems = 64)

  fun project(
    findings: List<ValidationGateFinding>,
    budget: FeatureTaskRuntimeHandoffProjectionBudget = FINDINGS_BUDGET,
  ): ValidationFindingSetProjection {
    if (findings.isEmpty()) {
      return ValidationFindingSetProjection(findings = emptyList(), droppedCount = 0)
    }
    val retained = mutableListOf<ValidationGateFinding>()
    var byteCount = 0
    for (finding in findings) {
      val itemBytes = itemUtf8Bytes(finding)
      if (retained.size >= budget.maxCollectionItems) break
      if (byteCount + itemBytes > budget.maxUtf8Bytes && retained.isNotEmpty()) break
      retained += finding
      byteCount += itemBytes
    }
    val dropped = findings.size - retained.size
    return ValidationFindingSetProjection(findings = retained, droppedCount = dropped)
  }

  private fun itemUtf8Bytes(finding: ValidationGateFinding): Int =
    (finding.module + finding.ruleOrTestId + finding.message + (finding.location ?: "")).encodeToByteArray().size
}
