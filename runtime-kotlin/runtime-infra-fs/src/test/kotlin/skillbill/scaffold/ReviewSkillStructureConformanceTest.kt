package skillbill.scaffold

import skillbill.scaffold.rendering.canonicalSeverityCloser
import skillbill.scaffold.validation.ReviewSkillStructureValidator
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
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
  fun `all packs satisfy the complete review skill structure standard`() {
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
      pack.resolve("code-review/bill-fixture-code-review/content.md"),
      fixtureBaseline.replace("internal-for: bill-code-review", "internal-for: bill-code-check"),
    )
    assertTrue(structureViolations(pack).any { it.rule == "code-review internal parent" })

    writeConformingFixture(pack)
    Files.writeString(
      pack.resolve("quality-check/bill-fixture-code-check/content.md"),
      fixtureQualityCheck.replace("internal-for: bill-code-check", "internal-for: bill-code-review"),
    )
    assertTrue(structureViolations(pack).any { it.rule == "quality-check internal parent" })

    writeConformingFixture(pack)
    Files.writeString(
      pack.resolve("code-review/bill-fixture-code-review/native-agents/agents.yaml"),
      "agents:\n  - name: bill-fixture-code-review-security\n    description: wrong\n    compose: governed-content\n",
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
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      nondeterministicWaveBatching,
      "deterministic wave batching",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      droppedSelectedResults,
      "selected specialist result retention",
    )
    assertFindingMergeNegativeFixtures(pack)
  }

  private fun assertFindingMergeNegativeFixtures(pack: Path) {
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      unattributedFindingMerge,
      "attributed finding merge",
    )
    assertContentRuleViolation(
      pack,
      "code-review/bill-fixture-code-review/content.md",
      lossyFindingDeduplication,
      "evidence-preserving deduplication",
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
    assertManifestRuleAccepted(pack, labelIndependentAreaMetadataManifest, "manifest bespoke area metadata")
    assertManifestRuleAccepted(pack, dominantStackRoutingManifest, "routing adjacent-pack disambiguation")
    assertManifestRuleViolation(pack, missingDeclaredAreaManifest, "manifest review area parity")
    assertManifestRuleViolation(pack, genericAreaMetadataManifest, "manifest bespoke area metadata")
    assertManifestRuleViolation(pack, vagueAreaMetadataManifest, "manifest bespoke area metadata")
    assertManifestRuleViolation(pack, bareOnlyRoutingManifest, "routing bare/glob pair")
    assertManifestRuleViolation(pack, globOnlyRoutingManifest, "routing bare/glob pair")
    assertManifestRuleViolation(pack, noPositiveDominanceManifest, "routing positive pack dominance")
    assertManifestRuleViolation(pack, noAdjacentDisambiguationManifest, "routing adjacent-pack disambiguation")
    assertManifestRuleViolation(pack, invertedDominanceRoutingManifest, "routing adjacent-pack disambiguation")
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
    return ReviewSkillStructureValidator.violations(pack).map { violation ->
      StructureViolation(violation.path, violation.rule)
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
    assertTrue(structureViolations(pack).any { it.rule == rule }, "Expected $rule")
    Files.delete(file)
    Files.delete(file.parent)
  }

  private fun assertQualityCheckRuleViolation(pack: Path, content: String, rule: String) {
    writeConformingFixture(pack)
    Files.writeString(pack.resolve("quality-check/bill-fixture-code-check/content.md"), content)
    assertTrue(structureViolations(pack).any { it.rule == rule }, "Expected $rule")
  }

  private fun assertManifestRuleViolation(pack: Path, manifest: String, rule: String) {
    writeConformingFixture(pack)
    Files.writeString(pack.resolve("platform.yaml"), manifest)
    assertTrue(structureViolations(pack).any { it.rule == rule }, "Expected $rule")
  }

  private fun assertManifestRuleAccepted(pack: Path, manifest: String, rule: String) {
    writeConformingFixture(pack)
    Files.writeString(pack.resolve("platform.yaml"), manifest)
    assertTrue(structureViolations(pack).none { it.rule == rule }, "Unexpected $rule")
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

  internal data class StructureViolation(val path: Path, val rule: String) {
    override fun toString(): String = "$path: $rule"
  }
}

private fun allContentFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
  paths.filter { it.fileName.toString() == "content.md" }.toList()
}

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

