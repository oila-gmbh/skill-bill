package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliUninstallRuntimeTest {
  @Test
  fun `dry run reports uninstall plan without removing files`() {
    val fixture = uninstallFixture()

    val result = runUninstall(fixture.home, "--dry-run")

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "uninstall_status: dry_run")
    assertTrue(Files.exists(fixture.stateRoot))
    assertTrue(Files.exists(fixture.managedSkillDir))
    assertTrue(Files.isSymbolicLink(fixture.skillBillLauncher))
  }

  @Test
  fun `uninstall aborts without confirmation`() {
    val fixture = uninstallFixture()

    val result = runUninstall(fixture.home, stdinText = "no\n")

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "uninstall_status: aborted")
    assertTrue(Files.exists(fixture.stateRoot))
    assertTrue(Files.exists(fixture.managedSkillDir))
  }

  @Test
  fun `uninstall removes managed install artifacts and preserves user files`() {
    val fixture = uninstallFixture()

    val result = runUninstall(fixture.home, "--yes")

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "uninstall_status: completed")
    assertFalse(Files.exists(fixture.stateRoot))
    assertFalse(Files.exists(fixture.managedSkillDir))
    assertFalse(Files.exists(fixture.legacySkillDir))
    assertFalse(Files.exists(fixture.ownedSymlink))
    assertFalse(Files.exists(fixture.skillBillLauncher))
    assertFalse(Files.exists(fixture.skillBillMcpLauncher))
    assertTrue(Files.exists(fixture.userSkillDir))
    assertTrue(Files.exists(fixture.userLauncher))
  }

  private fun runUninstall(home: Path, vararg args: String, stdinText: String? = null) = CliRuntime.run(
    listOf("--home", home.toString(), "uninstall") + args,
    CliRuntimeContext(
      userHome = home,
      stdinText = stdinText,
      environment = emptyMap(),
    ),
  )

  private fun uninstallFixture(): UninstallFixture {
    val home = Files.createTempDirectory("skillbill-cli-uninstall")
    val stateRoot = home.resolve(".skill-bill")
    val installedSkill = stateRoot.resolve("installed-skills/bill-code-review-1111111111111111")
    val runtimeCli = stateRoot.resolve("runtime/runtime-cli/bin/runtime-cli")
    val runtimeMcp = stateRoot.resolve("runtime/runtime-mcp/bin/runtime-mcp")
    Files.createDirectories(installedSkill)
    Files.writeString(installedSkill.resolve("SKILL.md"), "# bill-code-review\n")
    Files.createDirectories(runtimeCli.parent)
    Files.writeString(runtimeCli, "runtime cli\n")
    Files.createDirectories(runtimeMcp.parent)
    Files.writeString(runtimeMcp, "runtime mcp\n")

    val codexSkills = home.resolve(".codex/skills")
    val managedSkillDir = codexSkills.resolve("bill-code-review")
    val legacySkillDir = codexSkills.resolve("mdp-code-review")
    val userSkillDir = codexSkills.resolve("user-skill")
    val ownedSymlink = codexSkills.resolve("bill-old-orphan")
    Files.createDirectories(managedSkillDir)
    Files.writeString(managedSkillDir.resolve("Managed by skill-bill install.sh"), "")
    Files.createDirectories(legacySkillDir)
    Files.writeString(legacySkillDir.resolve("Managed by skill-bill install.sh"), "")
    Files.createDirectories(userSkillDir)
    Files.writeString(userSkillDir.resolve("README.md"), "user owned\n")
    Files.createSymbolicLink(ownedSymlink, installedSkill)

    val binDir = home.resolve(".local/bin")
    Files.createDirectories(binDir)
    val skillBillLauncher = binDir.resolve("skill-bill")
    val skillBillMcpLauncher = binDir.resolve("skill-bill-mcp")
    val userLauncher = binDir.resolve("skill-bill-user")
    Files.createSymbolicLink(skillBillLauncher, runtimeCli)
    Files.createSymbolicLink(skillBillMcpLauncher, runtimeMcp)
    Files.writeString(userLauncher, "user launcher\n")

    return UninstallFixture(
      home = home,
      stateRoot = stateRoot,
      managedSkillDir = managedSkillDir,
      legacySkillDir = legacySkillDir,
      userSkillDir = userSkillDir,
      ownedSymlink = ownedSymlink,
      skillBillLauncher = skillBillLauncher,
      skillBillMcpLauncher = skillBillMcpLauncher,
      userLauncher = userLauncher,
    )
  }
}

private data class UninstallFixture(
  val home: Path,
  val stateRoot: Path,
  val managedSkillDir: Path,
  val legacySkillDir: Path,
  val userSkillDir: Path,
  val ownedSymlink: Path,
  val skillBillLauncher: Path,
  val skillBillMcpLauncher: Path,
  val userLauncher: Path,
)
