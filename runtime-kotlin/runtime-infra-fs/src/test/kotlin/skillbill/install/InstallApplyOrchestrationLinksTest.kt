package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.OrchestrationLinkStatus
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyOrchestrationLinksTest : InstallApplyTestSupport() {
  @Test
  fun `apply creates orchestration symlink for each unique agent root when orchestration dir exists`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.repoRoot.resolve("orchestration"))
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX, InstallAgent.CLAUDE)))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty(), "unexpected failures: ${result.failures}")
    val orchestrationSource = fixture.repoRoot.resolve("orchestration").toAbsolutePath().normalize()
    assertEquals(1, result.orchestrationLinks.size, "agents sharing a parent dir produce one link")
    val link = result.orchestrationLinks.single()
    assertEquals(OrchestrationLinkStatus.CREATED, link.status)
    assertEquals(orchestrationSource, link.linkTarget)
    assertTrue(Files.isSymbolicLink(link.linkPath), "orchestration link path should be a symlink")
    assertEquals(orchestrationSource, readSymlinkTarget(link.linkPath))
  }

  @Test
  fun `reapply reports existing orchestration symlink as skipped when already linked`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.repoRoot.resolve("orchestration"))
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))
    InstallOperations.applyInstall(plan)

    val second = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertEquals(1, second.orchestrationLinks.size)
    assertEquals(OrchestrationLinkStatus.SKIPPED, second.orchestrationLinks.single().status)
    assertTrue(second.orchestrationLinks.single().message.contains("already linked"))
  }

  @Test
  fun `apply produces no orchestration links when orchestration dir is absent from repo`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.orchestrationLinks.isEmpty(), "no orchestration dir should produce no links")
  }

  @Test
  fun `apply replaces stale orchestration symlink pointing to a different target`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.repoRoot.resolve("orchestration"))
    val agentTargetParent = fixture.home.resolve("agent-skill-targets")
    Files.createDirectories(agentTargetParent)
    val orchestrationLink = agentTargetParent.resolve("orchestration")
    val staleTarget = fixture.home.resolve("some-other-dir")
    Files.createDirectories(staleTarget)
    createSymlinkOrSkip(orchestrationLink, staleTarget)
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(OrchestrationLinkStatus.CREATED, result.orchestrationLinks.single().status)
    val orchestrationSource = fixture.repoRoot.resolve("orchestration").toAbsolutePath().normalize()
    assertEquals(orchestrationSource, readSymlinkTarget(orchestrationLink))
  }

  @Test
  fun `reinstall removes and recreates orchestration symlink`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.repoRoot.resolve("orchestration"))
    val initialPlan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))
    InstallOperations.applyInstall(initialPlan)
    val agentTargetParent = fixture.home.resolve("agent-skill-targets")
    val orchestrationLink = agentTargetParent.resolve("orchestration")
    assertTrue(Files.isSymbolicLink(orchestrationLink), "initial install should create orchestration link")

    val reinstallPlan = InstallOperations.planInstall(
      fixture.request(agents = setOf(InstallAgent.CODEX), replaceExistingSkillBillLinks = true),
    )
    val result = InstallOperations.applyInstall(reinstallPlan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(OrchestrationLinkStatus.CREATED, result.orchestrationLinks.single().status)
    assertTrue(Files.isSymbolicLink(orchestrationLink))
    val orchestrationSource = fixture.repoRoot.resolve("orchestration").toAbsolutePath().normalize()
    assertEquals(orchestrationSource, readSymlinkTarget(orchestrationLink))
  }

  @Test
  fun `apply preserves existing non-symlink at orchestration path and surfaces a warning`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.repoRoot.resolve("orchestration"))
    val agentTargetParent = fixture.home.resolve("agent-skill-targets")
    val orchestrationDir = agentTargetParent.resolve("orchestration")
    Files.createDirectories(orchestrationDir)
    val plan = InstallOperations.planInstall(fixture.request(agents = setOf(InstallAgent.CODEX)))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertEquals(OrchestrationLinkStatus.FAILED, result.orchestrationLinks.single().status)
    assertTrue(Files.isDirectory(orchestrationDir, LinkOption.NOFOLLOW_LINKS), "non-symlink dir should be preserved")
    assertTrue(
      result.warnings.any { warning -> warning.kind == skillbill.install.model.InstallApplyIssueKind.ORCHESTRATION_LINK_FAILED },
    )
    assertFalse(Files.isSymbolicLink(orchestrationDir), "non-symlink dir should not be replaced")
  }
}
