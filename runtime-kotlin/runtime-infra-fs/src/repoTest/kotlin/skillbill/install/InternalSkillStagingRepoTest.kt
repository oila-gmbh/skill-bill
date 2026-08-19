package skillbill.install

import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.staging.stageInstalledSkill
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalSkillStagingRepoTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `shipped kmp ui wrapper resolves its flat compose guidelines companion`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-kmp-companion-home").also(tempDirs::add)
    val parentDir = repoRoot.resolve("skills/bill-code-review")
    val uiDir = repoRoot.resolve("platform-packs/kmp/code-review/bill-kmp-code-review-ui")
    val uiSkill = InstallPlanSkill(
      name = "bill-kmp-code-review-ui",
      sourceDir = uiDir,
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = "kmp",
      internalFor = "bill-code-review",
    )

    val rendered = stageInstalledSkill(
      repoRoot,
      parentDir,
      home,
      selectedPackSkills = listOf(uiSkill),
    )

    val wrapper = rendered.stagingDir.resolve("bill-kmp-code-review-ui.md")
    val companion = rendered.stagingDir.resolve("compose-guidelines.md")
    assertTrue(Files.isRegularFile(wrapper, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.readString(wrapper).contains("[compose-guidelines.md](compose-guidelines.md)"))
    assertEquals(companion, wrapper.parent.resolve("compose-guidelines.md"))
  }
}
