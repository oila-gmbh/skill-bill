package skillbill.scaffold.validation

import org.yaml.snakeyaml.Yaml
import skillbill.scaffold.rendering.canonicalSeverityCloser
import java.nio.file.Files
import java.nio.file.Path

internal fun severityViolations(file: Path): List<ReviewSkillStructureViolation> = severityRatings(
  Files.readString(file),
).filter { it !in allowedSeverities }.map { rating -> violation(file, "off-enum severity rating $rating") }

internal fun definesOwnSeverityVocabulary(content: String): Boolean {
  val headings = Regex("(?m)^#{1,3} ").findAll(content)
    .map { it.value.drop(MARKDOWN_HEADING_PREFIX_LENGTH).trim() }
    .toList()
  val hasSeverityHeading = headings.any { heading ->
    heading.contains("severity", ignoreCase = true) ||
      heading.contains("rating", ignoreCase = true) ||
      heading.contains("scale", ignoreCase = true)
  }
  val hasLegendOrTable = Regex("(?i)(?:severity|rating)\\s+(?:legend|table|scale|key):").containsMatchIn(content)
  val hasDefinitionProse = Regex(
    "(?i)\\b(?:Blocker|Major|Minor)\\s+(?:means?|is defined as|refers to|indicates?|represents?)\\b",
  ).containsMatchIn(content)
  return hasSeverityHeading || hasLegendOrTable || hasDefinitionProse
}

internal fun severityRatings(content: String): Set<String> = buildSet {
  Regex("(?m)\\bSeverity (?:ratings?|scale):\\s*([^\\n]+)").findAll(content).forEach { match ->
    Regex("\\b[A-Z][a-z]+\\b").findAll(match.groupValues[1]).mapTo(this) { it.value }
  }
  Regex("(?m)^- For ([A-Z][a-z]+)(?: or ([A-Z][a-z]+))?(?: [a-z-]+)? findings\\b")
    .findAll(content)
    .forEach { match -> match.groupValues.drop(1).filter(String::isNotEmpty).forEach(::add) }
  Regex("(?m)^- \\[F-[^]]+] ([A-Z][a-z]+) \\|").findAll(content).forEach { match -> add(match.groupValues[1]) }
  val ratingContext = "(?:rate|rated|rating|severity|at most|at least|classify|classified as)"
  val ratingValue = "(Blocker|Major|Minor|Nit|Critical|Warning)"
  Regex("(?i)\\b$ratingContext\\b[^.\\n:|]{0,40}[:|]?\\s*$ratingValue\\b")
    .findAll(content)
    .forEach { match -> add(match.groupValues[1].replaceFirstChar(Char::uppercase)) }
}

internal fun hasBespokeFocuses(focuses: List<Pair<String, String>>, metadataSize: Int, packLabel: String): Boolean {
  if (focuses.size != metadataSize || focuses.map { it.second }.toSet().size != focuses.size) return false
  return focuses.all { (area, focus) ->
    !isDefaultDerivedFocus(area, focus, packLabel) &&
      concreteFocusTerms(area, focus, packLabel).size >= MINIMUM_CONCRETE_FOCUS_TERMS
  }
}

internal fun hasCanonicalSeverityCloser(area: String?, content: String): Boolean {
  val rules = h2Section(content, "Project-Specific Rules")
  val finalRule = rules.lineSequence().map(String::trim).filter { it.startsWith("- ") }.lastOrNull()
  return area != null && finalRule == canonicalSeverityCloser(area)
}

internal fun declaredAreasForContent(file: Path): Set<String> = manifest(file.parent.parent.parent)
  ?.let(::declaredAreas)
  ?.toSet()
  .orEmpty()

internal fun composedBaselineSections(file: Path, heading: String): String {
  val pack = file.parent.parent.parent
  val packManifest = manifest(pack) ?: return h2Section(Files.readString(file), heading)
  val composition = packManifest["code_review_composition"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
  val layers = (composition["baseline_layers"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
  val inheritedSections = layers.filter { it["required"] == true }.mapNotNull { layer ->
    val platform = layer["platform"] as? String ?: return@mapNotNull null
    val inheritedPack = pack.parent.resolve(platform)
    val inheritedManifest = manifest(inheritedPack) ?: return@mapNotNull null
    val baseline = declaredBaseline(inheritedManifest) ?: return@mapNotNull null
    h2Section(Files.readString(inheritedPack.resolve(baseline)), heading)
  }
  return (listOf(h2Section(Files.readString(file), heading)) + inheritedSections).joinToString("\n")
}

internal fun manifest(pack: Path): Map<*, *>? {
  val file = pack.resolve("platform.yaml")
  if (!Files.isRegularFile(file)) return null
  return Yaml().load<Any?>(Files.readString(file)) as? Map<*, *>
}

internal fun declaredBaseline(manifest: Map<*, *>): String? =
  ((manifest["declared_files"] as? Map<*, *>)?.get("baseline") as? String)

internal fun declaredAreas(manifest: Map<*, *>): List<String> =
  (manifest["declared_code_review_areas"] as? List<*>)?.filterIsInstance<String>().orEmpty()
