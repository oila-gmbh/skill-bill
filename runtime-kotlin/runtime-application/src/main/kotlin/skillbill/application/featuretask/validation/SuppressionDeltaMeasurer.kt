package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.IntroducedSuppression
import skillbill.application.featuretask.validation.model.SuppressionDelta
import skillbill.ports.workflow.model.WorkflowScopedPathContent

object SuppressionDeltaMeasurer {
  fun measure(markers: List<String>, pathContents: List<WorkflowScopedPathContent>): SuppressionDelta {
    val declared = markers.map(String::trim).filter(String::isNotEmpty)
    if (declared.isEmpty()) {
      return SuppressionDelta(gated = false, introductions = emptyList())
    }
    val introductions = buildList {
      for (pair in pathContents) {
        for (marker in declared) {
          val headCount = countOccurrences(pair.headContent.orEmpty(), marker)
          val baseCount = countOccurrences(pair.baseContent.orEmpty(), marker)
          val introduced = (headCount - baseCount).coerceAtLeast(0)
          if (introduced > 0) {
            add(
              IntroducedSuppression(
                path = pair.headPath,
                marker = marker,
                introducedCount = introduced,
              ),
            )
          }
        }
      }
    }
    return SuppressionDelta(gated = true, introductions = introductions)
  }

  private fun countOccurrences(content: String, marker: String): Int {
    if (content.isEmpty() || marker.isEmpty()) return 0
    var count = 0
    var index = content.indexOf(marker)
    while (index >= 0) {
      count++
      index = content.indexOf(marker, index + marker.length)
    }
    return count
  }
}
