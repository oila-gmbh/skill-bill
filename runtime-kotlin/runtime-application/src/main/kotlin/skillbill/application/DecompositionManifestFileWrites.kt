package skillbill.application

import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestCodec
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.toWireMap
import java.nio.file.Path

/**
 * Decomposition manifest parse/emission seam. This is where workflow artifact maps and
 * repo-local YAML text from the workflow file-store port are schema-validated before
 * callers persist or return them.
 */
fun loadDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestFileStore,
  validator: DecompositionManifestValidator,
): DecompositionManifest {
  val yamlText = fileStore.readText(path)
  val wireMap = validator.validateYamlText(yamlText, path.toString())
  return DecompositionManifestCodec.decodeMap(wireMap, path.toString())
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

fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore,
  sourceLabel: String = "<in-memory>",
): String {
  val wireMap = encodeDecompositionManifestMap(manifest, validator, sourceLabel)
  val yamlText = fileStore.encodeManifestYaml(wireMap)
  validator.validateYamlText(yamlText, sourceLabel)
  return yamlText
}

fun writeDecompositionManifestText(target: Path, content: String, fileStore: DecompositionManifestFileStore) {
  fileStore.writeTextAtomically(target, content)
}

fun projectDecompositionSpecStatus(target: Path, status: String, fileStore: DecompositionManifestFileStore) {
  if (!fileStore.isRegularFile(target)) {
    return
  }
  val projected = projectStatus(fileStore.readText(target), status)
  fileStore.writeTextAtomically(target, projected)
}

private fun projectStatus(text: String, status: String): String {
  val humanStatus = when (status) {
    "pending" -> "Pending"
    "in_progress" -> "In Progress"
    "blocked" -> "Blocked"
    "complete" -> "Complete"
    "skipped" -> "Skipped"
    else -> status
  }
  val end = text.indexOf("\n---\n", startIndex = 4)
  val frontMatterProjected = if (!text.startsWith("---\n") || end == -1) {
    text
  } else {
    val frontMatter = text.substring(0, end + 1)
    val body = text.substring(end + "\n---\n".length)
    val replaced =
      if (Regex("(?m)^status: .*$").containsMatchIn(frontMatter)) {
        frontMatter.replace(Regex("(?m)^status: .*$"), "status: $humanStatus")
      } else {
        frontMatter + "status: $humanStatus\n"
      }
    replaced + "---\n" + body
  }
  return projectStatusSection(frontMatterProjected, humanStatus)
}

private fun projectStatusSection(text: String, humanStatus: String): String {
  val statusSection = Regex("(?ms)(^## Status\\s*\\n\\n)(.*?)(?=\\n## |\\z)")
  val match = statusSection.find(text) ?: return text
  return text.replaceRange(match.range, match.groupValues[1] + humanStatus + "\n")
}
