package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeRawMapArchitectureTest {

  @Test
  fun `runtime architecture forbids raw map shapes outside the open-boundary allowlist`() {
    val boundaryFiles = sourceFiles().filter { file ->
      file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
    }
    val violations = boundaryFiles.flatMap { file ->
      findRawMapViolations(file)
    }
    assertTrue(
      violations.isEmpty(),
      "Public application/domain/port declarations must not use raw Map<String, Any?> " +
        "shapes outside the open-boundary allow-list. Either annotate the declaration with " +
        "@OpenBoundaryMap or add it to RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST in " +
        "RuntimeArchitectureTestSupport.kt.\nViolations:\n" + violations.joinToString(separator = "\n"),
    )
  }


  @Test
  fun `open-boundary allow-list documents required exceptions`() {
    val architecture = Files.readString(runtimeArchitectureRoot.resolve("ARCHITECTURE.md"))
    val documentedEntries = parseArchitectureAllowList(architecture)
    assertTrue(
      documentedEntries.isNotEmpty(),
      "ARCHITECTURE.md must declare an Open-Boundary Allow-List section parseable by the architecture test.",
    )
    val allowListEntries = RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.toSet()
    val missingFromAllowlist = documentedEntries - allowListEntries
    val missingFromDoc = allowListEntries - documentedEntries
    assertTrue(
      missingFromAllowlist.isEmpty() && missingFromDoc.isEmpty(),
      "ARCHITECTURE.md and RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST must agree on the set of " +
        "open-boundary entries.\nMissing from constant: $missingFromAllowlist\n" +
        "Missing from doc: $missingFromDoc",
    )
    assertContains(architecture, "legacy raw-map")
    assertContains(architecture, "grandfathers")
  }


  @Test
  fun `every OpenBoundaryMap annotated declaration is documented in the architecture allow-list`() {
    val boundaryFiles = sourceFiles().filter { file ->
      file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
    }
    val annotated = boundaryFiles.flatMap(::findAnnotatedOpenBoundaryDeclarations)
    val documentedEntries = parseArchitectureAllowList(
      Files.readString(runtimeArchitectureRoot.resolve("ARCHITECTURE.md")),
    )
    val undocumented = annotated.filterNot { fqn -> fqn in documentedEntries }
    assertTrue(
      undocumented.isEmpty(),
      "Every @OpenBoundaryMap-annotated public declaration must appear by FQN in the " +
        "ARCHITECTURE.md Open-Boundary Allow-List section so the annotation cannot " +
        "act as a silent escape valve.\nUndocumented: $undocumented",
    )
  }


  @Test
  fun `SKILL-52_2 inventory classifies every public raw-map declaration exactly once`() {
    val architecture = Files.readString(runtimeArchitectureRoot.resolve("ARCHITECTURE.md"))
    val inventory = parseSkill522Inventory(architecture)
    assertTrue(
      inventory.entries.isNotEmpty(),
      "ARCHITECTURE.md must declare a SKILL-52.2 inventory section parseable by the architecture test.",
    )
    assertInventoryCategoriesKnown(inventory)
    assertInventoryMatchesAllowList(inventory)
    assertInventoryHasNoDuplicateFqns(inventory)
    assertAnnotatedDeclarationsAreOpenExtension(inventory)
    assertSubtaskIdsPresentForGatedCategories(inventory)
  }


  @Test
  fun `SKILL-52_2 inventory parser fires on synthetic fixture`() {
    val fixture =
      """
      <!-- skill-52-2-inventory:start -->

      ### must_type_now

      - `skillbill.fake.MustTypeOne` [subtask 3] — rationale.
      - `skillbill.fake.MustTypeTwo`
        [subtask 5] — wrapped-line rationale.

      ### open_extension (@OpenBoundaryMap)

      - `skillbill.fake.OpenExtensionOne`
      - `skillbill.fake.OpenExtensionTwo`

      ### private_serializer

      _None — placeholder._

      ### postponed_with_reason

      - `skillbill.fake.PostponedOne` [subtask 4] — reason.

      <!-- skill-52-2-inventory:end -->
      """.trimIndent()
    val parsed = parseSkill522Inventory(fixture)
    assertEquals(
      setOf(
        "skillbill.fake.MustTypeOne" to "must_type_now",
        "skillbill.fake.MustTypeTwo" to "must_type_now",
        "skillbill.fake.OpenExtensionOne" to "open_extension",
        "skillbill.fake.OpenExtensionTwo" to "open_extension",
        "skillbill.fake.PostponedOne" to "postponed_with_reason",
      ),
      parsed.entries.map { it.fqn to it.category }.toSet(),
    )
    val subtaskById = parsed.entries.associate { it.fqn to it.subtaskId }
    assertEquals(3, subtaskById["skillbill.fake.MustTypeOne"])
    assertEquals(5, subtaskById["skillbill.fake.MustTypeTwo"])
    assertEquals(4, subtaskById["skillbill.fake.PostponedOne"])
    assertEquals(null, subtaskById["skillbill.fake.OpenExtensionOne"])
  }


  @Test
  fun `raw map violation scanner fires on known violation fixtures`() {
    val fixture = SourceFile(
      relativePath = "test-fixture/Fake.kt",
      packageName = "skillbill.application",
      imports = emptyList(),
      source = rawMapViolationFixtureSource(),
    )
    val violations = findRawMapViolations(fixture)
    val violatingNames = violations.map { it.substringAfter("public `").substringBefore('`') }
    assertEquals(
      expectedRawMapViolationFixtureNames(),
      violatingNames.sorted(),
    )
  }

}
