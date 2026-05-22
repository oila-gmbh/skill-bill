package skillbill.scaffold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 3 A2: bijection between the canonical schema's
 * `x-runtime-anchored: true` top-level properties and the set of YAML
 * keys the Kotlin runtime consumes by name in
 * `ShellContentLoader.buildPack` (typed onto `PlatformManifest`).
 *
 * Modeled on `TelemetryEventInputSchemaParityTest`: two-direction
 * enforcement so neither side can silently drift.
 *
 * The Kotlin side is intentionally a curated constant rather than a
 * reflection sweep of `PlatformManifest` property names — the YAML keys
 * use snake_case while the Kotlin properties use camelCase, and the
 * mapping is not 1:1 (e.g. `routedSkillName` is a derived property, not
 * a manifest key). This bijection test pins the curated constant; a
 * schema author who marks a new field `x-runtime-anchored: true` without
 * threading it through `buildPack` fails the build here, and likewise a
 * Kotlin author who adds a new typed field without marking it on the
 * schema fails here too.
 */
class PlatformPackSchemaAnchoredBijectionTest {

  /**
   * Top-level YAML keys that `ShellContentLoader.buildPack` consumes by name. Source of
   * truth: walk `buildPack` and list every key passed to `manifest[...]` or
   * `requireField/parseDeclaredFiles/etc.` at the top level. Keep this list in lockstep
   * with the schema's `x-runtime-anchored: true` markers — the test below enforces both
   * directions.
   */
  private val expectedAnchoredFields: Set<String> = setOf(
    "platform",
    "contract_version",
    "display_name",
    "notes",
    "routing_signals",
    "declared_code_review_areas",
    "declared_files",
    "area_metadata",
    "declared_quality_check_file",
    "code_review_composition",
    "pointers",
    "addon_usage",
  )

  @Test
  fun `schema anchored set matches Kotlin runtime-consumed top-level fields`() {
    val schemaSide: Set<String> = anchoredTopLevelFieldNames()

    val missingFromSchema = expectedAnchoredFields - schemaSide
    val extraInSchema = schemaSide - expectedAnchoredFields

    assertTrue(
      missingFromSchema.isEmpty(),
      "SKILL-48 anchored bijection: Kotlin runtime consumes top-level fields that are NOT marked " +
        "`x-runtime-anchored: true` in the canonical schema: $missingFromSchema. " +
        "Add the marker in orchestration/contracts/platform-pack-schema.yaml.",
    )
    assertTrue(
      extraInSchema.isEmpty(),
      "SKILL-48 anchored bijection: schema marks top-level fields `x-runtime-anchored: true` that " +
        "the Kotlin runtime does NOT consume by name: $extraInSchema. " +
        "Either drop the marker (so the field flows through PlatformManifest.customFields) " +
        "or thread the field into ShellContentLoader.buildPack + PlatformManifest.",
    )
    // Belt-and-suspenders: if the asymmetric checks above both pass, set equality must hold.
    assertEquals(expectedAnchoredFields, schemaSide)
  }
}
