package skillbill.application.decomposition

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal fun decodeArtifacts(existingArtifactsJson: String): Map<String, Any?> =
  JsonSupport.parseObjectOrNull(existingArtifactsJson)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    .orEmpty()

internal fun loadManifestOrNull(
  path: Path,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore,
): DecompositionManifest? = try {
  loadDecompositionManifest(path, fileStore, validator)
} catch (_: NoSuchFileException) {
  null
}

internal fun manifestPathFromArtifacts(
  repoRoot: Path,
  artifactsPatch: Map<String, Any?>?,
  existingArtifacts: Map<String, Any?>,
): Path? {
  val merged = LinkedHashMap(existingArtifacts)
  artifactsPatch?.let(merged::putAll)
  val specPath = (merged["assessment"] as? Map<*, *>)?.get("spec_path")?.toString()?.takeIf(String::isNotBlank)
    ?: (merged["plan"] as? Map<*, *>)?.get("parent_spec_path")?.toString()?.takeIf(String::isNotBlank)
  (merged["plan"] as? Map<*, *>)?.asStringAnyMapOrNull()?.takeIf { it["mode"] == "decompose" }?.let { plan ->
    return decompositionManifestPath(repoRoot, Path.of(parentSpecPath(plan)), planSubtaskSpecPaths(plan))
  }
  return specPath?.let { resolvedParentSpecPath(repoRoot, Path.of(it)).parent.resolve(DECOMPOSITION_MANIFEST_FILENAME) }
}

internal fun DecompositionManifest.assertExecutionModelCanReplace(
  existing: DecompositionManifest?,
  manifestPath: Path,
): DecompositionManifest {
  if (existing != null && executionModel != existing.executionModel && existing.subtasks.any { it.hasStarted() }) {
    invalidManifest(
      manifestPath.toString(),
      "execution_model cannot change after decomposition execution has begun; manually migrate or reset the " +
        "decomposition manifest before changing execution_model.",
    )
  }
  return this
}

private fun planSubtaskSpecPaths(plan: Map<String, Any?>): List<String> =
  (plan["subtasks"] as? List<*>).orEmpty().mapNotNull { raw ->
    raw.asStringAnyMapOrNull()?.get("spec_path")?.toString()?.takeIf(String::isNotBlank)
  }

internal fun DecompositionManifest.withPreservedRuntimeState(existing: DecompositionManifest?): DecompositionManifest {
  if (existing == null) {
    return this
  }
  val existingById = existing.subtasks.associateBy(DecompositionSubtask::id)
  return copy(
    status = existing.status,
    subtasks = subtasks.map { planned ->
      val previous = existingById[planned.id] ?: return@map planned
      planned.copy(
        status = previous.status,
        branch = previous.branch,
        commitSha = previous.commitSha,
        workflowId = previous.workflowId,
        blockedReason = previous.blockedReason,
        lastResumableStep = previous.lastResumableStep,
      )
    },
    currentSubtaskIntent = existing.currentSubtaskIntent,
  )
}

internal fun DecompositionManifest.withRuntimeUpdate(
  repoRoot: Path,
  update: DecompositionManifestRuntimeUpdate,
): DecompositionManifest {
  val subtaskId = currentSubtaskIdForUpdate(repoRoot, update) ?: return this
  val status = statusFromUpdate(update)
  val updatedSubtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) subtask.withRuntimeFields(this, update, status) else subtask
  }
  return copy(
    subtasks = updatedSubtasks,
    currentSubtaskIntent = intentFor(subtaskId, status),
  ).withParentStatus()
}
