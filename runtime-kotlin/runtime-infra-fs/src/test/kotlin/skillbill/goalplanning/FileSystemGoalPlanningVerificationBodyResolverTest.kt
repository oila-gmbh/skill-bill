package skillbill.goalplanning

import skillbill.error.GoalVerificationBoundaryCapExceededError
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.verification.model.GoalVerificationContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemGoalPlanningVerificationBodyResolverTest {
  @Test
  fun `over budget verification resolution raises the named cap error`() {
    val repo = Files.createTempDirectory("goal-verification-body-cap")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val recent = LocalDate.now(ZoneOffset.UTC).minusDays(5)
    val headings = (0 until GoalVerificationContext.MAX_SELECTED_BODIES + 2).joinToString("\n\n") { index ->
      "## [$recent] entry-$index\n\nbody $index"
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$headings\n")
    val catalog = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("modules/a/src/Main.kt"),
    ).boundaryCatalog
    val selected = catalog.map(GoalPlanningBoundaryHeading::headingId)

    val error = assertFailsWith<GoalVerificationBoundaryCapExceededError> {
      FileSystemGoalPlanningBoundaryBodyResolver().resolve(
        repo,
        selected,
        catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
        caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
        loudFailOnCapExceeded = true,
      )
    }
    assertEquals(
      "finding verification boundary body resolution exceeded max_selected_bodies or max_total_body_bytes",
      error.message,
    )
  }

  @Test
  fun `per body byte cap loud fails instead of truncating under verification caps`() {
    val repo = Files.createTempDirectory("goal-verification-body-bytes")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val bigBody = "x".repeat(GoalVerificationContext.MAX_BODY_BYTES + 64)
    val recent = LocalDate.now(ZoneOffset.UTC).minusDays(5)
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [$recent] big-entry\n\n$bigBody\n",
    )
    val catalog = catalogOf(repo)
    val headingId = catalog.single().headingId

    assertFailsWith<GoalVerificationBoundaryCapExceededError> {
      FileSystemGoalPlanningBoundaryBodyResolver().resolve(
        repo,
        listOf(headingId),
        catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
        caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
        loudFailOnCapExceeded = true,
      )
    }
  }

  private fun catalogOf(repo: Path): List<GoalPlanningBoundaryHeading> =
    FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("modules/a/src/Main.kt"),
    ).boundaryCatalog
}
