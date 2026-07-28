package skillbill.workflow.taskruntime

enum class ReviewDeltaClassification {
  UNCHANGED,
  BOOKKEEPING_ONLY,
  SEMANTIC,
}

data class ReviewDeltaClassificationResult(
  val contractVersion: String = CONTRACT_VERSION,
  val classification: ReviewDeltaClassification,
  val semanticPaths: List<String>,
  val bookkeepingPaths: List<String>,
) {
  companion object {
    const val CONTRACT_VERSION: String = "0.1"
  }
}

data class ReviewDeltaChange(
  val path: String,
  val runtimeOwnedManifestFields: Set<String> = emptySet(),
) {
  init {
    require(runtimeOwnedManifestFields.all { it in ReviewDeltaClassifier.RUNTIME_OWNED_MANIFEST_FIELDS }) {
      "Review delta contains an ungoverned runtime-owned manifest field."
    }
  }
}

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

  fun classifyUnifiedDiff(diff: String): ReviewDeltaClassificationResult {
    val changes = mutableListOf<ReviewDeltaChange>()
    var path: String? = null
    var changedKeys = mutableSetOf<String>()
    val oldYamlPath = mutableListOf<Pair<Int, String>>()
    val newYamlPath = mutableListOf<Pair<Int, String>>()
    fun flush() {
      val currentPath = path ?: return
      val governedFields = if (
        currentPath.endsWith("/decomposition-manifest.yaml") &&
        changedKeys.isNotEmpty() &&
        changedKeys.all { it in RUNTIME_OWNED_MANIFEST_FIELDS }
      ) {
        changedKeys
      } else {
        emptySet()
      }
      changes += ReviewDeltaChange(currentPath, governedFields)
      changedKeys = mutableSetOf()
      oldYamlPath.clear()
      newYamlPath.clear()
    }
    diff.lineSequence().forEach { line ->
      if (line.startsWith("diff --git a/")) {
        flush()
        path = line.substringAfter(" b/", "").takeIf(String::isNotBlank)
      } else if (
        path?.endsWith("/decomposition-manifest.yaml") == true &&
        !line.startsWith("+++") &&
        !line.startsWith("---")
      ) {
        val marker = line.firstOrNull()
        if (marker == ' ' || marker == '+' || marker == '-') {
          val yaml = line.drop(1).removePrefix("- ")
          val indent = yaml.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
          val key = yaml.trim().substringBefore(":").trim()
          if (key.isNotBlank() && ":" in yaml) {
            fun update(stack: MutableList<Pair<Int, String>>, changed: Boolean) {
              while (stack.lastOrNull()?.first?.let { it >= indent } == true) stack.removeLast()
              if (changed) changedKeys += (stack.map { it.second } + key).joinToString(".")
              if (yaml.substringAfter(":", "").isBlank()) stack += indent to key
            }
            if (marker != '+') update(oldYamlPath, marker == '-')
            if (marker != '-') update(newYamlPath, marker == '+')
          }
        }
      }
    }
    flush()
    return classifyChanges(changes)
  }

  private fun isBookkeeping(path: String): Boolean =
    bookkeepingPathPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") } ||
      path.endsWith("/decomposition-manifest.yaml#runtime-manifest") ||
      path.endsWith("/decomposition-manifest.yaml.runtime-status") ||
      path.endsWith("/decomposition-manifest.yaml.repository-checkpoint")

  private fun normalizePath(path: String): String =
    path.trim().replace('\\', '/').removePrefix("./")

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

enum class ReviewGenerationDecision {
  RETAIN,
  CREATE_SUCCESSOR,
}

object ReviewGenerationPolicy {
  fun decide(classification: ReviewDeltaClassification): ReviewGenerationDecision =
    if (classification == ReviewDeltaClassification.SEMANTIC) {
      ReviewGenerationDecision.CREATE_SUCCESSOR
    } else {
      ReviewGenerationDecision.RETAIN
    }
}