private val allowedSeverities = setOf("Blocker", "Major", "Minor")
private val fixtureManifest = """
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
private val fixtureBaseline = """
      ---
      name: bill-fixture-code-review
      description: Fixture baseline review.
      internal-for: bill-code-review
      ---

      ## Classification Rules

      If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack.

      ## Diff-Signal Routing Table

      - Authentication or sensitive-data changes -> `security` specialist.

      ## Mixed Diffs

      Keep the baseline specialists for the whole review and use lightweight file-level classification.
      Exclude generated, vendored, and non-stack files from each specialist's scope.
      When selected specialists exceed worker capacity, run them in deterministic waves and retain every selected specialist result.

      ## Finding Discipline

      Calibrate severity and verify each precondition. Keep findings attributed through merge.
      Deduplicate overlaps without losing evidence.
""".trimIndent()
private val fixtureSpecialist = """
      ---
      name: bill-fixture-code-review-security
      description: Fixture security review.
      internal-for: bill-code-review
      ---

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
private val fixtureAgents = """
      agents:
        - name: bill-fixture-code-review-security
          description: "Fixture security specialist code reviewer. Runs against Fixture security boundaries for .fixture sources. Returns a Risk Register in the F-XXX bullet format."
          compose: governed-content
""".trimIndent()
private val fixtureQualityCheck = """
      ---
      name: bill-fixture-code-check
      description: Fixture quality check.
      internal-for: bill-code-check
      ---

      ## Purpose

      Check fixture changes.

      ## Execution Steps

      Determine the files in scope. Discover commands from build files, wrappers, and CI configuration before falling back to defaults.
      Run the pack's quality-check entrypoint and capture failures.

      ## Fix Strategy

      Follow the priority-ordered fix ladder and never suppress failures. Re-run targeted checks after fixes.
      Escalate to the full suite when targeted checks cannot establish safety.
""".trimIndent()

