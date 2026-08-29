package skillbill.architecture

import skillbill.error.FailureWireCode
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FailureCodeTotalityArchitectureTest {
  @Test
  fun `in-scope failure wire codes stay total and injective`() {
    val violations = inScopeFailureWireCodeViolations()
    assertEquals(
      emptyList(),
      violations,
      "Each in-scope failure case must resolve to exactly one wire code and each wire code must belong to exactly one case.",
    )
  }

  @Test
  fun `failure wire code totality scanner fires on synthetic orphan and duplicate fixtures`() {
    val orphanViolation = wireCodeViolation(
      hierarchy = "SyntheticFailureCode",
      entryCount = 2,
      wireValues = listOf("alpha"),
    )
    assertNotNull(orphanViolation)
    assertTrue(
      orphanViolation!!.contains("orphaned wire value"),
      "Regression if adding a failure case without a unique wire code stops failing the architecture gate.",
    )

    val duplicateViolation = wireCodeViolation(
      hierarchy = "SyntheticFailureCode",
      entryCount = 2,
      wireValues = listOf("shared", "shared"),
    )
    assertNotNull(duplicateViolation)
    assertTrue(
      duplicateViolation!!.contains("duplicate wire value"),
      "Regression if an orphaned or duplicated failure wire code no longer fails totality enforcement.",
    )
  }

  private fun inScopeFailureWireCodeViolations(): List<String> =
    listOf(
      wireCodeEntries(FeatureTaskRuntimePhaseOutputFailureCode.entries.toList()),
      wireCodeEntries(DecompositionManifestValidationFailureCode.entries.toList()),
      wireCodeEntries(FeatureTaskRuntimePhaseOutputFailureKind.entries.toList()),
      wireCodeEntries(FeatureTaskRuntimeHandoffProjectionFailureKind.entries.toList()),
    ).flatten()

  private fun wireCodeEntries(entries: List<FailureWireCode>): List<String> {
    val wireValues = entries.map { it.wireValue }
    return wireCodeViolation(
      hierarchy = entries.firstOrNull()?.javaClass?.simpleName ?: "<unknown>",
      entryCount = entries.size,
      wireValues = wireValues,
    )?.let { violation -> listOf(violation) }.orEmpty()
  }

  private fun wireCodeViolation(hierarchy: String, entryCount: Int, wireValues: List<String>): String? {
    if (wireValues.toSet().size != wireValues.size) {
      return "$hierarchy has duplicate wire value"
    }
    if (entryCount != wireValues.size) {
      return "$hierarchy has orphaned wire value"
    }
    if (wireValues.any(String::isBlank)) {
      return "$hierarchy has blank wire value"
    }
    return null
  }
}
