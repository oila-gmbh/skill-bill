package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallApplyRepoLocalConfigTest : InstallApplyTestSupport() {
  @Test
  fun `apply scaffolds default repo-local config and anchored gitignore entry`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val configPath = fixture.repoRoot.resolve(".skill-bill/config.yaml")
    assertTrue(Files.exists(configPath), "install should scaffold the repo-local config")
    val configContent = Files.readString(configPath)
    assertContains(configContent, "spec_type: local")
    assertContains(configContent, "code_review_parallel_agent: none")
    val gitignoreContent = Files.readString(fixture.repoRoot.resolve(".gitignore"))
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == "/.skill-bill/" })
  }

  @Test
  fun `reapply is idempotent and preserves a user-edited config`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))
    InstallOperations.applyInstall(plan)
    val configPath = fixture.repoRoot.resolve(".skill-bill/config.yaml")
    val userEdited = "spec_type: linear\ncode_review_parallel_agent: claude\n"
    Files.writeString(configPath, userEdited)

    val second = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertEquals(userEdited, Files.readString(configPath), "re-install must not clobber a user-edited config")
    val gitignoreContent = Files.readString(fixture.repoRoot.resolve(".gitignore"))
    assertEquals(
      1,
      gitignoreContent.lines().count { line -> line.trim() == "/.skill-bill/" },
      "re-install must not duplicate the .gitignore entry",
    )
  }

  @Test
  fun `apply preserves an existing user gitignore and appends the anchored entry once`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val gitignorePath = fixture.repoRoot.resolve(".gitignore")
    Files.writeString(gitignorePath, "build/\n*.log\n")
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val gitignoreContent = Files.readString(gitignorePath)
    assertContains(gitignoreContent, "build/")
    assertContains(gitignoreContent, "*.log")
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == "/.skill-bill/" })
  }
}
