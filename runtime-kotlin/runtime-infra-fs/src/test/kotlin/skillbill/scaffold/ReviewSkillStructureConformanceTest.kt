package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
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
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      vagueBaseline,
      "classification decisions",
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
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review-security/content.md",
      vagueSpecialist,
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
    assertSpecialistRuleViolation(pack, "ui", fixtureSpecialist, "UI lane deferrals")
    assertSpecialistRuleViolation(pack, "ux-accessibility", fixtureSpecialist, "UX accessibility lane deferrals")
    assertQualityCheckRuleViolation(pack, shallowQualityCheck, "quality-check command discovery")
    assertQualityCheckRuleViolation(pack, defaultFirstQualityCheck, "quality-check fallback ordering")
    assertQualityCheckRuleViolation(pack, unscopedQualityCheck, "quality-check scoped files")
    assertQualityCheckRuleViolation(pack, noEntrypointQualityCheck, "quality-check pack entrypoint")
    assertQualityCheckRuleViolation(pack, suppressingQualityCheck, "quality-check fix discipline")
    assertQualityCheckRuleViolation(pack, noTargetedRerunQualityCheck, "quality-check targeted rerun")
    assertQualityCheckRuleViolation(pack, unconditionalFullSuiteQualityCheck, "quality-check escalation")
    assertManifestAndSidecarNegativeFixtures(pack)
  }

  private fun assertManifestAndSidecarNegativeFixtures(pack: Path) {
    assertManifestRuleViolation(pack, missingDeclaredAreaManifest, "manifest review area parity")
    assertManifestRuleViolation(pack, genericAreaMetadataManifest, "manifest bespoke area metadata")
    assertManifestRuleViolation(pack, bareOnlyRoutingManifest, "routing bare/glob pair")
    assertManifestRuleViolation(pack, globOnlyRoutingManifest, "routing bare/glob pair")
    assertSidecarRuleViolation(pack, "shell-ceremony.md", validSidecar, "reserved generated sidecar name")
    assertSidecarRuleViolation(pack, "review-rubric.md", wrapperSidecar, "wrapper or provider sidecar content")
    assertSidecarRuleViolation(pack, "team-notes.md", organizationSidecar, "specialist rubric sidecar content")

    writeConformingFixture(pack)
    Files.delete(pack.resolve("code-review/bill-fixture-code-review/native-agents/agents.yaml"))
    assertEquals(emptyList(), structureViolations(pack), structureViolations(pack).joinToString("\n"))
  }

  internal fun structureViolations(pack: Path): List<StructureViolation> {
    val exemptions = setOf("go", "ios", "kmp", "kotlin", "php", "python")
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
    val focuses = metadata.values.mapNotNull { (it as? Map<*, *>)?.get("focus") as? String }
    return buildList {
      if (declaredContent != actualContent) {
        add(StructureViolation(manifestFile, "manifest declares every review content file"))
      }
      if (declaredAreas != areaKeys || areaKeys != metadataKeys) {
        add(StructureViolation(manifestFile, "manifest review area parity"))
      }
      if (focuses.size != metadata.size ||
        focuses.toSet().size != focuses.size ||
        focuses.any { !it.contains(packLabel, ignoreCase = true) }
      ) {
        add(StructureViolation(manifestFile, "manifest bespoke area metadata"))
      }
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
      if (tieBreakers.none { it.contains("Prefer") } || tieBreakers.none { it.contains("Do not prefer") } ||
        tieBreakers.none { it.contains("Exclude") }
      ) {
        add(StructureViolation(manifestFile, "routing tie-breaker conventions"))
      }
    }
  }

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
    return buildList {
      if (headings != required) add(StructureViolation(file, "baseline H2 sequence"))
      if (!content.contains("If ") || !content.contains("Otherwise")) {
        add(StructureViolation(file, "classification decisions"))
      }
      if (!Regex("(?is)Mixed Diffs.*keep.*whole review").containsMatchIn(content) ||
        !containsAll(content, "lightweight", "file-level classification")
      ) {
        add(StructureViolation(file, "mixed-diff retention"))
      }
      if (!containsAll(content, "specialist", "scope", "generated", "vendored", "non-stack")) {
        add(StructureViolation(file, "scoping exclusions"))
      }
      if (!containsAll(content, "severity", "precondition", "attributed", "deduplicat")) {
        add(StructureViolation(file, "finding discipline"))
      }
    }
  }

  private fun specialistViolations(file: Path, headings: List<String>): List<StructureViolation> {
    val required = listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules")
    val content = Files.readString(file)
    return buildList {
      if (headings != required && headings != required + "Repo-Local Knowledge") {
        add(StructureViolation(file, "specialist H2 sequence"))
      }
      if (!content.contains("### ")) add(StructureViolation(file, "specialist H3 grouping"))
      if (!hasCanonicalSeverityCloser(content)) add(StructureViolation(file, "canonical severity closer"))
      if (!content.contains('`') ||
        !Regex("(?i)\\b(must|do not|never|require|reject|verify|preserve)\\b").containsMatchIn(content) ||
        !Regex("(?i)failure|error|invariant|boundary").containsMatchIn(content)
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
    val agentsFile = pack.resolve("code-review").let { root ->
      Files.walk(root).use { paths ->
        paths.filter { it.endsWith("native-agents/agents.yaml") }.findFirst().orElse(null)
      }
    } ?: return emptyList()
    val agents = (Yaml().load<Any?>(Files.readString(agentsFile)) as? Map<*, *>)?.get("agents") as? List<*>
      ?: return listOf(StructureViolation(agentsFile, "native-agent descriptions"))
    val pattern = Regex(
      ".+ [a-z-]+ specialist code reviewer\\. Runs against .+\\. " +
        "Returns a Risk Register in the F-XXX bullet format\\.",
    )
    val agentMaps = agents.filterIsInstance<Map<*, *>>()
    return buildList {
      if (agentMaps.size != agents.size ||
        agentMaps.any { it["description"] !is String || !pattern.matches(it["description"] as String) }
      ) {
        add(StructureViolation(agentsFile, "native-agent description pattern"))
      }
      val manifest = Yaml().load<Any?>(Files.readString(pack.resolve("platform.yaml"))) as? Map<*, *>
        ?: emptyMap<Any?, Any?>()
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
    return buildList {
      if (headings(file) != listOf("Purpose", "Execution Steps", "Fix Strategy")) {
        add(StructureViolation(file, "quality-check H2 sequence"))
      }
      if (!containsAll(content, "build file", "wrapper", "CI")) {
        add(StructureViolation(file, "quality-check command discovery"))
      }
      if (!orderedFragments(content, "build file", "wrapper", "CI configuration", "before falling back")) {
        add(StructureViolation(file, "quality-check fallback ordering"))
      }
      if (!containsAll(content, "files in scope")) {
        add(StructureViolation(file, "quality-check scoped files"))
      }
      if (!containsAll(content, "pack's quality-check entrypoint")) {
        add(StructureViolation(file, "quality-check pack entrypoint"))
      }
      if (!containsAll(content, "priority-ordered", "never suppress")) {
        add(StructureViolation(file, "quality-check fix discipline"))
      }
      if (!containsAll(content, "re-run targeted checks")) {
        add(StructureViolation(file, "quality-check targeted rerun"))
      }
      if (!containsAll(content, "full suite when targeted checks cannot establish safety")) {
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

  private fun orderedFragments(content: String, vararg fragments: String): Boolean {
    val normalized = content.lowercase()
    return fragments.fold(-1) { previousIndex, fragment ->
      if (previousIndex == Int.MIN_VALUE) return@fold Int.MIN_VALUE
      normalized.indexOf(fragment.lowercase(), previousIndex + 1).takeIf { it >= 0 } ?: Int.MIN_VALUE
    } != Int.MIN_VALUE
  }

  private fun hasCanonicalSeverityCloser(content: String): Boolean {
    val rules = content.substringAfter("## Project-Specific Rules", "").substringBefore("\n## ")
    val finalRule = rules.lineSequence().map(String::trim).filter { it.startsWith("- ") }.lastOrNull()
    return finalRule?.matches(Regex("^- For Blocker or Major findings, describe the concrete .+\\.$")) == true
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
    return buildList {
      if (Regex("(?i)\\bnit\\b").containsMatchIn(content)) {
        add(StructureViolation(file, "forbidden severity Nit"))
      }
      if (Regex("Critical|Major or Critical|Critical or Major|Major or Blocker").containsMatchIn(content)) {
        add(StructureViolation(file, "off-enum severity vocabulary"))
      }
    }
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
        tie_breakers: ["Prefer fixture", "Do not prefer adjacent packs", "Exclude generated files"]
      declared_code_review_areas: [security]
      declared_files:
        baseline: code-review/bill-fixture-code-review/content.md
        areas:
          security: code-review/bill-fixture-code-review-security/content.md
      area_metadata:
        security:
          focus: Fixture security boundaries
      declared_quality_check_file: quality-check/bill-fixture-code-check/content.md
      pointers:
        code-review/bill-fixture-code-review: []
        code-review/bill-fixture-code-review-security: []
    """.trimIndent()
    val fixtureBaseline = """
      ## Classification Rules

      If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack.

      ## Diff-Signal Routing Table

      Route code files to the relevant specialist.

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
      - For Blocker or Major findings, describe the concrete consequence explicitly.
    """.trimIndent()
    val fixtureAgents = """
      agents:
        - name: bill-fixture-code-review-security
          description: "Fixture security specialist code reviewer. Runs against security lanes. Returns a Risk Register in the F-XXX bullet format."
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

    val vagueBaseline = fixtureBaseline.replace(
      "If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack.",
      "Classify the project.",
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
    val siblingInvokingSpecialist = fixtureSpecialist.replace(
      "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
      "Verify `FixtureApi` boundaries and invoke bill-fixture-code-review-testing for failures.",
    )
    val misplacedSeverityCloser = fixtureSpecialist + "\n- End with a noncanonical rule."
    val shallowQualityCheck = fixtureQualityCheck.replace(
      "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
      "Run configured commands.",
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
      "Fixture security boundaries",
      "Generic security boundaries",
    )
    val bareOnlyRoutingManifest = fixtureManifest.replace("[\".fixture\", \"*.fixture\"]", "[\".fixture\"]")
    val globOnlyRoutingManifest = fixtureManifest.replace("[\".fixture\", \"*.fixture\"]", "[\"*.fixture\"]")
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
