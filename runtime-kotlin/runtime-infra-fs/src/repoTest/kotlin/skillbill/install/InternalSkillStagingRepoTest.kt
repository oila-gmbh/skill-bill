package skillbill.infrastructure.fs.install

import skillbill.infrastructure.fs.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.fs.install.staging.stageInstalledSkill
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
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
      sourceDir = uiDir.toFileLocation(),
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = "kmp",
      internalFor = "bill-code-review",
    )

    val rendered = stageInstalledSkill(
      StageInstalledSkillInput(
        repoRoot = repoRoot,
        sourceSkillDir = parentDir,
        home = home,
        selectedPackSkills = listOf(uiSkill),
      ),
    )

    val wrapper = rendered.stagingDir.resolve("bill-kmp-code-review-ui.md")
    val companion = rendered.stagingDir.resolve("compose-guidelines.md")
    assertTrue(Files.isRegularFile(wrapper.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isRegularFile(companion.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.readString(wrapper.toPath()).contains("[compose-guidelines.md](compose-guidelines.md)"))
    assertEquals(companion.toPath(), wrapper.toPath().parent.resolve("compose-guidelines.md"))
  }
}
