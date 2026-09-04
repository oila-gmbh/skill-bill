package skillbill.scaffold
import skillbill.nativeagent.testNativeAgentCompositionContext
import skillbill.scaffold.runtime.RepoValidationRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoValidationReleasePolicyTest {
  @Test
  fun `repository validation surfaces malformed agent addon without changing report counts`() {
    val repoRoot = Files.createTempDirectory("skillbill-agent-addon-validation")
    val addon = repoRoot.resolve("agent-addons/review-helper")
    Files.createDirectories(addon)
    Files.writeString(addon.resolve("agent-addon.yaml"), "contract_version: [")
    Files.writeString(addon.resolve("content.md"), "# Fixture\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(report.issues.any { it.startsWith("agent-addons:") })
    assertEquals(0, report.skillCount)
    assertEquals(0, report.addonCount)
    assertEquals(0, report.platformPackCount)
  }

  @Test
  fun `release refs preserve semver metadata`() {
    val stable = RepoValidationRuntime.parseReleaseRef("refs/tags/v1.2.3")
    assertEquals("v1.2.3", stable.tag)
    assertEquals("1.2.3", stable.version)
    assertFalse(stable.prerelease)

    val prerelease = RepoValidationRuntime.parseReleaseRef("v2.0.0-rc.1+build.5")
    assertEquals("v2.0.0-rc.1+build.5", prerelease.tag)
    assertEquals("2.0.0-rc.1+build.5", prerelease.version)
    assertTrue(prerelease.prerelease)
  }

  @Test
  fun `release refs reject bare version tags without v prefix`() {
    listOf("0.2.0", "refs/tags/1.0.0-rc.1").forEach { ref ->
      val failure = assertFailsWith<IllegalArgumentException> {
        RepoValidationRuntime.parseReleaseRef(ref)
      }
      assertTrue(failure.message.orEmpty().contains("canonical vMAJOR"), ref)
    }
  }

  @Test
  fun `release refs reject non semver tags`() {
    val error = kotlin.runCatching {
      RepoValidationRuntime.parseReleaseRef("release-1.0")
    }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error.message.orEmpty().contains("Release tag must match"))
  }

  @Test
  fun `release policy preserves historical lines and requires complete custom policy from v0 1 2`() {
    val repoRoot = Files.createTempDirectory("skillbill-release-policy")

    listOf("v0.0.9", "v0.1.0", "v0.1.1+rebuild.1").forEach { ref ->
      RepoValidationRuntime.validateReleaseRef(repoRoot, ref, forcePrerelease = false)
    }

    val coveredPrerelease = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v0.1.2-rc.1", forcePrerelease = false)
    }
    assertTrue(coveredPrerelease.message.orEmpty().contains("LICENSE"))

    val missingPolicy = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v0.1.2", forcePrerelease = false)
    }
    assertTrue(missingPolicy.message.orEmpty().contains("LICENSE"))

    listOf(
      "identifier-only" to RepoValidationRuntime.PRE_1_LICENSE_IDENTIFIER,
      "marker-only" to RepoValidationRuntime.TRANSITIONAL_LICENSE_MARKER,
      "wrong-boundary" to completeTransitionalLicense().replace("v0.1.2", "v0.1.3"),
      "truncated" to completeTransitionalLicense().substringBefore("10. Termination and cure"),
      "material-clause-change" to
        completeTransitionalLicense().replace(
          "hosted-service use",
          "hosted-product use",
        ),
    ).forEach { (fixture, license) ->
      Files.writeString(repoRoot.resolve("LICENSE"), license)
      val failure = assertFailsWith<IllegalArgumentException> {
        RepoValidationRuntime.validateReleaseRef(repoRoot, "v0.1.2", forcePrerelease = false)
      }
      assertTrue(failure.message.orEmpty().contains("complete current"), fixture)
    }

    Files.writeString(repoRoot.resolve("LICENSE"), completeTransitionalLicense())
    listOf("v0.1.2", "v0.2.0+build.7", "v0.9.9-rc.1").forEach { ref ->
      RepoValidationRuntime.validateReleaseRef(repoRoot, ref, forcePrerelease = false)
    }
    Files.writeString(repoRoot.resolve("LICENSE"), completeTransitionalLicense().replace("\n", "\r\n"))
    RepoValidationRuntime.validateReleaseRef(repoRoot, "v0.1.2", forcePrerelease = false)
  }

  @Test
  fun `release policy keeps rc and manual staging non triggering while rejecting stable v1`() {
    val repoRoot = Files.createTempDirectory("skillbill-pre-one-staging")
    Files.writeString(repoRoot.resolve("LICENSE"), completeTransitionalLicense())

    val releaseCandidate = RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0-rc.1", forcePrerelease = false)
    val staging = RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0-staging.1", forcePrerelease = false)
    val forcedStableFailure = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0", forcePrerelease = true)
    }
    val stableFailure = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0", forcePrerelease = false)
    }

    assertTrue(releaseCandidate.prerelease)
    assertTrue(staging.prerelease)
    assertEquals("v1.0.0-staging.1", staging.tag)
    assertTrue(forcedStableFailure.message.orEmpty().contains("prerelease identifier"))
    assertTrue(stableFailure.message.orEmpty().contains("approved stable license policy"))
  }

  @Test
  fun `v1 release candidates require the exact transitional policy`() {
    val repoRoot = Files.createTempDirectory("skillbill-v1-rc-policy")

    val missing = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0-rc.1", forcePrerelease = false)
    }
    Files.writeString(repoRoot.resolve("LICENSE"), RepoValidationRuntime.TRANSITIONAL_LICENSE_MARKER)
    val incomplete = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0-rc.1", forcePrerelease = false)
    }
    Files.writeString(repoRoot.resolve("LICENSE"), completeTransitionalLicense().replace("Skill Bill Use", "Other Use"))
    val otherPolicy = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0-rc.1", forcePrerelease = false)
    }

    assertTrue(missing.message.orEmpty().contains("LICENSE"))
    assertTrue(incomplete.message.orEmpty().contains("complete current Skill Bill use license"))
    assertTrue(otherPolicy.message.orEmpty().contains("complete current Skill Bill use license"))
  }

  @Test
  fun `post one lines including prereleases require stable policy approval`() {
    val repoRoot = Files.createTempDirectory("skillbill-post-one-policy")
    Files.writeString(repoRoot.resolve("LICENSE"), completeTransitionalLicense())

    listOf("v1.0.1-rc.1", "v1.0.1", "v1.1.0", "v2.0.0+build.7").forEach { ref ->
      val failure = assertFailsWith<IllegalArgumentException> {
        RepoValidationRuntime.validateReleaseRef(repoRoot, ref, forcePrerelease = false)
      }
      assertTrue(failure.message.orEmpty().contains("approved stable license policy"), ref)
    }
  }

  @Test
  fun `post one releases require an approved stable license record tied to its exact bytes`() {
    val repoRoot = Files.createTempDirectory("skillbill-successor-license")
    val successor = completeTransitionalLicense()
    Files.writeString(
      repoRoot.resolve("LICENSE"),
      successor,
    )

    assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.1.0", forcePrerelease = false)
    }
    writeSuccessorApproval(repoRoot, successor)
    RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.1.0", forcePrerelease = false)
    Files.writeString(repoRoot.resolve("LICENSE"), successor.replace("Commercial License", "Business Agreement"))
    val changedLicense = assertFailsWith<IllegalArgumentException> {
      RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.1.0", forcePrerelease = false)
    }
    assertTrue(changedLicense.message.orEmpty().contains("approved stable license policy"))
  }

  @Test
  fun `post one release rejects unapproved or altered policy placeholders`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-successor-license")
    listOf(
      "",
      "Identifier: LicenseRef-Skill-Bill-Use-1.0\n",
      "Historical notice\n\n${completeTransitionalLicense()}",
      completeTransitionalLicense().replace("Commercial License", "Business Agreement"),
    ).forEach { license ->
      Files.writeString(repoRoot.resolve("LICENSE"), license)
      val failure = assertFailsWith<IllegalArgumentException> {
        RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.1", forcePrerelease = false)
      }
      assertTrue(failure.message.orEmpty().contains("approved stable license policy"))
    }
  }
}
