package skillbill.application.featuretask.validation

import skillbill.ports.workflow.model.WorkflowScopedPathContent

/** One marker introduction on a scoped path (count is HEAD−base, never below zero). */
data class IntroducedSuppression(
  val path: String,
  val marker: String,
  val introducedCount: Int,
) {
  init {
    require(path.isNotBlank()) { "Introduced suppression path must be non-blank." }
    require(marker.isNotBlank()) { "Introduced suppression marker must be non-blank." }
    require(introducedCount > 0) { "Introduced suppression count must be positive." }
  }
}

/**
 * Runtime-measured suppression delta. [gated] is false when the pack declares no markers
 * (ungated short-circuit). Measurement never reads agent-emitted fields.
 */
data class SuppressionDelta(
  val gated: Boolean,
  val introductions: List<IntroducedSuppression>,
) {
  val totalIntroduced: Int get() = introductions.sumOf { it.introducedCount }
}

object SuppressionDeltaMeasurer {
  fun measure(
    markers: List<String>,
    pathContents: List<WorkflowScopedPathContent>,
  ): SuppressionDelta {
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
