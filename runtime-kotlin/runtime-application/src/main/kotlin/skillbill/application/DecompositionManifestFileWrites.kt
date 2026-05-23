package skillbill.application

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.DecompositionManifestSchemaValidator
import skillbill.workflow.DecompositionManifestCodec
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.toWireMap
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

private val decompositionManifestYamlMapper: YAMLMapper by lazy { YAMLMapper() }

/**
 * Temporary SKILL-52 blocker: decomposition manifest projection still writes repo-local
 * files from the application layer because workflow projection is driven by static helpers
 * used by CLI, MCP, and tests. A later SKILL-52 adapter/composition subtask should introduce
 * an injected manifest storage port and move these `Files` calls behind the runtime-core
 * infrastructure boundary.
 */
fun loadDecompositionManifest(path: Path): DecompositionManifest {
  val yamlText = Files.readString(path)
  val wireMap = DecompositionManifestSchemaValidator.validateYamlText(yamlText, path.toString())
  return DecompositionManifestCodec.decodeMap(wireMap, path.toString())
}

fun decodeDecompositionManifestMap(
  wireMap: Map<String, Any?>,
  sourceLabel: String = "<in-memory>",
): DecompositionManifest {
  DecompositionManifestSchemaValidator.validate(wireMap, sourceLabel)
  return DecompositionManifestCodec.decodeMap(wireMap, sourceLabel)
}

fun encodeDecompositionManifestMap(
  manifest: DecompositionManifest,
  sourceLabel: String = "<in-memory>",
): Map<String, Any?> {
  val wireMap = manifest.toWireMap()
  DecompositionManifestSchemaValidator.validate(wireMap, sourceLabel)
  return wireMap
}

fun encodeDecompositionManifestYaml(manifest: DecompositionManifest, sourceLabel: String = "<in-memory>"): String {
  val wireMap = encodeDecompositionManifestMap(manifest, sourceLabel)
  val yamlText = decompositionManifestYamlMapper.writeValueAsString(wireMap)
  DecompositionManifestSchemaValidator.validateYamlText(yamlText, sourceLabel)
  return yamlText
}

fun writeDecompositionManifestText(target: Path, content: String) {
  writeTextAtomically(target, content)
}

fun projectDecompositionSpecStatus(target: Path, status: String) {
  if (!Files.isRegularFile(target)) {
    return
  }
  val projected = projectStatus(Files.readString(target), status)
  writeTextAtomically(target, projected)
}

private fun writeTextAtomically(target: Path, content: String) {
  Files.createDirectories(target.parent)
  val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
  Files.writeString(temp, content)
  try {
    Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(temp, target, REPLACE_EXISTING)
  }
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
