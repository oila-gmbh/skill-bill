package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseWorkflowContractTest {
  private val repoRoot: Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
      .first { Files.isRegularFile(it.resolve("LICENSE")) }

  @Test
  fun `release workflow verifies only canonical licensed artifacts before upload`() {
    val workflow = Files.readString(repoRoot.resolve(".github/workflows/release.yml"))

    listOf(
      "runtime-cli-\${RELEASE_VERSION}-\${host_token}.zip",
      "runtime-mcp-\${RELEASE_VERSION}-\${host_token}.zip",
      "SkillBill-\${RELEASE_VERSION}-\${host_token}.dmg",
      "SkillBill-\${RELEASE_VERSION}-\${host_token}.msi",
      "SkillBill-\${RELEASE_VERSION}-\${host_token}.deb",
      "SkillBill-\${RELEASE_VERSION}-\${host_token}.rpm",
      "skill-bill-skills-\${RELEASE_VERSION}.tar.gz",
    ).forEach { path -> assertTrue(workflow.contains(path), path) }
    assertTrue(workflow.contains("scripts/verify_release_artifact_licenses \"\${artifacts[@]}\""))
    assertTrue(workflow.contains("cp \"\${artifact}\" \"\${artifact}.sha256\" release-assets/"))
    assertTrue(workflow.contains("path: release-assets/"))
    assertTrue(workflow.contains("artifact_names=("))
    assertTrue(workflow.contains("Missing expected release asset"))
    assertFalse(workflow.contains("binaries/main/*/SkillBill-*"))
  }

  @Test
  fun `release workflow rejects existing releases instead of replacing their assets`() {
    val workflow = Files.readString(repoRoot.resolve(".github/workflows/release.yml"))

    assertTrue(workflow.contains("release assets are immutable"))
    assertTrue(workflow.contains("Use a fresh staging version"))
    assertTrue(workflow.contains("gh release create \"\${args[@]}\" --draft"))
    assertTrue(workflow.contains("gh release upload \"\${RELEASE_TAG}\" \"\${assets[@]}\""))
    assertTrue(workflow.contains("gh release edit \"\${RELEASE_TAG}\""))
    assertTrue(workflow.contains("SemVer prerelease label"))
    assertTrue(workflow.contains("scripts/validate_release_ref \"\$raw\" \"\${validation_args[@]}\""))
    assertTrue(workflow.contains("scripts/validate_release_ref \"\$STAGING_VERSION\" --force-prerelease"))
    assertFalse(workflow.contains("raw=\"\${{"))
    assertFalse(workflow.contains("--clobber"))
  }
}
