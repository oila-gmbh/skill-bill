package skillbill.scaffold.validation

import skillbill.error.InvalidReviewSkillStructureError
import org.yaml.snakeyaml.Yaml
import skillbill.scaffold.rendering.canonicalSeverityCloser
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal object ReviewSkillStructureValidatorHelpers {
  private const val MARKDOWN_HEADING_PREFIX_LENGTH = 3
  fun severityViolations(file: Path): List<ReviewSkillStructureViolation> = severityRatings(
    Files.readString(file),
  ).filter { it !in allowedSeverities }.map { rating -> violation(file, "off-enum severity rating $rating") }

  fun definesOwnSeverityVocabulary(content: String): Boolean {
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

  fun severityRatings(content: String): Set<String> = buildSet {
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

  fun hasBespokeFocuses(focuses: List<Pair<String, String>>, metadataSize: Int, packLabel: String): Boolean {
    if (focuses.size != metadataSize || focuses.map { it.second }.toSet().size != focuses.size) return false
    return focuses.all { (area, focus) ->
      !isDefaultDerivedFocus(area, focus, packLabel) &&
        concreteFocusTerms(area, focus, packLabel).size >= MINIMUM_CONCRETE_FOCUS_TERMS
    }
  }

  fun hasCanonicalSeverityCloser(area: String?, content: String): Boolean {
    val rules = h2Section(content, "Project-Specific Rules")
    val finalRule = rules.lineSequence().map(String::trim).filter { it.startsWith("- ") }.lastOrNull()
    return area != null && finalRule == canonicalSeverityCloser(area)
  }

  fun declaredAreasForContent(file: Path): Set<String> = manifest(file.parent.parent.parent)
    ?.let(::declaredAreas)
    ?.toSet()
    .orEmpty()

  fun composedBaselineSections(file: Path, heading: String): String {
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

  fun manifest(pack: Path): Map<*, *>? {
    val file = pack.resolve("platform.yaml")
    if (!Files.isRegularFile(file)) return null
    return Yaml().load<Any?>(Files.readString(file)) as? Map<*, *>
  }

  fun declaredBaseline(manifest: Map<*, *>): String? =
    ((manifest["declared_files"] as? Map<*, *>)?.get("baseline") as? String)

  fun declaredAreas(manifest: Map<*, *>): List<String> =
    (manifest["declared_code_review_areas"] as? List<*>)?.filterIsInstance<String>().orEmpty()

  fun declaredAreaForFile(manifest: Map<*, *>, relativeFile: String): String? {
    val files = manifest["declared_files"] as? Map<*, *> ?: return null
    val areas = files["areas"] as? Map<*, *> ?: return null
    return areas.entries.firstOrNull { (_, path) -> path == relativeFile }?.key as? String
      ?: APPROVED_CODE_REVIEW_AREAS.sortedByDescending(String::length).firstOrNull { area ->
        relativeFile.substringBeforeLast("/content.md").endsWith("-$area")
      }
  }

  fun declaredContentFiles(declaredFiles: Map<*, *>, areas: Map<*, *>): Set<String> = buildSet {
    (declaredFiles["baseline"] as? String)?.let(::add)
    areas.values.filterIsInstance<String>().forEach(::add)
  }

  fun contentFiles(pack: Path): List<Path> {
    val root = pack.resolve("code-review")
    if (!Files.isDirectory(root)) return emptyList()
    return Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "content.md" }.toList() }
  }

  fun allContentFiles(pack: Path): List<Path> = Files.walk(pack).use { paths ->
    paths.filter { it.fileName.toString() == "content.md" }.toList()
  }

  fun headings(file: Path): List<String> = Files.readAllLines(file)
    .filter { it.startsWith("## ") }
    .map { it.removePrefix("## ") }

  fun hasInternalParent(file: Path, parent: String): Boolean =
    Regex("(?m)^internal-for: ${Regex.escape(parent)}\\s*$").containsMatchIn(Files.readString(file))

  fun containsWrapperOrProviderOutput(content: String): Boolean = content.startsWith("---\n") ||
    containsAll(content, "## Descriptor") ||
    Regex("(?m)^(compose:|developer_instructions:|model:)").containsMatchIn(content)

  fun isSpecialistRubric(content: String): Boolean {
    val title = content.lineSequence().firstOrNull { it.startsWith("# ") }.orEmpty()
    return Regex("(?i)^# .*(rubric|guidelines|checks|rules)").containsMatchIn(title) &&
      content.lineSequence().any { it.startsWith("## ") } &&
      Regex("(?i)\\b(must|never|verify|reject|flag|require)\\b").containsMatchIn(content)
  }

  fun ignoreSection(content: String): String = content.substringAfter("## Ignore", "").substringBefore(
    "## Applicability",
  )

  fun h2Section(content: String, heading: String): String =
    content.substringAfter("## $heading", "").substringBefore("\n## ")

  fun orderedFragments(content: String, vararg fragments: String): Boolean {
    val normalized = content.lowercase()
    return fragments.fold(-1) { previousIndex, fragment ->
      if (previousIndex == Int.MIN_VALUE) return@fold Int.MIN_VALUE
      normalized.indexOf(fragment.lowercase(), previousIndex + 1).takeIf { it >= 0 } ?: Int.MIN_VALUE
    } != Int.MIN_VALUE
  }

  fun containsAll(content: String, vararg fragments: String): Boolean =
    fragments.all { content.contains(it, ignoreCase = true) }

  fun statesPositivePackDominance(rule: String): Boolean =
    containsAll(rule, "prefer", "dominat") && !rule.contains("do not prefer", ignoreCase = true)

  fun statesAdjacentPackDisambiguation(rule: String): Boolean = containsAll(rule, "do not prefer") &&
    (rule.contains("adjacent", ignoreCase = true) || containsAll(rule, "another", "dominant", "stack")) &&
    !Regex("(?i)\\bdo not prefer\\s+(?:an?\\s+|the\\s+)?(?:adjacent|another\\s+dominant\\s+stack)")
      .containsMatchIn(rule)

  fun isDefaultDerivedFocus(area: String, focus: String, packLabel: String): Boolean {
    val defaultFocus = defaultAreaFocus(area)
    return focus.equals(defaultFocus, ignoreCase = true) || focus.equals("$packLabel $defaultFocus", ignoreCase = true)
  }

  fun concreteFocusTerms(area: String, focus: String, packLabel: String): Set<String> {
    val generic = focusTerms("$packLabel ${area.replace('-', ' ')} ${defaultAreaFocus(area)}") + vagueFocusTerms
    return focusTerms(focus) - generic
  }

  fun focusTerms(value: String): Set<String> = Regex("[a-z0-9]+")
    .findAll(value.lowercase())
    .map(MatchResult::value)
    .filter { it.length > 2 }
    .toSet()

  fun displayPath(pack: Path, path: Path): String = runCatching { portablePath(pack.relativize(path)) }
    .getOrDefault(path.toString())

  fun portablePath(path: Path): String = path.toString().replace('\\', '/')

  fun reservedGeneratedSidecarNames(manifest: Map<*, *>): Set<String> {
    val pointers = manifest["pointers"] as? Map<*, *>
    val declaredNames = pointers.orEmpty().values.flatMap { entries ->
      (entries as? List<*>)?.filterIsInstance<Map<*, *>>()?.mapNotNull { it["name"] as? String }.orEmpty()
    }
    return (generatedSidecarNames + declaredNames).map(String::lowercase).toSet()
  }

  fun violation(path: Path, rule: String) = ReviewSkillStructureViolation(path, rule)

  fun invalidNativeAgentBundle(path: Path, error: Exception): Nothing = throw InvalidReviewSkillStructureError(
    "$path: invalid native-agent source bundle: ${error.message}",
    error,
  )

  private val allowedSeverities = setOf("Blocker", "Major", "Minor")
  private const val MINIMUM_CONCRETE_FOCUS_TERMS = 1
  private val vagueFocusTerms = setOf(
    "area", "checks", "code", "concerns", "custom", "focus", "general", "generic", "review", "risks",
    "specialist", "specific", "tailored", "unique",
  )
  private val generatedSidecarNames = setOf(
    "review-orchestrator.md",
    "review-delegation.md",
    "review-scope.md",
    "shell-ceremony.md",
    "specialist-contract.md",
    "stack-routing.md",
    "telemetry-contract.md",
  )
}
