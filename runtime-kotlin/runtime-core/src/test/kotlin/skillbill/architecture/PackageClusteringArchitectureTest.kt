package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PackageClusteringArchitectureTest {
  @Test
  fun `clustered packages do not hold loose files bound to sibling area clusters`() {
    val violations = ArchitectureScanSupport.packageClusteringViolations(
      sourceRoots = PrincipleEnforcementInventory.packageClusteringSourceRoots,
      genericSegments = PrincipleEnforcementInventory.packageClusteringGenericSegments,
    )
    assertEquals(
      emptyList(),
      violations,
      "Packages with subpackages must not accumulate cross-area loose files like the former application.model bucket.",
    )
  }

  @Test
  fun `package clustering scanner fires on synthetic cross-area loose file fixture`() {
    val violation = ArchitectureScanSupport.packageClusteringViolationMessage(
      packageName = "skillbill.application",
      primaryName = "FeatureTaskRuntimeLeaky",
      areaChildren = setOf("featuretask", "goalrunner", "review", "model"),
      genericSegments = PrincipleEnforcementInventory.packageClusteringGenericSegments,
    )
    assertNotNull(
      violation,
      "Regression if a FeatureTask type can live loose under skillbill.application while featuretask is already a" +
        "child cluster.",
    )
    assertEquals(
      "skillbill.application/FeatureTaskRuntimeLeaky.kt belongs to cluster 'featuretask'; move it under " +
        "skillbill.application.featuretask.",
      violation,
    )
  }
}
