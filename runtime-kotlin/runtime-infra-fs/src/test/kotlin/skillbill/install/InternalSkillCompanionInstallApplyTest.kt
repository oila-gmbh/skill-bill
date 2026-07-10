package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyStatus
import skillbill.install.runtime.InstallOperations
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InternalSkillCompanionInstallApplyTest : InstallApplyTestSupport() {
  @Test
  fun `reapply restores an externally deleted internal skill authored companion`() {
    val fixture = setupApplyFixture()
    val internalSkillDir = fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    Files.writeString(
      internalSkillDir.resolve("content.md"),
      content("bill-kotlin-code-review", internalFor = "bill-code-review"),
    )
    Files.writeString(internalSkillDir.resolve("review-guidelines.md"), "governed review rubric\n")
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    val first = InstallOperations.applyInstall(plan)
    val parentStaging = first.skills.single { skill -> skill.skillName == "bill-code-review" }.staging.stagingDir
    val companion = assertNotNull(parentStaging).resolve("review-guidelines.md")
    assertTrue(Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS))
    Files.delete(companion)

    val second = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertTrue(Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS))
    assertEquals("governed review rubric\n", Files.readString(companion))
  }
}
