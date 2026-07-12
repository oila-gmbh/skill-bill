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
    assertTrue(workflow.contains("intentional license drift probe"))
    assertTrue(workflow.contains("for artifact in \"\${artifacts[@]}\""))
    assertTrue(workflow.contains("License drift probe unexpectedly accepted \${artifact}."))
    assertTrue(workflow.contains("grep -q 'LICENSE bytes differ'"))
    assertTrue(workflow.contains("choco install lessmsi --yes --no-progress"))
    assertTrue(workflow.contains("cp \"\${artifact}\" \"\${artifact}.sha256\" release-assets/"))
    assertTrue(workflow.contains("path: release-assets/"))
    assertTrue(workflow.contains("artifact_names=("))
    assertTrue(workflow.contains("Missing expected release asset"))
    assertFalse(workflow.contains("binaries/main/*/SkillBill-*"))
  }

  @Test
  fun `release workflow binds publication to the validated commit and safely reconciles retries`() {
    val workflow = Files.readString(repoRoot.resolve(".github/workflows/release.yml"))

    assertTrue(workflow.contains("--target \"\${GITHUB_SHA}\""))
    assertTrue(workflow.contains("git ls-remote --exit-code --refs origin \"refs/tags/\${RELEASE_TAG}\""))
    assertTrue(workflow.contains("Staging tag \${RELEASE_TAG} already exists without a resumable draft release."))
    assertTrue(workflow.contains("git ls-remote origin \"refs/tags/\${RELEASE_TAG}^{}\""))
    assertTrue(workflow.contains("Release tag \${RELEASE_TAG} does not peel to the validated commit \${GITHUB_SHA}."))
    assertTrue(workflow.contains("--json author,isDraft,isPrerelease,targetCommitish,tagName"))
    assertTrue(
      workflow.contains(
        "Existing draft \${RELEASE_TAG} does not match this validated tag, commit, and prerelease classification.",
      ),
    )
    assertTrue(
      workflow.contains("Published release \${RELEASE_TAG} already matches the validated metadata and assets."),
    )
    assertTrue(workflow.contains("Published release is missing expected asset"))
    assertTrue(workflow.contains("gh release create \"\${args[@]}\" --draft"))
    assertTrue(workflow.contains("gh release download \"\${RELEASE_TAG}\""))
    assertTrue(workflow.contains("Existing draft asset bytes differ from the validated asset"))
    assertTrue(workflow.contains("gh release upload \"\${RELEASE_TAG}\" \"dist/\${asset_name}\""))
    assertTrue(workflow.contains("gh release edit \"\${RELEASE_TAG}\""))
    assertTrue(workflow.contains("SemVer prerelease label"))
    assertTrue(workflow.contains("scripts/validate_release_ref \"\$raw\" \"\${validation_args[@]}\""))
    assertTrue(workflow.contains("scripts/validate_release_ref \"\$STAGING_VERSION\" --force-prerelease"))
    assertTrue(workflow.contains("SKILL_BILL_COPYRIGHT_HOLDER_RELEASE_TOKEN"))
    assertTrue(
      workflow.contains("Stable v1.0.0 publication requires the copyright holder's release token and GitHub login."),
    )
    assertTrue(
      workflow.contains(
        "Stable v1.0.0 release \${RELEASE_TAG} was not created by the configured copyright holder.",
      ),
    )
    assertTrue(
      workflow.indexOf("gh release edit \"\${RELEASE_TAG}\"") >
        workflow.lastIndexOf("Draft release is missing uploaded asset"),
    )
    assertFalse(workflow.contains("raw=\"\${{"))
    assertFalse(workflow.contains("--clobber"))
  }

  @Test
  fun `release-ref wrapper shell-quotes each forwarded argument`() {
    val wrapper = Files.readString(repoRoot.resolve("scripts/validate_release_ref"))

    assertTrue(wrapper.contains("for arg in \"\${args[@]}\""))
    assertTrue(wrapper.contains("escaped_arg=\"\${arg//\\\\/\\\\\\\\}\""))
    assertTrue(wrapper.contains("escaped_arg=\"\${escaped_arg//\\\"/\\\\\\\"}\""))
    assertTrue(wrapper.contains("--args=\"\$gradle_args\""))
    assertFalse(wrapper.contains("printf -v"))
    assertFalse(wrapper.contains("\${args[*]}"))
  }
}
