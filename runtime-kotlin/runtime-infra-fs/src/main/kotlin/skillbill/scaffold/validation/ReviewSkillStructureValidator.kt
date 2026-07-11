@file:Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount", "TooManyFunctions")

package skillbill.scaffold.validation

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidReviewSkillStructureError
import skillbill.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.rendering.canonicalSeverityCloser
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal object ReviewSkillStructureValidator {
  fun validate(pack: Path) {
    val violations = violations(pack)
    if (violations.isNotEmpty()) {
      throw InvalidReviewSkillStructureError(
        "Platform pack '${pack.fileName}' violates the governed review-skill structure: " +
          violations.joinToString("; ") { violation -> "${displayPath(pack, violation.path)}: ${violation.rule}" },
      )
    }
  }

  fun violations(pack: Path): List<ReviewSkillStructureViolation> {
    if (pack.name == "platform-packs") {
      return Files.list(pack).use { packDirectories ->
        packDirectories.filter(Files::isDirectory).toList().flatMap(::violations)
      }
    }
    val manifest = manifest(pack) ?: return listOf(
      ReviewSkillStructureViolation(pack.resolve("platform.yaml"), "platform manifest mapping"),
    )
    val reviewFiles = contentFiles(pack)
    val hasReviewSurface = declaredBaseline(
      manifest,
    ) != null || declaredAreas(manifest).isNotEmpty() || reviewFiles.isNotEmpty()
    return buildList {
      if (hasReviewSurface) {
        addAll(manifestViolations(pack, manifest))
        addAll(reviewFiles.flatMap { file -> contentViolations(pack, manifest, file) })
        addAll(nativeAgentViolations(pack, manifest))
        addAll(authoredSidecarViolations(reviewFiles, manifest))
      }
      addAll(qualityCheckViolations(pack, manifest))
      addAll(allContentFiles(pack).flatMap(::severityViolations))
    }
  }

  private fun manifestViolations(pack: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val manifestFile = pack.resolve("platform.yaml")
    val declaredFiles = manifest["declared_files"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val areas = declaredFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val declaredContent = declaredContentFiles(declaredFiles, areas)
    val actualContent = contentFiles(pack).map { pack.relativize(it).portablePath() }.toSet()
    val declaredAreas = (manifest["declared_code_review_areas"] as? List<*>)?.map(Any?::toString)?.toSet().orEmpty()
    val areaKeys = areas.keys.map(Any?::toString).toSet()
    val metadata = manifest["area_metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val metadataKeys = metadata.keys.map(Any?::toString).toSet()
    val packLabel = (manifest["display_name"] ?: manifest["platform"]).toString()
    val focuses = metadata.mapNotNull { (rawArea, rawMetadata) ->
      val area = rawArea as? String ?: return@mapNotNull null
      val focus = (rawMetadata as? Map<*, *>)?.get("focus") as? String ?: return@mapNotNull null
      area to focus
    }
    return buildList {
      if (declaredContent != actualContent) add(violation(manifestFile, "manifest declares every review content file"))
      if (declaredAreas != areaKeys || areaKeys != metadataKeys) {
        add(violation(manifestFile, "manifest review area parity"))
      }
      if (!hasBespokeFocuses(focuses, metadata.size, packLabel)) {
        add(violation(manifestFile, "manifest bespoke area metadata"))
      }
      addAll(routingViolations(manifestFile, manifest))
      addAll(pointerViolations(manifestFile, manifest))
    }
  }

  private fun routingViolations(manifestFile: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val routing = manifest["routing_signals"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val strongSignals = (routing["strong"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val tieBreakers = (routing["tie_breakers"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    return buildList {
      strongSignals.filter { it.matches(Regex("\\*?\\.[A-Za-z0-9]+")) }.forEach { signal ->
        val counterpart = if (signal.startsWith("*.")) signal.removePrefix("*") else "*$signal"
        if (counterpart !in strongSignals) add(violation(manifestFile, "routing bare/glob pair"))
      }
      if (tieBreakers.none(::statesPositivePackDominance)) {
        add(violation(manifestFile, "routing positive pack dominance"))
      }
      if (tieBreakers.none(::statesAdjacentPackDisambiguation)) {
        add(violation(manifestFile, "routing adjacent-pack disambiguation"))
      }
      if (!containsAll(tieBreakers.joinToString(" "), "exclude", "generated", "vendor", "dominan")) {
        add(violation(manifestFile, "routing generated and vendored exclusion"))
      }
    }
  }

  private fun pointerViolations(manifestFile: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val declaredFiles = manifest["declared_files"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val areas = declaredFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val pointers = manifest["pointers"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val expected = declaredContentFiles(declaredFiles, areas).map { it.removeSuffix("/content.md") }.toSet()
    val actual = pointers.keys.map(Any?::toString).filter { it.startsWith("code-review/") }.toSet()
    return if (actual == expected) {
      emptyList()
    } else {
      listOf(
        violation(manifestFile, "generated pointers for declared review skills"),
      )
    }
  }

  private fun contentViolations(pack: Path, manifest: Map<*, *>, file: Path): List<ReviewSkillStructureViolation> {
    val parentViolation = if (hasInternalParent(file, "bill-code-review")) {
      emptyList()
    } else {
      listOf(violation(file, "code-review internal parent"))
    }
    val relativeFile = pack.relativize(file).portablePath()
    return parentViolation + if (relativeFile == declaredBaseline(manifest)) {
      baselineViolations(file)
    } else {
      specialistViolations(file, declaredAreaForFile(manifest, relativeFile))
    }
  }

  private fun baselineViolations(file: Path): List<ReviewSkillStructureViolation> {
    val required = listOf("Classification Rules", "Diff-Signal Routing Table", "Mixed Diffs", "Finding Discipline")
    val content = Files.readString(file)
    val classification = h2Section(content, "Classification Rules")
    val routing = h2Section(content, "Diff-Signal Routing Table")
    val mixedDiffs = h2Section(content, "Mixed Diffs")
    val discipline = h2Section(content, "Finding Discipline")
    val composedMixedDiffs = composedBaselineSections(file, "Mixed Diffs")
    val composedDiscipline = composedBaselineSections(file, "Finding Discipline")
    return buildList {
      if (headings(file) != required) add(violation(file, "baseline H2 sequence"))
      if (!classification.contains("If ") || !classification.contains("Otherwise")) {
        add(violation(file, "classification decisions"))
      }
      val declaredAreas = declaredAreasForContent(file)
      if (declaredAreas.isEmpty() || declaredAreas.any { area ->
          !Regex("(?m)^- .+ -> `$area` specialist\\.$").containsMatchIn(routing)
        }
      ) {
        add(violation(file, "signal-to-specialist routing mappings"))
      }
      if (!containsAll(mixedDiffs, "keep", "whole review", "lightweight", "file-level classification")) {
        add(violation(file, "mixed-diff retention"))
      }
      if (!containsAll(mixedDiffs, "specialist", "scope", "generated", "vendored", "non-stack")) {
        add(violation(file, "scoping exclusions"))
      }
      if (!containsAll(discipline, "severity", "precondition")) add(violation(file, "finding discipline"))
      if (!containsAll(composedMixedDiffs, "deterministic", "wave", "capacity")) {
        add(violation(file, "deterministic wave batching"))
      }
      if (!containsAll(composedMixedDiffs, "retain", "every selected", "result")) {
        add(violation(file, "selected specialist result retention"))
      }
      if (!containsAll(composedDiscipline, "attributed", "merge")) {
        add(violation(file, "attributed finding merge"))
      }
      if (!containsAll(composedDiscipline, "deduplicat", "without losing", "evidence")) {
        add(violation(file, "evidence-preserving deduplication"))
      }
    }
  }

  private fun specialistViolations(file: Path, area: String?): List<ReviewSkillStructureViolation> {
    val required = listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules")
    val content = Files.readString(file)
    val projectRules = h2Section(content, "Project-Specific Rules")
    return buildList {
      if (headings(file) != required && headings(file) != required + "Repo-Local Knowledge") {
        add(violation(file, "specialist H2 sequence"))
      }
      if (!projectRules.contains("### ")) add(violation(file, "specialist H3 grouping"))
      if (!hasCanonicalSeverityCloser(area, content)) add(violation(file, "canonical severity closer"))
      if (!projectRules.contains('`') ||
        !Regex("(?i)\\b(must|do not|never|require|reject|verify|preserve)\\b").containsMatchIn(projectRules) ||
        !Regex("(?i)failure|error|invariant|boundary").containsMatchIn(projectRules)
      ) {
        add(violation(file, "enforceable stack failure modes"))
      }
      if (Regex("(?i)\\b(run|invoke|spawn)\\b[^\\n]*bill-[a-z0-9-]+-code-review-").containsMatchIn(content)) {
        add(violation(file, "sibling specialist invocation"))
      }
      if (area == "ui" && !containsAll(ignoreSection(content), "ux-accessibility", "security")) {
        add(violation(file, "UI lane deferrals"))
      }
      if (area == "ux-accessibility" && !containsAll(ignoreSection(content), "ui", "security")) {
        add(violation(file, "UX accessibility lane deferrals"))
      }
    }
  }

  private fun nativeAgentViolations(pack: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val baseline = declaredBaseline(manifest) ?: return emptyList()
    val agentsFile = pack.resolve(baseline).parent.resolve("native-agents/agents.yaml")
    if (!Files.isRegularFile(agentsFile)) return listOf(violation(agentsFile, "native-agent source bundle"))
    val agents = try {
      parseNativeAgentBundle(agentsFile)
    } catch (error: IllegalArgumentException) {
      invalidNativeAgentBundle(agentsFile, error)
    } catch (error: IOException) {
      invalidNativeAgentBundle(agentsFile, error)
    }
    val displayName = (manifest["display_name"] ?: manifest["platform"]).toString()
    val areaMetadata = manifest["area_metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val expectedDescriptions = areaMetadata.mapNotNull { (rawArea, rawMetadata) ->
      val area = rawArea as? String ?: return@mapNotNull null
      val focus = (rawMetadata as? Map<*, *>)?.get("focus") as? String ?: return@mapNotNull null
      "bill-${pack.name}-code-review-$area" to
        "$displayName ${area.replace('-', ' ')} specialist code reviewer. " +
        "Runs against $focus. Returns a Risk Register in the F-XXX bullet format."
    }.toMap()
    return buildList {
      if (agents.any { agent -> expectedDescriptions[agent.name] != agent.description }) {
        add(violation(agentsFile, "native-agent description pattern"))
      }
      val expectedNames = declaredAreas(manifest).map { "bill-${pack.name}-code-review-$it" }.toSet()
      if (agents.map { it.name }.toSet() != expectedNames) {
        add(violation(agentsFile, "native-agent specialist coverage"))
      }
    }
  }

  private fun qualityCheckViolations(pack: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val declared = manifest["declared_quality_check_file"] as? String ?: return emptyList()
    val file = pack.resolve(declared)
    if (!Files.isRegularFile(file)) return listOf(violation(file, "declared quality-check source"))
    val content = Files.readString(file)
    val execution = h2Section(content, "Execution Steps")
    val fixStrategy = h2Section(content, "Fix Strategy")
    return buildList {
      if (!hasInternalParent(file, "bill-code-check")) add(violation(file, "quality-check internal parent"))
      if (headings(file) != listOf("Purpose", "Execution Steps", "Fix Strategy")) {
        add(violation(file, "quality-check H2 sequence"))
      }
      if (!containsAll(execution, "build file", "wrapper", "CI")) {
        add(violation(file, "quality-check command discovery"))
      }
      if (!orderedFragments(execution, "build file", "wrapper", "CI configuration", "before falling back")) {
        add(violation(file, "quality-check fallback ordering"))
      }
      if (!containsAll(execution, "files in scope")) add(violation(file, "quality-check scoped files"))
      if (!containsAll(execution, "pack's quality-check entrypoint")) {
        add(violation(file, "quality-check pack entrypoint"))
      }
      if (!containsAll(fixStrategy, "priority-ordered", "never suppress")) {
        add(violation(file, "quality-check fix discipline"))
      }
      if (!containsAll(fixStrategy, "re-run targeted checks")) {
        add(violation(file, "quality-check targeted rerun"))
      }
      if (!containsAll(fixStrategy, "full suite when targeted checks cannot establish safety")) {
        add(violation(file, "quality-check escalation"))
      }
    }
  }

  private fun authoredSidecarViolations(
    reviewFiles: List<Path>,
    manifest: Map<*, *>,
  ): List<ReviewSkillStructureViolation> = reviewFiles
    .filter { !it.parent.name.endsWith("code-review") }
    .flatMap { contentFile ->
      Files.list(contentFile.parent).use { siblings ->
        val sidecars = siblings.filter { it.fileName.toString().endsWith(".md") && it != contentFile }.toList()
        if (sidecars.size > 1) return@use listOf(violation(contentFile, "one authored rubric sidecar"))
        val sidecar = sidecars.singleOrNull() ?: return@use emptyList()
        val sourceContent = Files.readString(contentFile)
        val sidecarContent = Files.readString(sidecar)
        buildList {
          if (sidecar.fileName.toString().lowercase() in reservedGeneratedSidecarNames(manifest)) {
            add(violation(sidecar, "reserved generated sidecar name"))
          }
          if (containsWrapperOrProviderOutput(sidecarContent)) {
            add(violation(sidecar, "wrapper or provider sidecar content"))
          }
          if (!isSpecialistRubric(sidecarContent)) add(violation(sidecar, "specialist rubric sidecar content"))
          if (!sourceContent.contains(sidecar.fileName.toString()) ||
            !Regex("(?i)insufficient").containsMatchIn(sourceContent)
          ) {
            add(violation(contentFile, "authored rubric sidecar rationale"))
          }
        }
      }
    }

  private fun reservedGeneratedSidecarNames(manifest: Map<*, *>): Set<String> {
    val pointers = manifest["pointers"] as? Map<*, *>
    val declaredNames = pointers.orEmpty().values.flatMap { entries ->
      (entries as? List<*>)?.filterIsInstance<Map<*, *>>()?.mapNotNull { it["name"] as? String }.orEmpty()
    }
    return (generatedSidecarNames + declaredNames).map(String::lowercase).toSet()
  }

  private fun severityViolations(file: Path): List<ReviewSkillStructureViolation> = severityRatings(
    Files.readString(file),
  ).filter { it !in allowedSeverities }.map { rating -> violation(file, "off-enum severity rating $rating") }

  private fun severityRatings(content: String): Set<String> = buildSet {
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

  private fun hasBespokeFocuses(focuses: List<Pair<String, String>>, metadataSize: Int, packLabel: String): Boolean {
    if (focuses.size != metadataSize || focuses.map { it.second }.toSet().size != focuses.size) return false
    return focuses.all { (area, focus) ->
      !isDefaultDerivedFocus(area, focus, packLabel) &&
        concreteFocusTerms(area, focus, packLabel).size >= MINIMUM_CONCRETE_FOCUS_TERMS
    }
  }

  private fun hasCanonicalSeverityCloser(area: String?, content: String): Boolean {
    val rules = h2Section(content, "Project-Specific Rules")
    val finalRule = rules.lineSequence().map(String::trim).filter { it.startsWith("- ") }.lastOrNull()
    return area != null && finalRule == canonicalSeverityCloser(area)
  }

  private fun declaredAreasForContent(file: Path): Set<String> = manifest(file.parent.parent.parent)
    ?.let(::declaredAreas)
    ?.toSet()
    .orEmpty()

  private fun composedBaselineSections(file: Path, heading: String): String {
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

  private fun manifest(pack: Path): Map<*, *>? {
    val file = pack.resolve("platform.yaml")
    if (!Files.isRegularFile(file)) return null
    return Yaml().load<Any?>(Files.readString(file)) as? Map<*, *>
  }

  private fun declaredBaseline(manifest: Map<*, *>): String? =
    ((manifest["declared_files"] as? Map<*, *>)?.get("baseline") as? String)

  private fun declaredAreas(manifest: Map<*, *>): List<String> =
    (manifest["declared_code_review_areas"] as? List<*>)?.filterIsInstance<String>().orEmpty()

  private fun declaredAreaForFile(manifest: Map<*, *>, relativeFile: String): String? {
    val files = manifest["declared_files"] as? Map<*, *> ?: return null
    val areas = files["areas"] as? Map<*, *> ?: return null
    return areas.entries.firstOrNull { (_, path) -> path == relativeFile }?.key as? String
      ?: APPROVED_CODE_REVIEW_AREAS.sortedByDescending(String::length).firstOrNull { area ->
        relativeFile.substringBeforeLast("/content.md").endsWith("-$area")
      }
  }

  private fun declaredContentFiles(declaredFiles: Map<*, *>, areas: Map<*, *>): Set<String> = buildSet {
    (declaredFiles["baseline"] as? String)?.let(::add)
    areas.values.filterIsInstance<String>().forEach(::add)
  }

  private fun contentFiles(pack: Path): List<Path> {
    val root = pack.resolve("code-review")
    if (!Files.isDirectory(root)) return emptyList()
    return Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "content.md" }.toList() }
  }

  private fun allContentFiles(pack: Path): List<Path> = Files.walk(pack).use { paths ->
    paths.filter { it.fileName.toString() == "content.md" }.toList()
  }

  private fun headings(file: Path): List<String> = Files.readAllLines(file)
    .filter { it.startsWith("## ") }
    .map { it.removePrefix("## ") }

  private fun hasInternalParent(file: Path, parent: String): Boolean =
    Regex("(?m)^internal-for: ${Regex.escape(parent)}\\s*$").containsMatchIn(Files.readString(file))

  private fun containsWrapperOrProviderOutput(content: String): Boolean = content.startsWith("---\n") ||
    containsAll(content, "## Descriptor") ||
    Regex("(?m)^(compose:|developer_instructions:|model:)").containsMatchIn(content)

  private fun isSpecialistRubric(content: String): Boolean {
    val title = content.lineSequence().firstOrNull { it.startsWith("# ") }.orEmpty()
    return Regex("(?i)^# .*(rubric|guidelines|checks|rules)").containsMatchIn(title) &&
      content.lineSequence().any { it.startsWith("## ") } &&
      Regex("(?i)\\b(must|never|verify|reject|flag|require)\\b").containsMatchIn(content)
  }

  private fun ignoreSection(content: String): String = content.substringAfter("## Ignore", "").substringBefore(
    "## Applicability",
  )

  private fun h2Section(content: String, heading: String): String =
    content.substringAfter("## $heading", "").substringBefore("\n## ")

  private fun orderedFragments(content: String, vararg fragments: String): Boolean {
    val normalized = content.lowercase()
    return fragments.fold(-1) { previousIndex, fragment ->
      if (previousIndex == Int.MIN_VALUE) return@fold Int.MIN_VALUE
      normalized.indexOf(fragment.lowercase(), previousIndex + 1).takeIf { it >= 0 } ?: Int.MIN_VALUE
    } != Int.MIN_VALUE
  }

  private fun containsAll(content: String, vararg fragments: String): Boolean =
    fragments.all { content.contains(it, ignoreCase = true) }

  private fun statesPositivePackDominance(rule: String): Boolean =
    containsAll(rule, "prefer", "dominat") && !rule.contains("do not prefer", ignoreCase = true)

  private fun statesAdjacentPackDisambiguation(rule: String): Boolean = containsAll(rule, "do not prefer") &&
    (rule.contains("adjacent", ignoreCase = true) || containsAll(rule, "another", "dominant", "stack")) &&
    !Regex("(?i)\\bdo not prefer\\s+(?:an?\\s+|the\\s+)?(?:adjacent|another\\s+dominant\\s+stack)")
      .containsMatchIn(rule)

  private fun isDefaultDerivedFocus(area: String, focus: String, packLabel: String): Boolean {
    val defaultFocus = defaultAreaFocus(area)
    return focus.equals(defaultFocus, ignoreCase = true) || focus.equals("$packLabel $defaultFocus", ignoreCase = true)
  }

  private fun concreteFocusTerms(area: String, focus: String, packLabel: String): Set<String> {
    val generic = focusTerms("$packLabel ${area.replace('-', ' ')} ${defaultAreaFocus(area)}") + vagueFocusTerms
    return focusTerms(focus) - generic
  }

  private fun focusTerms(value: String): Set<String> = Regex("[a-z0-9]+")
    .findAll(value.lowercase())
    .map(MatchResult::value)
    .filter { it.length > 2 }
    .toSet()

  private fun displayPath(pack: Path, path: Path): String = runCatching { pack.relativize(path).portablePath() }
    .getOrDefault(path.toString())

  private fun Path.portablePath(): String = toString().replace('\\', '/')

  private fun violation(path: Path, rule: String) = ReviewSkillStructureViolation(path, rule)

  private fun invalidNativeAgentBundle(path: Path, error: Exception): Nothing = throw InvalidReviewSkillStructureError(
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

internal data class ReviewSkillStructureViolation(val path: Path, val rule: String) {
  override fun toString(): String = "$path: $rule"
}

internal fun validateReviewSkillStructure(pack: PlatformManifest) {
  val baseline = pack.declaredFiles.baseline ?: return
  val bundle = baseline.parent.resolve("native-agents").resolve(NATIVE_AGENT_BUNDLE_FILE)
  if (!Files.isRegularFile(bundle)) return

  val actualAgents = parseNativeAgentBundle(bundle)
  val actualNames = actualAgents.map { it.name }
  val specialistNames = pack.declaredCodeReviewAreas
    .map { area -> pack.declaredFiles.areas.getValue(area).parent.fileName.toString() }
    .toSet()
  val baselineName = baseline.parent.fileName.toString()
  val expectedNames = specialistNames + baselineName
  val actualNameSet = actualNames.toSet()
  val governedNameSet = actualAgents.filter { it.composition != null }.map { it.name }.toSet()
  val unknown = governedNameSet - expectedNames
  if (actualNames.size != actualNameSet.size || unknown.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '${pack.slug}': native-agent bundle may not declare duplicate agents or unknown " +
        "governed-content agents; unknown=${unknown.sorted()}.",
    )
  }
}
