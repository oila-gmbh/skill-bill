package skillbill.db.decomposition

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestFileCandidate
import skillbill.ports.workflow.decomposition.runtime.model.LoadedDecompositionManifest
import skillbill.ports.workflow.decomposition.runtime.model.ValidatedDecompositionManifestYaml
import skillbill.workflow.decomposition.DecompositionManifestCodec
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.requireAccepted
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import skillbill.workflow.decomposition.toWireMap
import java.nio.file.NoSuchFileException
import java.nio.file.Path

fun loadDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): DecompositionManifest = loadValidatedDecompositionManifest(path, fileStore, validator, recoverPending).manifest

fun loadValidatedDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): LoadedDecompositionManifest {
  val validated = validateDecompositionManifestYaml(path, fileStore, validator, recoverPending)
  return LoadedDecompositionManifest(
    manifest = validated.manifest,
    yamlText = validated.yamlText,
    repairEvidence = validated.repairEvidence,
  )
}

fun validateDecompositionManifestYaml(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): ValidatedDecompositionManifestYaml {
  val yamlText = if (recoverPending) fileStore.readText(path) else fileStore.readTextWithoutRecovery(path)
  return when (val result = validator.validateYamlTextResult(yamlText, path.toString())) {
    is DecompositionManifestValidationResult.AcceptedUnchanged -> ValidatedDecompositionManifestYaml(
      manifest = result.manifest,
      yamlText = result.yamlText,
      repairEvidence = null,
    )
    is DecompositionManifestValidationResult.AcceptedAfterRepair -> ValidatedDecompositionManifestYaml(
      manifest = result.manifest,
      yamlText = result.yamlText,
      repairEvidence = result.evidence,
    )
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(path.toString())
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}

fun decodeDecompositionManifestMap(
  wireMap: Map<String, Any?>,
  validator: DecompositionManifestValidator,
  sourceLabel: String = "<in-memory>",
): DecompositionManifest {
  validator.validate(wireMap, sourceLabel)
  return DecompositionManifestCodec.decodeMap(wireMap, sourceLabel)
}

fun encodeDecompositionManifestMap(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  sourceLabel: String = "<in-memory>",
): Map<String, Any?> {
  val wireMap = manifest.toWireMap()
  validator.validate(wireMap, sourceLabel)
  return wireMap
}

fun archivedDecompositionManifest(repoRoot: Path, manifestPath: Path): Boolean {
  val relative = runCatching { repoRoot.normalize().relativize(manifestPath.normalize()).toString() }
    .getOrDefault(manifestPath.toString())
    .replace('\\', '/')
  return relative.startsWith(".feature-specs/done/")
}

@OpenBoundaryMap("Persisted workflow artifact JSON decoded for decomposition runtime updates")
fun decodeArtifacts(existingArtifactsJson: String): Map<String, Any?> =
  JsonCodec.parseObjectOrNull(existingArtifactsJson)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    .orEmpty()

fun findMatchingDecompositionManifests(
  repoRoot: Path,
  issueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): List<DecompositionManifestFileCandidate> {
  val normalizedIssueKey = issueKey.trim().uppercase()
  val issueKeyInPath = Regex("(?<![A-Za-z0-9])${Regex.escape(normalizedIssueKey)}(?![A-Za-z0-9])")
  val manifestFiles = if (recoverPending) {
    fileStore.findDecompositionManifestFiles(repoRoot)
  } else {
    fileStore.findDecompositionManifestFilesWithoutRecovery(repoRoot)
  }
  return manifestFiles
    .asSequence()
    .sortedBy { path -> path.toString() }
    .filterNot { path -> archivedDecompositionManifest(repoRoot, path) }
    .filter { path ->
      val relativePath = runCatching { repoRoot.relativize(path).toString() }
        .getOrElse { path.toString() }
      issueKeyInPath.containsMatchIn(relativePath.uppercase())
    }
    .map { path ->
      DecompositionManifestFileCandidate(
        path,
        matchedManifest(path, normalizedIssueKey, fileStore, validator, recoverPending),
      )
    }
    .toList()
}

fun resolveDecompositionManifest(
  repoRoot: Path,
  issueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): DecompositionManifest? {
  val candidates = findMatchingDecompositionManifests(
    repoRoot = repoRoot,
    issueKey = issueKey,
    fileStore = fileStore,
    validator = validator,
    recoverPending = recoverPending,
  )
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = issueKey,
      reason = "multiple active decomposition manifests match the requested issue key: " +
        activeCandidates.joinToString { candidate -> repoRoot.relativize(candidate.path).toString() } + ".",
      failureCode = "duplicate_active",
    )
  }
  return activeCandidates.firstOrNull()?.manifest ?: candidates.firstOrNull()?.manifest
}

fun DecompositionManifest.withParentStatus(): DecompositionManifest {
  val parentStatus = when {
    subtasks.all { it.status in setOf("complete", "skipped") } -> "complete"
    subtasks.any { it.status == "blocked" } -> "blocked"
    subtasks.any { it.status in setOf("in_progress", "complete", "skipped") || it.hasStarted() } -> "in_progress"
    else -> "pending"
  }
  return copy(status = parentStatus)
}

fun Any?.asStringAnyMapOrNull(): Map<String, Any?>? =
  (this as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { (key, value) ->
    val stringKey = key as? String ?: return null
    stringKey to value
  }

private fun matchedManifest(
  path: Path,
  normalizedIssueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean,
): DecompositionManifest {
  val manifest = try {
    loadDecompositionManifest(path, fileStore, validator, recoverPending)
  } catch (error: NoSuchFileException) {
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = path.toString(),
      reason = "manifest disappeared during read; the decomposition bundle is incomplete.",
      failureCode = "incomplete_bundle",
      cause = error,
    )
  }
  if (manifest.issueKey != normalizedIssueKey) {
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = path.toString(),
      reason = "manifest issue_key '${manifest.issueKey}' does not match the requested issue key " +
        "'$normalizedIssueKey'.",
      failureCode = "issue_key_mismatch",
    )
  }
  return manifest
}
