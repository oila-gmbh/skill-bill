package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal data class FeatureTaskRuntimePhaseFileManifest(
  val before: List<String>,
  val after: List<String>,
) {
  val introduced: List<String> = (after.toSet() - before.toSet()).sorted()
}

internal object FeatureTaskRuntimePhaseSafetyPolicy {
  fun lineSeparatedPaths(raw: String): List<String> = raw
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .sorted()
    .toList()

  fun changedPaths(status: String): List<String> = status
    .lineSequence()
    .map(String::trimEnd)
    .filter { it.length >= PORCELAIN_PATH_OFFSET }
    .map { line -> line.substring(PORCELAIN_PATH_OFFSET).substringAfterLast(" -> ").trim('"') }
    .filter(String::isNotBlank)
    .distinct()
    .sorted()
    .toList()

  fun dispositionForTerminalOutput(phaseId: String, output: Map<String, Any?>): FeatureTaskRuntimeFailureDisposition {
    val explicit = (output["failure_disposition"] as? String)
      ?.let(FeatureTaskRuntimeFailureDisposition::fromWireValue)
    if (explicit != null) return explicit
    return if (
      output["status"] == "failed" ||
      phaseId == "validate"
    ) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }
  }

  private const val PORCELAIN_PATH_OFFSET: Int = 3
}
