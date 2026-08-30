package skillbill.scaffold

import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.renderNativeAgentSource
import skillbill.scaffold.runtime.RepoValidationRuntime
import skillbill.scaffold.runtime.requiredSupportingFilesForSkill
import skillbill.scaffold.runtime.supportingFileTargets
import skillbill.testing.seedConformingPlatformPack
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoValidationInternalSkillTest {
  @Test
  fun `repo validation rejects internal-for with unknown parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-unknown-parent")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature-helper", "bill-featur")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("not a discovered skill") && it.contains("bill-feature-helper") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects internal-for with self parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-self-parent")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature-helper", "bill-feature-helper")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("skill itself") && it.contains("bill-feature-helper") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects chained internal-for`() {
    val repoRoot = Files.createTempDirectory("skillbill-chained")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-other", null)
    seedInternalSkill(repoRoot, "bill-feature", "bill-other")
    seedInternalSkill(repoRoot, "bill-feature-helper", "bill-feature")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("chained internal-for") && it.contains("bill-feature") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects an authored sidecar collision with the internal name`() {
    val repoRoot = Files.createTempDirectory("skillbill-collision")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature", null)
    seedInternalSkill(repoRoot, "bill-feature-helper", "bill-feature")
    Files.writeString(
      repoRoot.resolve("skills/bill-feature/bill-feature-helper.md"),
      "authored collision\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("collides") && it.contains("bill-feature-helper.md") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation does not require internal skills in the README catalog`() {
    val repoRoot = Files.createTempDirectory("skillbill-readme-internal")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature", null)
    seedInternalSkill(repoRoot, "bill-feature-helper", "bill-feature")
    // README catalog lists the listed skills but intentionally omits the internal one.
    Files.writeString(
      repoRoot.resolve("README.md"),
      """
      | Skill | Purpose |
      |-------|---------|
      | `/bill-code-review` | Review code |
      | `/bill-feature` | Feature entry |
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    val readmeCatalogIssues = report.issues.filter { it.contains("README.md catalog is missing skills") }
    val detail = readmeCatalogIssues.joinToString("\n")
    assertTrue(
      readmeCatalogIssues.isEmpty(),
      "internal skills must be excluded from the README catalog requirement; got: $detail",
    )
  }

}
