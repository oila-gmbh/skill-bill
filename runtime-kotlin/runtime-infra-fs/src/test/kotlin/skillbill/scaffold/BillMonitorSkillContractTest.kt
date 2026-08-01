package skillbill.scaffold

import skillbill.install.plan.discoverBaseSkills
import skillbill.install.staging.stageInstalledSkill
import skillbill.scaffold.authoring.discoverTargets
import skillbill.scaffold.authoring.renderAuthoringTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillMonitorSkillContractTest {
  @Test
  fun `bill monitor source is a bounded read-only entry point`() {
    val content = Files.readString(repositoryRoot().resolve("skills/bill-monitor/content.md"))

    assertContains(content, "name: bill-monitor")
    assertContains(content, "exactly one issue key argument")
    assertContains(content, "exactly one read-only status snapshot")
    assertContains(content, "--repo-root /absolute/path/to/repository --monitor")
    assertContains(content, "complete, pending, and blocked counts")
    assertContains(content, "exit monitor mode")
    assertContains(content, "For an explicit status follow-up")
    assertContains(content, "refuse the request")
    assertContains(content, "skill-bill goal watch PROJECT-123 --interval-seconds 5")
    assertEquals(1, content.lines().count { line -> line.trimStart().startsWith("skill-bill goal status") })
    assertEquals(1, content.lines().count { line -> line.trimStart().startsWith("skill-bill goal watch") })
    assertFalse(content.contains("skill-bill goal run"))
    assertFalse(content.contains("skill-bill workflow continue"))
    assertFalse(content.contains("skill-bill goal reset"))
    assertFalse(content.contains("skill-bill goal accept"))
  }

  @Test
  fun `bill monitor is found by authored and install-plan discovery`() {
    val root = repositoryRoot()

    assertTrue(discoverTargets(root).containsKey("bill-monitor"))
    assertTrue(discoverBaseSkills(root.resolve("skills")).any { it.name == "bill-monitor" })
    assertContains(
      Files.readString(root.resolve("README.md")),
      "| `/bill-monitor` | Inspect one decomposed goal with a bounded read-only status snapshot |",
    )
    val report = RepoValidationRuntime.validateRepo(root)
    assertFalse(
      report.issues.any { issue -> issue.contains("README.md catalog is missing skills") && issue.contains("bill-monitor") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `bill monitor render and staging keep generated output out of source`() {
    val root = repositoryRoot()
    val sourceDir = root.resolve("skills/bill-monitor")
    val rendered = renderAuthoringTarget(root, "bill-monitor")
    val staged = stageInstalledSkill(root, sourceDir, Files.createTempDirectory("skillbill-bill-monitor-home"))

    assertContains(rendered.stdout, "Governed skill: `bill-monitor`")
    assertContains(staged.stagingDir.resolve("SKILL.md").readText(), "Governed skill: `bill-monitor`")
    assertFalse(Files.exists(sourceDir.resolve("SKILL.md")))
    assertFalse(Files.exists(sourceDir.resolve("shell-ceremony.md")))
  }

  private fun repositoryRoot(): Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
    .first { Files.isRegularFile(it.resolve("LICENSE")) }
}
