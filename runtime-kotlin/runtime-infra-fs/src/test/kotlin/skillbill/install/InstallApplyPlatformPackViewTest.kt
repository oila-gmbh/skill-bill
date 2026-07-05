package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyStatus
import skillbill.install.runtime.InstallOperations
import skillbill.scaffold.platformpack.discoverPlatformPacks
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyPlatformPackViewTest : InstallApplyTestSupport() {
  @Test
  fun `apply materializes selected platform pack manifests under each agent skill root`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val agentRoot = fixture.home.resolve("agent-skill-targets/codex")
    val packRoot = agentRoot.resolve("platform-packs/kotlin")
    assertTrue(Files.isRegularFile(agentRoot.resolve("platform-packs/.skill-bill-install")))
    assertTrue(Files.isRegularFile(packRoot.resolve("platform.yaml")), "installed pack manifest is missing")
    assertEquals(listOf("kotlin"), discoverPlatformPacks(agentRoot.resolve("platform-packs")).map { pack -> pack.slug })
    val flatCodeReview = readSymlinkTarget(agentRoot.resolve("bill-kotlin-code-review"))
    val nestedCodeReview = readSymlinkTarget(packRoot.resolve("code-review/bill-kotlin-code-review"))
    assertEquals(flatCodeReview, nestedCodeReview)
    assertTrue(Files.isRegularFile(packRoot.resolve("code-review/bill-kotlin-code-review/content.md")))
    assertTrue(Files.isRegularFile(packRoot.resolve("quality-check/bill-kotlin-code-check/content.md")))
    assertFalse(
      Files.exists(agentRoot.resolve("platform-packs/kmp/platform.yaml"), LinkOption.NOFOLLOW_LINKS),
      "unselected pack manifest must not be discoverable",
    )
  }

  @Test
  fun `apply copies only declared internal quality check content into platform pack view`() {
    val fixture = setupApplyFixture()
    val qualityCheckDir = fixture.repoRoot.resolve("platform-packs/kotlin/quality-check/bill-kotlin-code-check")
    Files.writeString(qualityCheckDir.resolve("notes.md"), "private implementation notes")
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val packQualityCheck = fixture.home
      .resolve("agent-skill-targets/codex/platform-packs/kotlin/quality-check/bill-kotlin-code-check")
    assertTrue(Files.isRegularFile(packQualityCheck.resolve("content.md")))
    assertFalse(
      Files.exists(packQualityCheck.resolve("notes.md"), LinkOption.NOFOLLOW_LINKS),
      "platform-pack view must not expose undeclared files from internal skill source directories",
    )
  }

  @Test
  fun `apply succeeds when a selected platform pack skill declares internal-for and stages as a sidecar`() {
    val fixture = setupApplyFixture()
    val internalPackSkillDir = fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    Files.writeString(
      internalPackSkillDir.resolve("content.md"),
      """
      |---
      |name: bill-kotlin-code-review
      |description: Internal pack dispatch target.
      |internal-for: bill-code-review
      |---
      |
      |Authored internal pack body.
      """.trimMargin(),
    )
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val agentRoot = fixture.home.resolve("agent-skill-targets/codex")
    val internalPackView = agentRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    assertFalse(
      Files.exists(internalPackView, LinkOption.NOFOLLOW_LINKS),
      "internal pack skill must not materialize a standalone platform-packs symlink",
    )
    assertTrue(
      Files.isRegularFile(agentRoot.resolve("bill-code-review/bill-kotlin-code-review.md")),
      "internal pack skill must stage as a sibling sidecar of its parent's installed directory",
    )
  }
}