private val vagueClassificationBaseline = fixtureBaseline.replace(
  "If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack.",
  "Classify the project.",
)
private val misplacedClassificationBaseline = vagueClassificationBaseline.replace(
  "Calibrate severity and verify each precondition.",
  "If fixture source dominates, select the fixture pack. Otherwise select the adjacent pack. " +
    "Calibrate severity and verify each precondition.",
)
private val vagueRoutingBaseline = fixtureBaseline.replace(
  "- Authentication or sensitive-data changes -> `security` specialist.",
  "Route code files to the relevant specialist.",
)
private val mixedDiffDropsLanes = fixtureBaseline.replace(
  "Keep the baseline specialists for the whole review",
  "Replace the baseline specialists after classification",
)
private val unscopedBaseline = fixtureBaseline.replace("generated, vendored, and non-stack", "irrelevant")
private val undisciplinedBaseline = fixtureBaseline.replace(
  "Calibrate severity and verify each precondition.",
  "Merge the findings.",
)
private val nondeterministicWaveBatching = fixtureBaseline.replace(
  "run them in deterministic waves",
  "run them as capacity allows",
)
private val droppedSelectedResults = fixtureBaseline.replace(
  "retain every selected specialist result",
  "keep completed results",
)
private val unattributedFindingMerge = fixtureBaseline.replace(
  "Keep findings attributed through merge.",
  "Merge findings.",
)
private val lossyFindingDeduplication = fixtureBaseline.replace(
  "Deduplicate overlaps without losing evidence.",
  "Deduplicate overlaps.",
)
private val vagueSpecialist = fixtureSpecialist.replace(
  "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
  "Consider API topics and general risks.",
)
private val misplacedSpecialistRule = vagueSpecialist.replace(
  "Focus.",
  "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
)
private val siblingInvokingSpecialist = fixtureSpecialist.replace(
  "Verify `FixtureApi` boundaries and reject failure paths that violate its invariant.",
  "Verify `FixtureApi` boundaries and invoke bill-fixture-code-review-testing for failures.",
)
private val misplacedSeverityCloser = fixtureSpecialist + "\n- End with a noncanonical rule."
private val wrongAreaSeverityCloser = fixtureSpecialist.replace(
  canonicalSeverityCloser("security"),
  canonicalSeverityCloser("persistence"),
)
private val shallowQualityCheck = fixtureQualityCheck.replace(
  "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
  "Run configured commands.",
)
private val misplacedQualityCheckDiscovery = shallowQualityCheck.replace(
  "Check fixture changes.",
  "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
)
private val defaultFirstQualityCheck = fixtureQualityCheck.replace(
  "Discover commands from build files, wrappers, and CI configuration before falling back to defaults.",
  "Fall back to defaults before checking build files, wrappers, and CI configuration.",
)
private val unscopedQualityCheck = fixtureQualityCheck.replace(
  "Determine the files in scope.",
  "Check the repository.",
)
private val noEntrypointQualityCheck = fixtureQualityCheck.replace(
  "Run the pack's quality-check entrypoint and capture failures.",
  "Run arbitrary commands.",
)
private val suppressingQualityCheck = fixtureQualityCheck.replace(
  "Follow the priority-ordered fix ladder and never suppress failures.",
  "Suppress failures when convenient.",
)
private val noTargetedRerunQualityCheck = fixtureQualityCheck.replace(
  "Re-run targeted checks after fixes.",
  "Do not rerun checks.",
)
private val unconditionalFullSuiteQualityCheck = fixtureQualityCheck.replace(
  "Escalate to the full suite when targeted checks cannot establish safety.",
  "Always run the full suite.",
)
private val missingDeclaredAreaManifest = fixtureManifest.replace(
  "declared_code_review_areas: [security]",
  "declared_code_review_areas: []",
)
private val genericAreaMetadataManifest = fixtureManifest.replace(
  "Fixture security boundaries for .fixture sources",
  "Fixture secrets handling, auth, and sensitive-data exposure",
)
private val labelIndependentAreaMetadataManifest = fixtureManifest.replace(
  "Fixture security boundaries for .fixture sources",
  "Request signatures and capability token expiry for .fixture sources",
)
private val vagueAreaMetadataManifest = fixtureManifest.replace(
  "Fixture security boundaries for .fixture sources",
  "Fixture custom security review focus",
)
private val bareOnlyRoutingManifest = fixtureManifest.replace("[\".fixture\", \"*.fixture\"]", "[\".fixture\"]")
private val globOnlyRoutingManifest = fixtureManifest.replace("[\".fixture\", \"*.fixture\"]", "[\"*.fixture\"]")
private val noPositiveDominanceManifest = fixtureManifest.replace(
  "Prefer Fixture when Fixture source signals dominate the changed product surface.",
  "Select Fixture for Fixture files.",
)
private val noAdjacentDisambiguationManifest = fixtureManifest.replace(
  "Do not prefer Fixture when an adjacent pack's declared signals dominate.",
  "Do not prefer Fixture for ambiguous files.",
)
private val dominantStackRoutingManifest = fixtureManifest.replace(
  "Do not prefer Fixture when an adjacent pack's declared signals dominate.",
  "Do not prefer Fixture when it appears only as tooling around another dominant stack.",
)
private val invertedDominanceRoutingManifest = fixtureManifest.replace(
  "Do not prefer Fixture when an adjacent pack's declared signals dominate.",
  "Do not prefer another dominant stack when Fixture signals are present.",
)
private val noGeneratedExclusionManifest = fixtureManifest.replace(
  "Exclude generated and vendored files from dominance scoring.",
  "Exclude vendored files from dominance scoring.",
)
private val noVendoredExclusionManifest = fixtureManifest.replace(
  "Exclude generated and vendored files from dominance scoring.",
  "Exclude generated files from dominance scoring.",
)
private val validSidecar = """
      # Security Review Rubric

      ## Boundary Rules

      Verify `FixtureApi` boundaries and reject invalid authorization states.
""".trimIndent()
private val wrapperSidecar = """
      # Security Review Rubric

      ## Descriptor

      compose: governed-content
""".trimIndent()
private val organizationSidecar = """
      # Team Notes

      ## Planning

      Remember release dates and ownership.
""".trimIndent()
