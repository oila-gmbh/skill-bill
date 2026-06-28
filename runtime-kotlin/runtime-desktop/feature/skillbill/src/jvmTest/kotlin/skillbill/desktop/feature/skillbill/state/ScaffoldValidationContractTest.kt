package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.ScaffoldValidationId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ScaffoldValidationContractTest {

  @Test
  fun `every validation id maps to a resource whose placeholder count matches its arg count`() {
    val strings = loadDesignSystemStrings()
    ScaffoldValidationId.entries.forEach { id ->
      val key = resourceKeyFor(id)
      val template = strings[key] ?: fail("strings.xml is missing resource '$key' for $id")
      assertEquals(
        expectedArgCount(id),
        placeholderCount(template),
        "placeholder count of '$key' must match the args emitted for $id (template: \"$template\")",
      )
    }
  }

  // Independent restatement of the ScaffoldValidationId -> string-resource mapping owned by
  // scaffoldValidationText in ScaffoldWizardDialog. Kept exhaustive so a new id fails to compile
  // until both the UI mapping and this contract are updated together.
  private fun resourceKeyFor(id: ScaffoldValidationId): String = when (id) {
    ScaffoldValidationId.SKILL_NAME_REQUIRED -> "scaffold_error_skill_name_required"
    ScaffoldValidationId.PLATFORM_SLUG_REQUIRED -> "scaffold_error_platform_slug_required"
    ScaffoldValidationId.PLATFORM_REQUIRED -> "scaffold_error_platform_required"
    ScaffoldValidationId.FAMILY_REQUIRED -> "scaffold_error_family_required"
    ScaffoldValidationId.CODE_REVIEW_AREA_REQUIRED -> "scaffold_error_code_review_area_required"
    ScaffoldValidationId.ADD_ON_NAME_REQUIRED -> "scaffold_error_add_on_name_required"
    ScaffoldValidationId.OWNING_PLATFORM_PACK_REQUIRED -> "scaffold_error_owning_platform_pack_required"
    ScaffoldValidationId.BASELINE_PACK_REQUIRED -> "scaffold_error_baseline_pack_required"
    ScaffoldValidationId.BASELINE_PACK_UNAVAILABLE -> "scaffold_error_baseline_pack_unavailable"
    ScaffoldValidationId.BASELINE_SKILL_REQUIRED -> "scaffold_error_baseline_skill_required"
    ScaffoldValidationId.BASELINE_SKILL_UNAVAILABLE -> "scaffold_error_baseline_skill_unavailable"
    ScaffoldValidationId.BASELINE_MODE_UNSUPPORTED -> "scaffold_error_baseline_mode_unsupported"
    ScaffoldValidationId.BASELINE_SCOPE_UNSUPPORTED -> "scaffold_error_baseline_scope_unsupported"
    ScaffoldValidationId.DUPLICATE_BASELINE_LAYER -> "scaffold_error_duplicate_baseline_layer"
    ScaffoldValidationId.BASELINE_SELF_REFERENCE -> "scaffold_error_baseline_self_reference"
    ScaffoldValidationId.BASELINE_COMPOSITION_CYCLE -> "scaffold_error_baseline_composition_cycle"
  }

  // Independent restatement of the args contract enforced by validateScaffoldWizard.
  private fun expectedArgCount(id: ScaffoldValidationId): Int = when (id) {
    ScaffoldValidationId.SKILL_NAME_REQUIRED,
    ScaffoldValidationId.PLATFORM_SLUG_REQUIRED,
    ScaffoldValidationId.PLATFORM_REQUIRED,
    ScaffoldValidationId.FAMILY_REQUIRED,
    ScaffoldValidationId.CODE_REVIEW_AREA_REQUIRED,
    ScaffoldValidationId.ADD_ON_NAME_REQUIRED,
    ScaffoldValidationId.OWNING_PLATFORM_PACK_REQUIRED,
    -> 0
    ScaffoldValidationId.BASELINE_PACK_REQUIRED,
    ScaffoldValidationId.BASELINE_SKILL_REQUIRED,
    -> 1
    ScaffoldValidationId.BASELINE_PACK_UNAVAILABLE,
    ScaffoldValidationId.BASELINE_SELF_REFERENCE,
    -> 2
    ScaffoldValidationId.BASELINE_SKILL_UNAVAILABLE,
    ScaffoldValidationId.DUPLICATE_BASELINE_LAYER,
    ScaffoldValidationId.BASELINE_COMPOSITION_CYCLE,
    -> 3
    ScaffoldValidationId.BASELINE_MODE_UNSUPPORTED,
    ScaffoldValidationId.BASELINE_SCOPE_UNSUPPORTED,
    -> 4
  }

  private fun placeholderCount(template: String): Int =
    Regex("%(\\d+)\\${'$'}").findAll(template).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0

  private fun loadDesignSystemStrings(): Map<String, String> {
    val text = locateStringsXml().readText()
    return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
      .findAll(text)
      .associate { it.groupValues[1] to it.groupValues[2] }
  }

  private fun locateStringsXml(): File {
    val candidates = listOf(
      "runtime-kotlin/runtime-desktop/core/designsystem/src/commonMain/composeResources/values/strings.xml",
      "runtime-desktop/core/designsystem/src/commonMain/composeResources/values/strings.xml",
      "core/designsystem/src/commonMain/composeResources/values/strings.xml",
    )
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      candidates.forEach { rel ->
        val candidate = File(dir, rel)
        if (candidate.exists()) return candidate
      }
      dir = dir.parentFile
    }
    fail("Could not locate designsystem strings.xml from ${System.getProperty("user.dir")}")
  }
}
