package skillbill.scaffold.policy

internal fun appendPointers(
  lines: MutableList<String>,
  baselineContentPath: String,
  declaredCodeReviewAreas: List<String>,
  declaredAreaFiles: Map<String, String>,
  declaredQualityCheckFile: String?,
) {
  lines += ""
  lines += "pointers:"
  appendPointerConsumer(lines, baselineContentPath, baselinePointers)
  declaredCodeReviewAreas.forEach { area ->
    declaredAreaFiles[area]?.let { appendPointerConsumer(lines, it, specialistPointers) }
  }
  declaredQualityCheckFile?.let { appendPointerConsumer(lines, it, qualityCheckPointers) }
}

private fun appendPointerConsumer(
  lines: MutableList<String>,
  contentPath: String,
  pointers: List<Pair<String, String>>,
) {
  lines += "  ${contentPath.removeSuffix("/content.md")}:"
  pointers.forEach { (name, target) ->
    lines += "    - name: ${yamlScalar(name)}"
    lines += "      target: ${yamlScalar(target)}"
  }
}

internal fun yamlScalar(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private val baselinePointers = listOf(
  "review-orchestrator.md" to "orchestration/review-orchestrator/PLAYBOOK.md",
  "review-delegation.md" to "orchestration/review-delegation/PLAYBOOK.md",
  "review-scope.md" to "orchestration/review-scope/PLAYBOOK.md",
  "shell-ceremony.md" to "orchestration/shell-content-contract/shell-ceremony.md",
  "specialist-contract.md" to "orchestration/review-orchestrator/specialist-contract.md",
  "stack-routing.md" to "orchestration/stack-routing/PLAYBOOK.md",
  "telemetry-contract.md" to "orchestration/telemetry-contract/PLAYBOOK.md",
)

private val specialistPointers = listOf(
  "review-orchestrator.md" to "orchestration/review-orchestrator/PLAYBOOK.md",
  "shell-ceremony.md" to "orchestration/shell-content-contract/shell-ceremony.md",
  "telemetry-contract.md" to "orchestration/telemetry-contract/PLAYBOOK.md",
)

private val qualityCheckPointers = listOf(
  "shell-ceremony.md" to "orchestration/shell-content-contract/shell-ceremony.md",
  "stack-routing.md" to "orchestration/stack-routing/PLAYBOOK.md",
  "telemetry-contract.md" to "orchestration/telemetry-contract/PLAYBOOK.md",
)
