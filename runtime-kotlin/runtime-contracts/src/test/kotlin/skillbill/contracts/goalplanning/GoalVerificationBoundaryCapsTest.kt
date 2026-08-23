package skillbill.contracts.goalplanning

import kotlin.test.Test
import kotlin.test.assertEquals

class GoalVerificationBoundaryCapsTest {
  @Test
  fun `effective verification caps load from the packaged contract document`() {
    val document = GoalVerificationBoundaryCaps::class.java.classLoader
      .getResourceAsStream(GoalVerificationBoundaryCaps.RESOURCE_PATH)
      ?.use { stream -> stream.readBytes().decodeToString() }
      ?: error("goal verification boundary caps contract is missing from the classpath")
    val parsed = GoalVerificationBoundaryCaps.parse(document)
    assertEquals(parsed.maxDiscoveryFileCount, GoalVerificationBoundaryCaps.maxDiscoveryFileCount)
    assertEquals(parsed.maxHeadingsPerFile, GoalVerificationBoundaryCaps.maxHeadingsPerFile)
    assertEquals(parsed.maxCatalogHeadings, GoalVerificationBoundaryCaps.maxCatalogHeadings)
    assertEquals(parsed.historyRecencyDays, GoalVerificationBoundaryCaps.historyRecencyDays)
    assertEquals(parsed.maxSelectedBodies, GoalVerificationBoundaryCaps.maxSelectedBodies)
    assertEquals(parsed.maxBodyBytes, GoalVerificationBoundaryCaps.maxBodyBytes)
    assertEquals(parsed.maxTotalBodyBytes, GoalVerificationBoundaryCaps.maxTotalBodyBytes)
    assertEquals(parsed.maxBoundaryFileBytes, GoalVerificationBoundaryCaps.maxBoundaryFileBytes)
  }
}
