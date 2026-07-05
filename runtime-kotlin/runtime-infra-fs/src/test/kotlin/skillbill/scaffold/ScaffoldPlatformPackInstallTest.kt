package skillbill.scaffold

import skillbill.scaffold.policy.renderPlatformPackManifest
import skillbill.scaffold.rendering.inferSkillDescription
import skillbill.scaffold.rendering.renderContentBody
import skillbill.scaffold.runtime.TemplateContext
import skillbill.scaffold.runtime.scaffold
import skillbill.scaffold.runtime.supportingFileTargets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldPlatformPackInstallTest {
  @Test
  fun `platform pack scaffold installs internal quality check as bill-code-check sidecar`() = withIsolatedUserHome {
    val repo = seedRepo()
    val home = Path.of(System.getProperty("user.home"))
    Files.createDirectories(home.resolve(".codex"))

    scaffold(payload(repo, "platform-pack", "platform" to "java"))

    val codexSkills = home.resolve(".codex/skills")
    assertFalse(
      Files.exists(codexSkills.resolve("bill-java-code-check"), LinkOption.NOFOLLOW_LINKS),
      "internal platform quality-check skill must not install as a listed skill",
    )
    val parentTarget = readSymlinkTarget(codexSkills.resolve("bill-code-check"))
    assertTrue(
      Files.isRegularFile(parentTarget.resolve("bill-java-code-check.md")),
      "platform quality-check skill must install as a sidecar of bill-code-check",
    )
  }
}

private fun payload(repo: Path, kind: String, vararg pairs: Pair<String, Any?>): Map<String, Any?> =
  mapOf("scaffold_payload_version" to "1.0", "kind" to kind, "repo_root" to repo.toString()) + pairs

private fun seedRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-platform-install-repo")
  skillbill.testsupport.SkillClassFixtures.seedShippedSkillClasses(repo)
  supportingFileTargets(repo).values.forEach { target ->
    Files.createDirectories(target.parent)
    Files.writeString(target, "# ${target.fileName}\n")
  }
  seedBaseSkill(repo, "bill-code-check")
  seedKmpPack(repo)
  return repo
}

private fun seedBaseSkill(repo: Path, skillName: String) {
  val skillDir = repo.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("content.md"),
    """
    |---
    |name: $skillName
    |description: Test base skill.
    |---
    |
    |Test body.
    """.trimMargin(),
  )
}

private fun seedKmpPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kmp")
  val baseline = packRoot.resolve("code-review/bill-kmp-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kmp-code-review", "code-review", "kmp", "", "KMP")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      platform = "kmp",
      displayName = "KMP",
      strongSignals = listOf(".kt"),
      baselineContentPath = "code-review/bill-kmp-code-review/content.md",
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun readSymlinkTarget(linkPath: Path): Path {
  val target = Files.readSymbolicLink(linkPath)
  val resolved = if (target.isAbsolute) target else linkPath.parent.resolve(target)
  return resolved.toAbsolutePath().normalize()
}

private fun withIsolatedUserHome(block: () -> Unit) {
  val originalHome = System.getProperty("user.home")
  val tempHome = Files.createTempDirectory("skillbill-platform-install-home")
  try {
    System.setProperty("user.home", tempHome.toString())
    block()
  } finally {
    System.setProperty("user.home", originalHome)
  }
}
