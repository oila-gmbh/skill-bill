package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

internal data class FeatureTaskRuntimePhaseFileManifest(
  val before: List<String>,
  val after: List<String>,
) {
  val introduced: List<String> = (after.toSet() - before.toSet()).sorted()
}

internal object FeatureTaskRuntimePhaseSafetyPolicy {
  private val issueDirectory = Regex("^([A-Za-z]+-[0-9]+(?:\\.[0-9]+)*)(?:[-/]|$)")

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

  fun unauthorizedIssueSpecs(
    manifest: FeatureTaskRuntimePhaseFileManifest,
    allowedIssueKeys: Set<String>,
  ): List<String> {
    val normalizedAllowed = allowedIssueKeys.map(String::uppercase).toSet()
    return manifest.introduced.filter { path ->
      if (!path.startsWith(FEATURE_SPEC_ROOT)) return@filter false
      val relative = path.removePrefix(FEATURE_SPEC_ROOT)
      val issueKey = issueDirectory.find(relative)?.groupValues?.get(1)?.uppercase() ?: return@filter false
      issueKey !in normalizedAllowed
    }
  }

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
  private const val FEATURE_SPEC_ROOT: String = ".feature-specs/"
}
