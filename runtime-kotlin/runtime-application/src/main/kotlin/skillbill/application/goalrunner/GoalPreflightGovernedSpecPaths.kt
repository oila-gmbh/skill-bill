package skillbill.application.goalrunner

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Path

internal fun missingGovernedSpecPaths(
  root: Path,
  manifest: DecompositionManifest,
  fileStore: DecompositionManifestFileStore,
): List<String> = buildList {
  val parentPath = relativeGovernedSpecPath(root, manifest.parentSpecPath)
  if (!fileStore.isRegularFileWithoutRecovery(root.resolve(parentPath))) {
    add(parentPath)
  }
  manifest.subtasks
    .filterNot { it.status == "complete" || it.status == "skipped" }
    .forEach { subtask ->
      val subtaskPath = relativeGovernedSpecPath(root, subtask.specPath)
      if (!fileStore.isRegularFileWithoutRecovery(root.resolve(subtaskPath))) {
        add(subtaskPath)
      }
    }
}

internal fun governedSpecPreflightViolation(
  manifest: DecompositionManifest,
  root: Path,
  fileStore: DecompositionManifestFileStore,
): InvalidDecompositionManifestSchemaError? {
  val missing = missingGovernedSpecPaths(root, manifest, fileStore)
  if (missing.isEmpty() || manifest.specSource == SpecSource.LINEAR) {
    return null
  }
  return InvalidDecompositionManifestSchemaError(
    sourceLabel = manifest.issueKey,
    reason = "Governed spec scratch is missing (${missing.joinToString(", ")}). " +
      "Linear-mode goals delete scratch after subtask completion; a hard reset reopens work without " +
      "restoring those files. Re-run bill-feature-spec or restore the spec directory before resuming.",
    failureCode = "missing_governed_spec",
  )
}

internal fun relativeGovernedSpecPath(root: Path, rawPath: String): String {
  val path = Path.of(rawPath)
  val resolved = (if (path.isAbsolute) path else root.resolve(path)).toAbsolutePath().normalize()
  return if (resolved.startsWith(root)) {
    root.relativize(resolved).joinToString("/")
  } else {
    rawPath
  }
}
