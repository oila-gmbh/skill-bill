package skillbill.goalplanning

import skillbill.contracts.goalplanning.GoalVerificationBoundaryCaps
import skillbill.error.GoalVerificationBoundaryCapExceededError
import skillbill.ports.goalrunner.model.GoalPlanningContext
import skillbill.ports.goalrunner.model.GoalVerificationContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalVerificationBoundaryCapsParityTest {
  @Test
  fun `verification caps are contract-backed and stay tighter than planning except shared file bytes`() {
    assertEquals(GoalVerificationBoundaryCaps.maxDiscoveryFileCount, GoalVerificationContext.MAX_DISCOVERY_FILE_COUNT)
    assertEquals(GoalVerificationBoundaryCaps.maxHeadingsPerFile, GoalVerificationContext.MAX_HEADINGS_PER_FILE)
    assertEquals(GoalVerificationBoundaryCaps.maxCatalogHeadings, GoalVerificationContext.MAX_CATALOG_HEADINGS)
    assertEquals(GoalVerificationBoundaryCaps.maxSelectedBodies, GoalVerificationContext.MAX_SELECTED_BODIES)
    assertEquals(GoalVerificationBoundaryCaps.maxBodyBytes, GoalVerificationContext.MAX_BODY_BYTES)
    assertEquals(GoalVerificationBoundaryCaps.maxTotalBodyBytes, GoalVerificationContext.MAX_TOTAL_BODY_BYTES)
    assertEquals(GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES, GoalVerificationContext.MAX_BOUNDARY_FILE_BYTES)
    assertEquals(GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES, GoalVerificationBoundaryCaps.maxBoundaryFileBytes)
    assertTrue(GoalVerificationBoundaryCaps.maxDiscoveryFileCount < GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT)
    assertTrue(GoalVerificationBoundaryCaps.maxHeadingsPerFile < GoalPlanningContext.MAX_HEADINGS_PER_FILE)
    assertTrue(GoalVerificationBoundaryCaps.maxCatalogHeadings < GoalPlanningContext.MAX_CATALOG_HEADINGS)
    assertTrue(GoalVerificationBoundaryCaps.maxSelectedBodies < GoalPlanningContext.MAX_SELECTED_BODIES)
    assertTrue(GoalVerificationBoundaryCaps.maxBodyBytes < GoalPlanningContext.MAX_BODY_BYTES)
    assertTrue(GoalVerificationBoundaryCaps.maxTotalBodyBytes < GoalPlanningContext.MAX_TOTAL_BODY_BYTES)
    assertEquals(GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT, 32)
    assertEquals(GoalPlanningContext.MAX_CATALOG_HEADINGS, 256)
  }
}

class FileSystemGoalPlanningVerificationDiscoveryCapTest {
  @Test
  fun `over budget verification discovery loud fails instead of truncating the catalog`() {
    val repo = Files.createTempDirectory("goal-verification-discovery-cap")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val headings = (0 until GoalVerificationBoundaryCaps.maxHeadingsPerFile + 2).joinToString("\n\n") { index ->
      "## [2026-08-${"%02d".format((index % 28) + 1)}] entry-$index\n\nbody $index"
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$headings\n")

    assertFailsWith<GoalVerificationBoundaryCapExceededError> {
      FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
        repo,
        listOf("modules/a/src/Main.kt"),
        loudFailOnCapExceeded = true,
      )
    }
  }
}
