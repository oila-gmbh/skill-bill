package skillbill.goalplanning

import skillbill.contracts.goalplanning.GoalVerificationBoundaryCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.verification.model.GoalVerificationContext
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalVerificationBoundaryCapsParityTest {
  @Test
  fun `verification caps are contract-backed and stay tighter than planning except shared file bytes`() {
    assertEquals(GoalVerificationBoundaryCaps.maxDiscoveryFileCount, GoalVerificationContext.MAX_DISCOVERY_FILE_COUNT)
    assertEquals(GoalVerificationBoundaryCaps.maxHeadingsPerFile, GoalVerificationContext.MAX_HEADINGS_PER_FILE)
    assertEquals(GoalVerificationBoundaryCaps.maxCatalogHeadings, GoalVerificationContext.MAX_CATALOG_HEADINGS)
    assertEquals(GoalVerificationBoundaryCaps.historyRecencyDays, GoalVerificationContext.HISTORY_RECENCY_DAYS)
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
  fun `verification discovery keeps only the newest recent entries per boundary file`() {
    val repo = Files.createTempDirectory("goal-verification-discovery-cap")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val today = LocalDate.now(ZoneOffset.UTC)
    val headings = (0 until GoalVerificationBoundaryCaps.maxHeadingsPerFile + 5).joinToString("\n\n") { index ->
      "## [$today] entry-$index\n\nbody $index"
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$headings\n")

    val discovery = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("modules/a/src/Main.kt"),
    )

    assertEquals(GoalVerificationBoundaryCaps.maxHeadingsPerFile, discovery.boundaryCatalog.size)
    assertTrue(discovery.boundaryCatalogTruncated)
    assertTrue(discovery.boundaryCatalog.all { it.heading.contains("entry-") })
    assertTrue(
      discovery.boundaryCatalog.none {
        it.heading.contains("entry-${GoalVerificationBoundaryCaps.maxHeadingsPerFile}")
      },
    )
    assertTrue(discovery.boundaryCatalog.any { it.heading.contains("entry-0") })
  }
}
