package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.scaffold.rendering.canonicalSeverityCloser
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewSkillStructureConformanceTest {
  @Test
  fun `repository pack content uses only the governed severity vocabulary`() {
    val violations = allContentFiles(repoRootFromTest().resolve("platform-packs"))
      .flatMap(::severityViolations)

    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `severity vocabulary is closed in rating contexts without flagging incidental prose`() {
    val root = Files.createTempDirectory("review-severity-")
    val contentFile = root.resolve("content.md")

    listOf("Nit", "Critical", "Warning").forEach { rating ->
      Files.writeString(contentFile, "Severity ratings: Blocker, Major, Minor, $rating\n")
      assertTrue(severityViolations(contentFile).any { rating in it.rule }, "Expected rejection for $rating")

      Files.writeString(contentFile, "Rate the finding at most $rating when impact is contained.\n")
      assertTrue(
        severityViolations(contentFile).any { rating in it.rule },
        "Expected natural-form rejection for $rating",
      )
    }

    Files.writeString(contentFile, "Nit is an abbreviation in unrelated incidental prose.\n")
    assertEquals(emptyList(), severityViolations(contentFile))
  }

  @Test
  fun `non-exempt packs satisfy the complete review skill structure standard`() {
    val repositoryViolations = structureViolations(repoRootFromTest().resolve("platform-packs"))
    assertEquals(emptyList(), repositoryViolations, repositoryViolations.joinToString("\n"))

    val root = Files.createTempDirectory("review-skill-structure-")
    val pack = root.resolve("platform-packs/fixture")
    writeConformingFixture(pack)

    assertEquals(emptyList(), structureViolations(pack), structureViolations(pack).joinToString("\n"))

    Files.writeString(
      pack.resolve("code-review/bill-fixture-code-review-security/content.md"),
      "## Focus\n\nMissing the governed skeleton.\n",
    )
    assertTrue(structureViolations(pack).any { it.rule == "specialist H2 sequence" })

    writeConformingFixture(pack)
    Files.writeString(pack.resolve("quality-check/bill-fixture-code-check/content.md"), "## Purpose\n")
    assertTrue(structureViolations(pack).any { it.rule == "quality-check H2 sequence" })

    writeConformingFixture(pack)
    Files.writeString(
      pack.resolve("code-review/bill-fixture-code-review/native-agents/agents.yaml"),
      "agents:\n  - name: bill-fixture-code-review-security\n    description: wrong\n",
    )
    assertTrue(structureViolations(pack).any { it.rule == "native-agent description pattern" })

    assertSemanticNegativeFixtures(pack)
  }

  private fun assertSemanticNegativeFixtures(pack: Path) {
    assertBaselineNegativeFixtures(pack)
    assertSpecialistNegativeFixtures(pack)
    assertQualityCheckNegativeFixtures(pack)
    assertManifestAndSidecarNegativeFixtures(pack)
  }

  private fun assertBaselineNegativeFixtures(pack: Path) {
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      vagueClassificationBaseline,
      "classification decisions",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      misplacedClassificationBaseline,
      "classification decisions",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      vagueRoutingBaseline,
      "signal-to-specialist routing mappings",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      mixedDiffDropsLanes,
      "mixed-diff retention",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      unscopedBaseline,
      "scoping exclusions",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      undisciplinedBaseline,
      "finding discipline",
    )
  }

  private fun assertSpecialistNegativeFixtures(pack: Path) {
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review-security/content.md",
      vagueSpecialist,
      "enforceable stack failure modes",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review-security/content.md",
      misplacedSpecialistRule,
      "enforceable stack failure modes",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review-security/content.md",
      siblingInvokingSpecialist,
      "sibling specialist invocation",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review-security/content.md",
      misplacedSeverityCloser,
      "canonical severity closer",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review-security/content.md",
      wrongAreaSeverityCloser,
      "canonical severity closer",
    )
    assertSpecialistRuleViolation(pack, "ui", fixtureSpecialist, "UI lane deferrals")
    assertSpecialistRuleViolation(pack, "ux-accessibility", fixtureSpecialist, "UX accessibility lane deferrals")
  }

  private fun assertQualityCheckNegativeFixtures(pack: Path) {
    assertQualityCheckRuleViolation(pack, shallowQualityCheck, "quality-check command discovery")
    assertQualityCheckRuleViolation(pack, misplacedQualityCheckDiscovery, "quality-check command discovery")
    assertQualityCheckRuleViolation(pack, defaultFirstQualityCheck, "quality-check fallback ordering")
    assertQualityCheckRuleViolation(pack, unscopedQualityCheck, "quality-check scoped files")
    assertQualityCheckRuleViolation(pack, noEntrypointQualityCheck, "quality-check pack entrypoint")
    assertQualityCheckRuleViolation(pack, suppressingQualityCheck, "quality-check fix discipline")
    assertQualityCheckRuleViolation(pack, noTargetedRerunQualityCheck, "quality-check targeted rerun")
    assertQualityCheckRuleViolation(pack, unconditionalFullSuiteQualityCheck, "quality-check escalation")
  }

  private fun assertManifestAndSidecarNegativeFixtures(pack: Path) {
    assertManifestRuleViolation(pack, missingDeclaredAreaManifest, "manifest review area parity")
    assertManifestRuleViolation(pack, genericAreaMetadataManifest, "manifest bespoke area metadata")
    assertManifestRuleViolation(pack, bareOnlyRoutingManifest, "routing bare/glob pair")
    assertManifestRuleViolation(pack, globOnlyRoutingManifest, "routing bare/glob pair")
    assertManifestRuleViolation(pack, noPositiveDominanceManifest, "routing positive pack dominance")
    assertManifestRuleViolation(pack, noAdjacentDisambiguationManifest, "routing adjacent-pack disambiguation")
    assertManifestRuleViolation(pack, noGeneratedExclusionManifest, "routing generated and vendored exclusion")
    assertManifestRuleViolation(pack, noVendoredExclusionManifest, "routing generated and vendored exclusion")
    assertSidecarRuleViolation(pack, "shell-ceremony.md", validSidecar, "reserved generated sidecar name")
    assertSidecarRuleViolation(pack, "review-rubric.md", wrapperSidecar, "wrapper or provider sidecar content")
    assertSidecarRuleViolation(pack, "team-notes.md", organizationSidecar, "specialist rubric sidecar content")

    writeConformingFixture(pack)
    Files.delete(pack.resolve("code-review/bill-fixture-code-review/native-agents/agents.yaml"))
    assertTrue(structureViolations(pack).any { it.rule == "native-agent source bundle" })
  }

  internal fun structureViolations(pack: Path): List<StructureViolation> {
    val exemptions = setOf("ios", "php", "python")
    // SKILL-112 subtasks 2-7 remove one pack each; subtask 8 removes this mechanism.
    if (pack.name == "platform-packs") {
      return Files.list(pack).use { packDirectories ->
        packDirectories
          .filter { Files.isDirectory(it) }
          .filter { it.name !in exemptions }
          .toList()
          .flatMap(::structureViolations)
      }
    }
    if (pack.name in exemptions) return emptyList()

    return manifestViolations(pack) +
      contentFiles(pack).flatMap(::contentViolations) +
      nativeAgentViolations(pack) +
      qualityCheckViolations(pack) +
      authoredSidecarViolations(pack)
  }

  private fun manifestViolations(pack: Path): List<StructureViolation> {
    val manifestFile = pack.resolve("platform.yaml")
    if (!Files.isRegularFile(manifestFile)) return listOf(StructureViolation(manifestFile, "platform manifest"))
    val manifest = Yaml().load<Any?>(Files.readString(manifestFile)) as? Map<*, *>
      ?: return listOf(StructureViolation(manifestFile, "platform manifest mapping"))
    return manifestContentViolations(pack, manifestFile, manifest) +
      manifestRoutingViolations(manifestFile, manifest) +
      manifestPointerViolations(manifestFile, manifest)
  }

  private fun manifestContentViolations(
    pack: Path,
    manifestFile: Path,
    manifest: Map<*, *>,
  ): List<StructureViolation> {
    val declaredFiles = manifest["declared_files"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val areas = declaredFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val declaredContent = declaredContentFiles(declaredFiles, areas)
    val actualContent = contentFiles(pack).map { pack.relativize(it).toString() }.toSet()
    val declaredAreas = (manifest["declared_code_review_areas"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
    val areaKeys = areas.keys.map { it.toString() }.toSet()
    val metadata = manifest["area_metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val metadataKeys = metadata.keys.map { it.toString() }.toSet()
    val packLabel = (manifest["display_name"] ?: manifest["platform"]).toString()
    val focuses = metadata.mapNotNull { (rawArea, rawMetadata) ->
      val area = rawArea as? String ?: return@mapNotNull null
      val focus = (rawMetadata as? Map<*, *>)?.get("focus") as? String ?: return@mapNotNull null
      area to focus
    }
    return buildList {
      if (declaredContent != actualContent) {
        add(StructureViolation(manifestFile, "manifest declares every review content file"))
      }
      if (declaredAreas != areaKeys || areaKeys != metadataKeys) {
        add(StructureViolation(manifestFile, "manifest review area parity"))
      }
      if (!hasBespokeFocuses(focuses, metadata.size, packLabel)) {
        add(StructureViolation(manifestFile, "manifest bespoke area metadata"))
      }
    }
  }

  private fun hasBespokeFocuses(focuses: List<Pair<String, String>>, metadataSize: Int, packLabel: String): Boolean {
    if (focuses.size != metadataSize) return false
    if (focuses.map { it.second }.toSet().size != focuses.size) return false
    return focuses.all { (area, focus) ->
      focus.contains(packLabel, ignoreCase = true) &&
        !focus.equals("$packLabel ${defaultAreaFocus(area)}", ignoreCase = true)
    }
  }

  private fun manifestRoutingViolations(manifestFile: Path, manifest: Map<*, *>): List<StructureViolation> {
    val routing = manifest["routing_signals"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val strongSignals = (routing["strong"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val tieBreakers = (routing["tie_breakers"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    return buildList {
      strongSignals.filter { it.matches(Regex("\\*?\\.[A-Za-z0-9]+")) }.forEach { signal ->
        val counterpart = if (signal.startsWith("*.")) signal.removePrefix("*") else "*$signal"
        if (counterpart !in strongSignals) add(StructureViolation(manifestFile, "routing bare/glob pair"))
      }
      if (tieBreakers.none(::statesPositivePackDominance)) {
        add(StructureViolation(manifestFile, "routing positive pack dominance"))
      }
      if (tieBreakers.none(::statesAdjacentPackDisambiguation)) {
        add(StructureViolation(manifestFile, "routing adjacent-pack disambiguation"))
      }
      if (!excludesGeneratedAndVendoredFromDominance(tieBreakers)) {
        add(StructureViolation(manifestFile, "routing generated and vendored exclusion"))
      }
    }
  }

  private fun statesPositivePackDominance(rule: String): Boolean =
    containsAll(rule, "prefer", "dominat") && !rule.contains("do not prefer", ignoreCase = true)

  private fun statesAdjacentPackDisambiguation(rule: String): Boolean = containsAll(rule, "do not prefer", "adjacent")

  private fun excludesGeneratedAndVendoredFromDominance(rules: List<String>): Boolean =
    containsAll(rules.joinToString(" "), "exclude", "generated", "vendor", "dominan")

  private fun manifestPointerViolations(manifestFile: Path, manifest: Map<*, *>): List<StructureViolation> {
    val declaredFiles = manifest["declared_files"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val areas = declaredFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val pointers = manifest["pointers"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val expectedPointers = declaredContentFiles(declaredFiles, areas).map { it.removeSuffix("/content.md") }.toSet()
    val reviewPointers = pointers.keys.map { it.toString() }.filter { it.startsWith("code-review/") }.toSet()
    return if (reviewPointers == expectedPointers) {
      emptyList()
    } else {
      listOf(StructureViolation(manifestFile, "generated pointers for declared review skills"))
    }
  }

  private fun declaredContentFiles(declaredFiles: Map<*, *>, areas: Map<*, *>): Set<String> = buildSet {
    (declaredFiles["baseline"] as? String)?.let(::add)
    areas.values.filterIsInstance<String>().forEach(::add)
  }

  private fun contentViolations(file: Path): List<StructureViolation> {
    val sectionHeadings = headings(file)
    return if (file.parent.name.endsWith("code-review")) {
      baselineViolations(file, sectionHeadings)
    } else {
      specialistViolations(file, sectionHeadings)
    }
  }

  private fun baselineViolations(file: Path, headings: List<String>): List<StructureViolation> {
    val required = listOf("Classification Rules", "Diff-Signal Routing Table", "Mixed Diffs", "Finding Discipline")
    val content = Files.readString(file)
    val classification = h2Section(content, "Classification Rules")
    val routing = h2Section(content, "Diff-Signal Routing Table")
    val mixedDiffs = h2Section(content, "Mixed Diffs")
    val discipline = h2Section(content, "Finding Discipline")
    return buildList {
      if (headings != required) add(StructureViolation(file, "baseline H2 sequence"))
      if (!classification.contains("If ") || !classification.contains("Otherwise")) {
        add(StructureViolation(file, "classification decisions"))
      }
      val declaredAreas = declaredAreasForContent(file)
      if (declaredAreas.isEmpty() || declaredAreas.any { area ->
          !Regex("(?m)^- .+ -> `$area` specialist\\.$").containsMatchIn(routing)
        }
      ) {
        add(StructureViolation(file, "signal-to-specialist routing mappings"))
      }
      if (!containsAll(mixedDiffs, "keep", "whole review", "lightweight", "file-level classification")
      ) {
        add(StructureViolation(file, "mixed-diff retention"))
      }
      if (!containsAll(mixedDiffs, "specialist", "scope", "generated", "vendored", "non-stack")) {
        add(StructureViolation(file, "scoping exclusions"))
      }
      if (!containsAll(discipline, "severity", "precondition", "attributed", "deduplicat")) {
        add(StructureViolation(file, "finding discipline"))
      }
    }
  }

  private fun specialistViolations(file: Path, headings: List<String>): List<StructureViolation> {
    val required = listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules")
    val content = Files.readString(file)
    val projectRules = h2Section(content, "Project-Specific Rules")
    return buildList {
      if (headings != required && headings != required + "Repo-Local Knowledge") {
        add(StructureViolation(file, "specialist H2 sequence"))
      }
      if (!projectRules.contains("### ")) add(StructureViolation(file, "specialist H3 grouping"))
      if (!hasCanonicalSeverityCloser(file, content)) add(StructureViolation(file, "canonical severity closer"))
      if (!projectRules.contains('`') ||
        !Regex("(?i)\\b(must|do not|never|require|reject|verify|preserve)\\b").containsMatchIn(projectRules) ||
        !Regex("(?i)failure|error|invariant|boundary").containsMatchIn(projectRules)
      ) {
        add(StructureViolation(file, "enforceable stack failure modes"))
      }
      if (Regex("(?i)\\b(run|invoke|spawn)\\b[^\\n]*bill-[a-z0-9-]+-code-review-").containsMatchIn(content)) {
        add(StructureViolation(file, "sibling specialist invocation"))
      }
      if (file.parent.name.endsWith("-ui") && !containsAll(ignoreSection(content), "ux-accessibility", "security")) {
        add(StructureViolation(file, "UI lane deferrals"))
      }
      if (file.parent.name.endsWith("-ux-accessibility") && !containsAll(ignoreSection(content), "ui", "security")) {
        add(StructureViolation(file, "UX accessibility lane deferrals"))
      }
    }
  }

  private fun nativeAgentViolations(pack: Path): List<StructureViolation> {
    val manifest = Yaml().load<Any?>(Files.readString(pack.resolve("platform.yaml"))) as? Map<*, *>
      ?: emptyMap<Any?, Any?>()
    val baseline = ((manifest["declared_files"] as? Map<*, *>)?.get("baseline") as? String)
      ?: return emptyList()
    val agentsFile = pack.resolve(baseline).parent.resolve("native-agents/agents.yaml")
    if (!Files.isRegularFile(agentsFile)) {
      return listOf(StructureViolation(agentsFile, "native-agent source bundle"))
    }
    val agents = (Yaml().load<Any?>(Files.readString(agentsFile)) as? Map<*, *>)?.get("agents") as? List<*>
      ?: return listOf(StructureViolation(agentsFile, "native-agent descriptions"))
    val agentMaps = agents.filterIsInstance<Map<*, *>>()
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
      if (agentMaps.size != agents.size ||
        agentMaps.any { agent -> expectedDescriptions[agent["name"]] != agent["description"] }
      ) {
        add(StructureViolation(agentsFile, "native-agent description pattern"))
      }
      val areas = ((manifest["declared_files"] as? Map<*, *>)?.get("areas") as? Map<*, *>)?.keys.orEmpty()
      val expectedNames = areas.map { "bill-${pack.name}-code-review-$it" }.toSet()
      if (agentMaps.map { it["name"] }.toSet() != expectedNames) {
        add(StructureViolation(agentsFile, "native-agent specialist coverage"))
      }
    }
  }

  private fun qualityCheckViolations(pack: Path): List<StructureViolation> {
    val manifest = Yaml().load<Any?>(Files.readString(pack.resolve("platform.yaml"))) as Map<*, *>
    val declared = manifest["declared_quality_check_file"] as? String
      ?: return emptyList()
    val file = pack.resolve(declared)
    if (!Files.isRegularFile(file)) return listOf(StructureViolation(file, "declared quality-check source"))
    val content = Files.readString(file)
    val execution = h2Section(content, "Execution Steps")
    val fixStrategy = h2Section(content, "Fix Strategy")
    return buildList {
      if (headings(file) != listOf("Purpose", "Execution Steps", "Fix Strategy")) {
        add(StructureViolation(file, "quality-check H2 sequence"))
      }
      if (!containsAll(execution, "build file", "wrapper", "CI")) {
        add(StructureViolation(file, "quality-check command discovery"))
      }
      if (!orderedFragments(execution, "build file", "wrapper", "CI configuration", "before falling back")) {
        add(StructureViolation(file, "quality-check fallback ordering"))
      }
      if (!containsAll(execution, "files in scope")) {
        add(StructureViolation(file, "quality-check scoped files"))
      }
      if (!containsAll(execution, "pack's quality-check entrypoint")) {
        add(StructureViolation(file, "quality-check pack entrypoint"))
      }
      if (!containsAll(fixStrategy, "priority-ordered", "never suppress")) {
        add(StructureViolation(file, "quality-check fix discipline"))
      }
      if (!containsAll(fixStrategy, "re-run targeted checks")) {
        add(StructureViolation(file, "quality-check targeted rerun"))
      }
      if (!containsAll(fixStrategy, "full suite when targeted checks cannot establish safety")) {
        add(StructureViolation(file, "quality-check escalation"))
      }
    }
  }

  private fun assertContentRuleViolation(pack: Path, relativePath: String, content: String, rule: String) {
    writeConformingFixture(pack)
    Files.writeString(pack.resolve(relativePath), content)
    assertTrue(structureViolations(pack).any { it.rule == rule }, "Expected $rule")
  }

  private fun assertSpecialistRuleViolation(pack: Path, area: String, content: String, rule: String) {
    val file = pack.resolve("code-review/bill-fixture-code-review-$area/content.md")
    Files.createDirectories(file.parent)
    Files.writeString(file, content)
    assertTrue(specialistViolations(file, headings(file)).any { it.rule == rule }, "Expected $rule")
    Files.delete(file)
    Files.delete(file.parent)
  }

  private fun assertQualityCheckRuleViolation(pack: Path, content: String, rule: String) {
    writeConformingFixture(pack)
    Files.writeString(pack.resolve("quality-check/bill-fixture-code-check/content.md"), content)
    assertTrue(qualityCheckViolations(pack).any { it.rule == rule }, "Expected $rule")
  }

  private fun assertManifestRuleViolation(pack: Path, manifest: String, rule: String) {
    writeConformingFixture(pack)
    Files.writeString(pack.resolve("platform.yaml"), manifest)
    assertTrue(structureViolations(pack).any { it.rule == rule }, "Expected $rule")
  }

  private fun assertSidecarRuleViolation(pack: Path, fileName: String, sidecarContent: String, rule: String) {
    writeConformingFixture(pack)
    val specialist = pack.resolve("code-review/bill-fixture-code-review-security/content.md")
    Files.writeString(
      specialist,
      fixtureSpecialist + "\n\nThe $fileName rubric is required because an H2 section is insufficient.\n",
    )
    val sidecar = specialist.resolveSibling(fileName)
    Files.writeString(sidecar, sidecarContent)
    assertTrue(structureViolations(pack).any { it.rule == rule }, "Expected $rule")
    Files.delete(sidecar)
  }

  private fun containsAll(content: String, vararg fragments: String): Boolean =
    fragments.all { content.contains(it, ignoreCase = true) }

  private fun h2Section(content: String, heading: String): String =
    content.substringAfter("## $heading", "").substringBefore("\n## ")

  private fun orderedFragments(content: String, vararg fragments: String): Boolean {
    val normalized = content.lowercase()
    return fragments.fold(-1) { previousIndex, fragment ->
      if (previousIndex == Int.MIN_VALUE) return@fold Int.MIN_VALUE
      normalized.indexOf(fragment.lowercase(), previousIndex + 1).takeIf { it >= 0 } ?: Int.MIN_VALUE
    } != Int.MIN_VALUE
  }

  private fun hasCanonicalSeverityCloser(file: Path, content: String): Boolean {
    val rules = content.substringAfter("## Project-Specific Rules", "").substringBefore("\n## ")
    val finalRule = rules.lineSequence().map(String::trim).filter { it.startsWith("- ") }.lastOrNull()
    val area = file.parent.name.substringAfter("-code-review-", "")
    return area.isNotEmpty() && finalRule == canonicalSeverityCloser(area)
  }

  private fun declaredAreasForContent(file: Path): Set<String> {
    val manifestFile = file.parent.parent.parent.resolve("platform.yaml")
    val manifest = Yaml().load<Any?>(Files.readString(manifestFile)) as? Map<*, *> ?: return emptySet()
    return (manifest["declared_code_review_areas"] as? List<*>)
      ?.filterIsInstance<String>()
      ?.toSet()
      .orEmpty()
  }

  private fun ignoreSection(content: String): String =
    content.substringAfter("## Ignore", "").substringBefore("## Applicability")

  private fun authoredSidecarViolations(pack: Path): List<StructureViolation> = contentFiles(pack)
    .filter { !it.parent.name.endsWith("code-review") }
    .flatMap { contentFile ->
      Files.list(contentFile.parent).use { siblings ->
        val sidecars = siblings.filter {
          it.fileName.toString().endsWith(".md") && it != contentFile
        }.toList()
        if (sidecars.size > 1) return@use listOf(StructureViolation(contentFile, "one authored rubric sidecar"))
        val sidecar = sidecars.singleOrNull() ?: return@use emptyList()
        val sourceContent = Files.readString(contentFile)
        val sidecarContent = Files.readString(sidecar)
        buildList {
          if (sidecar.fileName.toString() in reservedGeneratedSidecarNames(pack)) {
            add(StructureViolation(sidecar, "reserved generated sidecar name"))
          }
          if (containsWrapperOrProviderOutput(sidecarContent)) {
            add(StructureViolation(sidecar, "wrapper or provider sidecar content"))
          }
          if (!isSpecialistRubric(sidecarContent)) {
            add(StructureViolation(sidecar, "specialist rubric sidecar content"))
          }
          if (!sourceContent.contains(sidecar.fileName.toString()) ||
            !Regex("(?i)insufficient").containsMatchIn(sourceContent)
          ) {
            add(StructureViolation(contentFile, "authored rubric sidecar rationale"))
          }
        }
      }
    }

  private fun reservedGeneratedSidecarNames(pack: Path): Set<String> {
    val manifest = Yaml().load<Any?>(Files.readString(pack.resolve("platform.yaml"))) as? Map<*, *>
    val pointers = manifest?.get("pointers") as? Map<*, *>
    val declaredNames = pointers.orEmpty().values.flatMap { entries ->
      (entries as? List<*>)?.filterIsInstance<Map<*, *>>()?.mapNotNull { it["name"] as? String }.orEmpty()
    }
    return generatedSidecarNames + declaredNames
  }

  private fun containsWrapperOrProviderOutput(content: String): Boolean = content.startsWith("---\n") || containsAll(
    content,
    "## Descriptor",
  ) || Regex("(?m)^(compose:|developer_instructions:|model:)").containsMatchIn(content)

  private fun isSpecialistRubric(content: String): Boolean {
    val title = content.lineSequence().firstOrNull { it.startsWith("# ") }.orEmpty()
    return Regex("(?i)^# .*(rubric|guidelines|checks|rules)").containsMatchIn(title) &&
      content.lineSequence().any { it.startsWith("## ") } &&
      Regex("(?i)\\b(must|never|verify|reject|flag|require)\\b").containsMatchIn(content)
  }

  private fun severityViolations(file: Path): List<StructureViolation> {
    val content = Files.readString(file)
    val ratings = severityRatings(content)
    return ratings.filter { it !in allowedSeverities }.map { rating ->
      StructureViolation(file, "off-enum severity rating $rating")
    }
  }

  private fun severityRatings(content: String): Set<String> = buildSet {
    Regex("(?m)\\bSeverity (?:ratings?|scale):\\s*([^\\n]+)").findAll(content).forEach { match ->
      Regex("\\b[A-Z][a-z]+\\b").findAll(match.groupValues[1]).mapTo(this) { it.value }
    }
    Regex("(?m)^- For ([A-Z][a-z]+)(?: or ([A-Z][a-z]+))?(?: [a-z-]+)? findings\\b")
      .findAll(content)
      .forEach { match -> match.groupValues.drop(1).filter(String::isNotEmpty).forEach(::add) }
    Regex("(?m)^- \\[F-[^]]+] ([A-Z][a-z]+) \\|").findAll(content).forEach { match ->
      add(match.groupValues[1])
    }
    val ratingContext = "(?:rate|rated|rating|severity|at most|at least|classify|classified as)"
    val ratingValue = "(Blocker|Major|Minor|Nit|Critical|Warning)"
    Regex("(?i)\\b$ratingContext\\b[^.\\n:|]{0,40}[:|]?\\s*$ratingValue\\b")
      .findAll(content)
      .forEach { match -> add(match.groupValues[1].replaceFirstChar(Char::uppercase)) }
  }

  private fun contentFiles(root: Path): List<Path> = Files.walk(root.resolve("code-review")).use { paths ->
    paths.filter { it.fileName.toString() == "content.md" }.toList()
  }

  private fun allContentFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { it.fileName.toString() == "content.md" }.toList()
  }

  private fun headings(file: Path): List<String> = Files.readAllLines(file)
    .filter { it.startsWith("## ") }
    .map { it.removePrefix("## ") }

  private fun writeConformingFixture(pack: Path) {
    Files.createDirectories(pack.resolve("code-review/bill-fixture-code-review-security"))
    Files.createDirectories(pack.resolve("code-review/bill-fixture-code-review/native-agents"))
    Files.createDirectories(pack.resolve("quality-check/bill-fixture-code-check"))
    Files.writeString(pack.resolve("platform.yaml"), fixtureManifest)
    Files.writeString(pack.resolve("code-review/bill-fixture-code-review-security/content.md"), fixtureSpecialist)
    Files.writeString(pack.resolve("code-review/bill-fixture-code-review/content.md"), fixtureBaseline)
    Files.writeString(pack.resolve("code-review/bill-fixture-code-review/native-agents/agents.yaml"), fixtureAgents)
    Files.writeString(pack.resolve("quality-check/bill-fixture-code-check/content.md"), fixtureQualityCheck)
  }

  internal data class StructureViolation(val path: Path, val rule: String) {
    override fun toString(): String = "$path: $rule"
  }

  private companion object {
    val allowedSeverities = setOf("Blocker", "Major", "Minor")
    val generatedSidecarNames = setOf(
      "review-orchestrator.md",
      "review-delegation.md",
      "review-scope.md",
      "shell-ceremony.md",
      "specialist-contract.md",
      "stack-routing.md",
      "telemetry-contract.md",
    )
    val fixtureManifest = """
      platform: fixture
      display_name: Fixture
      routing_signals:
        strong: [".fixture", "*.fixture"]
        tie_breakers:
          - "Prefer Fixture when Fixture source signals dominate the changed product surface."
          - "Do not prefer Fixture when an adjacent pack's declared signals dominate."
          - "Exclude generated and vendored files from dominance scoring."
      declared_code_review_areas: [security]
      declared_files:
        baseline: code-review/bill-fixture-code-review/content.md
        areas:
          security: code-review/bill-fixture-code-review-security/content.md
      area_metadata:
        security:
          focus: Fixture security boundaries for .fixture sources
      declared_quality_check_file: quality-check/bill-fixture-code-check/content.md
      pointers:
        code-review/bill-fixture-code-review: []
        code-review/bill-fixture-code-review-security: []
    """.trimIndent()
    val fixtureBaseline = """
      ## Classification Rules

      If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack.

      ## Diff-Signal Routing Table

      - Authentication or sensitive-data changes -> `security` specialist.

      ## Mixed Diffs

      Keep the baseline specialists for the whole review and use lightweight file-level classification.
      Exclude generated, vendored, and non-stack files from each specialist's scope.

      ## Finding Discipline

      Calibrate severity and verify each precondition. Keep findings attributed, then deduplicate overlaps.
    """.trimIndent()
    val fixtureSpecialist = """
      ## Focus

      Focus.

      ## Ignore

      Ignore.

      ## Applicability

      Applicable.

      ## Project-Specific Rules

      ### Failure Modes

      - Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.
      - For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
    """.trimIndent()
    val fixtureAgents = """
      agents:
        - name: bill-fixture-code-review-security
          description: "Fixture security specialist code reviewer. Runs against Fixture security boundaries for .fixture sources. Returns a Risk Register in the F-XXX bullet format."
    """.trimIndent()
    val fixtureQualityCheck = """
      ## Purpose

      Check fixture changes.

      ## Execution Steps

      Determine the files in scope. Discover commands from build files, wrappers, and CI configuration before falling back to defaults.
      Run the pack's quality-check entrypoint and capture failures.

      ## Fix Strategy

      Follow the priority-ordered fix ladder and never suppress failures. Re-run targeted checks after fixes.
      Escalate to the full suite when targeted checks cannot establish safety.
    """.trimIndent()

    val vagueClassificationBaseline = fixtureBaseline.replace(
      "If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack.",
      "Classify the project.",
    )
    val misplacedClassificationBaseline = vagueClassificationBaseline.replace(
      "Calibrate severity and verify each precondition.",
      "If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack. " +
        "Calibrate severity and verify each precondition.",
    )
    val vagueRoutingBaseline = fixtureBaseline.replace(
      "- Authentication or sensitive-data changes -> `security` specialist.",
      "Route code files to the relevant specialist.",
    )
    val mixedDiffDropsLanes = fixtureBaseline.replace(
      "Keep the baseline specialists for the whole review",
      "Replace the baseline specialists after classification",
    )
    val unscopedBaseline = fixtureBaseline.replace("generated, vendored, and non-stack", "irrelevant")
    val undisciplinedBaseline = fixtureBaseline.replace(
      "Calibrate severity and verify each precondition. Keep findings attributed, then deduplicate overlaps.",
      "Merge the findings.",
    )
    val vagueSpecialist = fixtureSpecialist.replace(
      "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
      "Consider API topics and general risks.",
    )
    val misplacedSpecialistRule = vagueSpecialist.replace(
      "Focus.",
      "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
    )
    val siblingInvokingSpecialist = fixtureSpecialist.replace(
      "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
      "Verify `FixtureApi` boundaries and invoke bill-fixture-code-review-testing for failures.",
    )
    val misplacedSeverityCloser = fixtureSpecialist + "\n- End with a noncanonical rule."
    val wrongAreaSeverityCloser = fixtureSpecialist.replace(
      canonicalSeverityCloser("security"),
      canonicalSeverityCloser("persistence"),
    )
    val shallowQualityCheck = fixtureQualityCheck.replace(
      "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
      "Run configured commands.",
    )
    val misplacedQualityCheckDiscovery = shallowQualityCheck.replace(
      "Check fixture changes.",
      "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
    )
    val defaultFirstQualityCheck = fixtureQualityCheck.replace(
      "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
      "Fall back to defaults before checking build files, wrappers, and CI configuration.",
    )
    val unscopedQualityCheck = fixtureQualityCheck.replace(
      "Determine the files in scope.",
      "Check the repository.",
    )
    val noEntrypointQualityCheck = fixtureQualityCheck.replace(
      "Run the pack's quality-check entrypoint and capture failures.",
      "Run arbitrary commands.",
    )
    val suppressingQualityCheck = fixtureQualityCheck.replace(
      "Follow the priority-ordered fix ladder and never suppress failures.",
      "Suppress failures when convenient.",
    )
    val noTargetedRerunQualityCheck = fixtureQualityCheck.replace(
      "Re-run targeted checks after fixes.",
      "Do not rerun checks.",
    )
    val unconditionalFullSuiteQualityCheck = fixtureQualityCheck.replace(
      "Escalate to the full suite when targeted checks cannot establish safety.",
      "Always run the full suite.",
    )
    val missingDeclaredAreaManifest = fixtureManifest.replace(
      "declared_code_review_areas: [security]",
      "declared_code_review_areas: []",
    )
    val genericAreaMetadataManifest = fixtureManifest.replace(
      "Fixture security boundaries for .fixture sources",
      "Fixture secrets handling, auth, and sensitive-data exposure",
    )
    val bareOnlyRoutingManifest = fixtureManifest.replace("[\".fixture\", \"*.fixture\"]", "[\".fixture\"]")
    val globOnlyRoutingManifest = fixtureManifest.replace("[\".fixture\", \"*.fixture\"]", "[\"*.fixture\"]")
    val noPositiveDominanceManifest = fixtureManifest.replace(
      "Prefer Fixture when Fixture source signals dominate the changed product surface.",
      "Select Fixture for Fixture files.",
    )
    val noAdjacentDisambiguationManifest = fixtureManifest.replace(
      "Do not prefer Fixture when an adjacent pack's declared signals dominate.",
      "Do not prefer Fixture for ambiguous files.",
    )
    val noGeneratedExclusionManifest = fixtureManifest.replace(
      "Exclude generated and vendored files from dominance scoring.",
      "Exclude vendored files from dominance scoring.",
    )
    val noVendoredExclusionManifest = fixtureManifest.replace(
      "Exclude generated and vendored files from dominance scoring.",
      "Exclude generated files from dominance scoring.",
    )
    val validSidecar = """
      # Security Review Rubric

      ## Boundary Rules

      Verify `FixtureApi` boundaries and reject invalid authorization states.
    """.trimIndent()
    val wrapperSidecar = """
      # Security Review Rubric

      ## Descriptor

      compose: governed-content
    """.trimIndent()
    val organizationSidecar = """
      # Team Notes

      ## Planning

      Remember release dates and ownership.
    """.trimIndent()
  }
}
