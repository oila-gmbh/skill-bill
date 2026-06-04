package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads a governed feature-task-runtime spec and extracts its run-invariants.
 * The sole filesystem read for the spec, so the CLI module can stay free of raw
 * file IO.
 */
@Inject
class FileSystemFeatureTaskRuntimeRunInvariantsSource : FeatureTaskRuntimeRunInvariantsSource {
  override fun read(specPath: Path): FeatureTaskRuntimeRunInvariants {
    val normalizedPath = specPath.toAbsolutePath().normalize()
    require(Files.isRegularFile(normalizedPath)) {
      "feature-task-runtime spec path '$normalizedPath' must point to a readable spec file."
    }
    val specText = Files.readString(normalizedPath)
    return FeatureTaskRuntimeRunInvariants(
      specReference = normalizedPath.toString(),
      featureSize = parseFeatureSize(specText),
      acceptanceCriteria = parseNumberedSection(specText, ACCEPTANCE_CRITERIA_HEADINGS),
      mandatesAndOverrides = parseBulletSection(specText, MANDATES_HEADINGS),
    )
  }

  private fun parseFeatureSize(specText: String): FeatureTaskRuntimeFeatureSize {
    val rawValue = FEATURE_SIZE_LINE.find(specText)?.groupValues?.get(1)
      ?: return FeatureTaskRuntimeFeatureSize.DEFAULT
    return FeatureTaskRuntimeFeatureSize.fromWire(rawValue)
  }

  private fun parseNumberedSection(specText: String, headings: Set<String>): List<String> {
    val body = sectionBody(specText, headings) ?: return emptyList()
    val items = mutableListOf<StringBuilder>()
    body.lineSequence().forEach { rawLine ->
      val line = rawLine.trimEnd()
      val numbered = NUMBERED_ITEM.find(line)
      when {
        numbered != null -> items += StringBuilder(numbered.groupValues[1].trim())
        line.isBlank() -> Unit
        items.isNotEmpty() -> items.last().append(' ').append(line.trim())
      }
    }
    return items.map { it.toString().trim() }.filter(String::isNotBlank)
  }

  private fun parseBulletSection(specText: String, headings: Set<String>): List<String> {
    val body = sectionBody(specText, headings) ?: return emptyList()
    val items = mutableListOf<StringBuilder>()
    body.lineSequence().forEach { rawLine ->
      val line = rawLine.trimEnd()
      val bullet = BULLET_ITEM.find(line)
      when {
        bullet != null -> items += StringBuilder(bullet.groupValues[1].trim())
        line.isBlank() -> Unit
        items.isNotEmpty() -> items.last().append(' ').append(line.trim())
      }
    }
    return items.map { it.toString().trim() }.filter(String::isNotBlank)
  }

  private fun sectionBody(specText: String, headings: Set<String>): String? {
    val lines = specText.lines()
    val startIndex = lines.indexOfFirst { line -> line.headingTitle()?.lowercase() in headings }
    if (startIndex < 0) {
      return null
    }
    val remaining = lines.drop(startIndex + 1)
    val endOffset = remaining.indexOfFirst { line -> line.headingTitle() != null }
    val sectionLines = if (endOffset < 0) remaining else remaining.take(endOffset)
    return sectionLines.joinToString(separator = "\n")
  }

  private fun String.headingTitle(): String? = HEADING.find(this)?.groupValues?.get(1)?.trim()

  private companion object {
    val ACCEPTANCE_CRITERIA_HEADINGS = setOf("acceptance criteria")
    val MANDATES_HEADINGS = setOf("mandates", "mandates and overrides", "mandates & overrides")
    val HEADING = Regex("""^#{2,6}\s+(.+)$""")
    val NUMBERED_ITEM = Regex("""^\s*\d+\.\s+(.*)$""")
    val BULLET_ITEM = Regex("""^\s*[-*]\s+(.*)$""")
    val FEATURE_SIZE_LINE = Regex("""(?im)^\s*(?:feature[_ -]size|size)\s*:\s*([^\r\n#]+)(?:\s+#.*)?$""")
  }
}
