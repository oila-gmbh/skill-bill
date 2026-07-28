package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.ReviewDeltaChange
import skillbill.workflow.taskruntime.model.ReviewDeltaClassification
import skillbill.workflow.taskruntime.model.ReviewDeltaClassificationResult
import skillbill.workflow.taskruntime.model.ReviewGenerationDecision

class ReviewDeltaClassifier(
  private val bookkeepingPathPrefixes: Set<String> = DEFAULT_BOOKKEEPING_PATH_PREFIXES,
) {
  fun classify(changedPaths: Collection<String>): ReviewDeltaClassificationResult {
    val normalized = changedPaths.map(::normalizePath).filter(String::isNotEmpty).distinct().sorted()
    val (bookkeeping, semantic) = normalized.partition(::isBookkeeping)
    val classification = when {
      normalized.isEmpty() -> ReviewDeltaClassification.UNCHANGED
      semantic.isEmpty() -> ReviewDeltaClassification.BOOKKEEPING_ONLY
      else -> ReviewDeltaClassification.SEMANTIC
    }
    return ReviewDeltaClassificationResult(
      classification = classification,
      semanticPaths = semantic,
      bookkeepingPaths = bookkeeping,
    )
  }

  fun classifyChanges(changes: Collection<ReviewDeltaChange>): ReviewDeltaClassificationResult {
    val normalized = changes.map { change ->
      if (change.path.endsWith("/decomposition-manifest.yaml") && change.runtimeOwnedManifestFields.isNotEmpty()) {
        "${normalizePath(change.path)}#runtime-manifest"
      } else {
        change.path
      }
    }
    return classify(normalized)
  }

  fun classifyUnifiedDiff(diff: String): ReviewDeltaClassificationResult =
    classifyChanges(ReviewDeltaUnifiedDiffParser(RUNTIME_OWNED_MANIFEST_FIELDS).parse(diff))

  private fun isBookkeeping(path: String): Boolean =
    bookkeepingPathPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") } ||
      path.endsWith("/decomposition-manifest.yaml#runtime-manifest") ||
      path.endsWith("/decomposition-manifest.yaml.runtime-status") ||
      path.endsWith("/decomposition-manifest.yaml.repository-checkpoint")

  private fun normalizePath(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

  companion object {
    val DEFAULT_BOOKKEEPING_PATH_PREFIXES: Set<String> = setOf(
      ".skill-bill/runtime",
      ".feature-specs/runtime-manifest-status",
      ".feature-specs/repository-checkpoints",
    )
    val RUNTIME_OWNED_MANIFEST_FIELDS: Set<String> = setOf(
      "status",
      "current_subtask_intent",
      "current_subtask_intent.subtask_id",
      "subtasks.status",
      "subtasks.workflow_id",
      "subtasks.blocked_reason",
      "subtasks.last_resumable_step",
      "subtasks.commit_sha",
      "subtasks.finalizing_agent_id",
      "subtasks.participating_agent_ids",
    )
  }
}

private class ReviewDeltaUnifiedDiffParser(
  private val runtimeOwnedManifestFields: Set<String>,
) {
  private val changes = mutableListOf<ReviewDeltaChange>()
  private val changedKeys = mutableSetOf<String>()
  private val oldYamlPath = mutableListOf<Pair<Int, String>>()
  private val newYamlPath = mutableListOf<Pair<Int, String>>()
  private var path: String? = null

  fun parse(diff: String): List<ReviewDeltaChange> {
    diff.lineSequence().forEach(::consume)
    flush()
    return changes
  }

  private fun consume(line: String) {
    if (line.startsWith("diff --git a/")) {
      flush()
      path = line.substringAfter(" b/", "").takeIf(String::isNotBlank)
      return
    }
    parseManifestYamlLine(line)?.let(::record)
  }

  private fun parseManifestYamlLine(line: String): ParsedManifestYamlLine? {
    if (
      path?.endsWith("/decomposition-manifest.yaml") != true ||
      line.startsWith("+++") ||
      line.startsWith("---")
    ) {
      return null
    }
    val marker = line.firstOrNull()?.takeIf { it == ' ' || it == '+' || it == '-' } ?: return null
    val yaml = line.drop(1).removePrefix("- ")
    val key = yaml.trim().substringBefore(":").trim()
    if (key.isBlank() || ":" !in yaml) return null
    return ParsedManifestYamlLine(
      marker = marker,
      indent = yaml.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0),
      key = key,
      opensNestedValue = yaml.substringAfter(":", "").isBlank(),
    )
  }

  private fun record(line: ParsedManifestYamlLine) {
    if (line.marker != '+') update(oldYamlPath, line, line.marker == '-')
    if (line.marker != '-') update(newYamlPath, line, line.marker == '+')
  }

  private fun update(stack: MutableList<Pair<Int, String>>, line: ParsedManifestYamlLine, changed: Boolean) {
    while (stack.lastOrNull()?.first?.let { it >= line.indent } == true) stack.removeLast()
    if (changed) {
      val parsedPath = (stack.map { it.second } + line.key).joinToString(".")
      changedKeys += if (
        stack.isEmpty() &&
        line.indent >= REVIEW_MANIFEST_SUBTASK_FIELD_INDENT &&
        "subtasks.${line.key}" in runtimeOwnedManifestFields
      ) {
        "subtasks.${line.key}"
      } else {
        parsedPath
      }
    }
    if (line.opensNestedValue) stack += line.indent to line.key
  }

  private fun flush() {
    val currentPath = path ?: return
    val governedFields = changedKeys.takeIf {
      currentPath.endsWith("/decomposition-manifest.yaml") &&
        it.isNotEmpty() &&
        it.all(runtimeOwnedManifestFields::contains)
    }?.toSet().orEmpty()
    changes += ReviewDeltaChange(currentPath, governedFields)
    changedKeys.clear()
    oldYamlPath.clear()
    newYamlPath.clear()
  }
}

private data class ParsedManifestYamlLine(
  val marker: Char,
  val indent: Int,
  val key: String,
  val opensNestedValue: Boolean,
)

private const val REVIEW_MANIFEST_SUBTASK_FIELD_INDENT = 2

object ReviewGenerationPolicy {
  fun decide(classification: ReviewDeltaClassification): ReviewGenerationDecision =
    if (classification == ReviewDeltaClassification.SEMANTIC) {
      ReviewGenerationDecision.CREATE_SUCCESSOR
    } else {
      ReviewGenerationDecision.RETAIN
    }
}
