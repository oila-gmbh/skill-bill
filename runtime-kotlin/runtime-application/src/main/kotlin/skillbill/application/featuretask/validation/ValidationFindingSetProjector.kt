package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget

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
      val overItemCap = retained.size >= budget.maxCollectionItems
      val overByteCap = byteCount + itemBytes > budget.maxUtf8Bytes && retained.isNotEmpty()
      if (overItemCap || overByteCap) {
        break
      }
      retained += finding
      byteCount += itemBytes
    }
    val dropped = findings.size - retained.size
    return ValidationFindingSetProjection(findings = retained, droppedCount = dropped)
  }

  fun page(
    findings: List<ValidationGateFinding>,
    offset: Int,
    budget: FeatureTaskRuntimeHandoffProjectionBudget = FINDINGS_BUDGET,
  ): ValidationFindingSetProjection {
    require(offset >= 0) {
      "ValidationFindingSetProjector.page offset must be >= 0, was $offset."
    }
    if (offset >= findings.size) {
      return ValidationFindingSetProjection(findings = emptyList(), droppedCount = 0, scheduledRemainderCount = 0)
    }
    val retained = mutableListOf<ValidationGateFinding>()
    var byteCount = 0
    var index = offset
    while (index < findings.size) {
      val finding = findings[index]
      val itemBytes = itemUtf8Bytes(finding)
      val overItemCap = retained.size >= budget.maxCollectionItems
      val overByteCap = byteCount + itemBytes > budget.maxUtf8Bytes && retained.isNotEmpty()
      if (overItemCap || overByteCap) {
        break
      }
      retained += finding
      byteCount += itemBytes
      index++
    }
    return ValidationFindingSetProjection(
      findings = retained,
      droppedCount = 0,
      scheduledRemainderCount = findings.size - index,
    )
  }

  private fun itemUtf8Bytes(finding: ValidationGateFinding): Int =
    (finding.module + finding.ruleOrTestId + finding.message + (finding.location ?: "")).encodeToByteArray().size
}
