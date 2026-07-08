package skillbill.team

import skillbill.contracts.team.TEAM_BUNDLE_CONTRACT_VERSION
import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamBundleCompatibility
import skillbill.team.model.TeamBundleExclusions
import skillbill.team.model.TeamBundleHashes
import skillbill.team.model.TeamBundleMetadata
import skillbill.team.model.TeamBundlePrivacyDefaults
import skillbill.team.model.TeamBundlePrivacyLevel
import skillbill.team.model.TeamBundleSourceCategory
import skillbill.team.model.TeamBundleSourceEntry
import skillbill.team.model.TeamBundleTeamMetadata
import skillbill.team.model.TeamBundleTelemetryDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamBundleModelsTest {
  @Test
  fun `enum wire mappings round trip`() {
    assertEquals(TeamBundleChannel.STABLE, TeamBundleChannel.fromWireValue("stable"))
    assertEquals(TeamBundlePrivacyLevel.ANONYMOUS, TeamBundlePrivacyLevel.fromWireValue("anonymous"))
    assertEquals(TeamBundleSourceCategory.HORIZONTAL_SKILL, TeamBundleSourceCategory.fromWireValue("horizontal_skill"))
    assertNull(TeamBundleChannel.fromWireValue("nightly"))
  }

  @Test
  fun `minimal model construction carries contract version and bundle metadata`() {
    val bundle = TeamBundle(
      metadata = TeamBundleMetadata(
        bundleId = "team-bundle-foundation",
        version = "1.0.0",
        channel = TeamBundleChannel.STABLE,
        createdAt = "2026-07-08T00:00:00Z",
        createdBy = "platform-team",
        sourceRepo = "skill-bill",
        sourceRef = "main",
      ),
      hashes = TeamBundleHashes(
        contentHash = "sha256:bundle-content",
        manifestHashes = mapOf("platform.yaml" to "sha256:manifest"),
        bundleChecksum = "sha256:bundle",
      ),
      sources = listOf(
        TeamBundleSourceEntry(
          category = TeamBundleSourceCategory.HORIZONTAL_SKILL,
          path = "skills/bill-code-check/content.md",
          contentHash = "sha256:source",
        ),
      ),
      compatibility = TeamBundleCompatibility(
        minSkillBillVersion = "0.1.0",
        shellContractVersion = "1.2",
      ),
      telemetryDefaults = TeamBundleTelemetryDefaults(
        enabled = true,
        level = TeamBundlePrivacyLevel.ANONYMOUS,
      ),
      privacyDefaults = TeamBundlePrivacyDefaults(
        telemetry = TeamBundlePrivacyLevel.ANONYMOUS,
        sourcePaths = TeamBundlePrivacyLevel.ANONYMOUS,
        authorIdentity = TeamBundlePrivacyLevel.OFF,
      ),
      teamMetadata = TeamBundleTeamMetadata(teamId = "platform", name = "Platform"),
      exclusions = TeamBundleExclusions(paths = emptyList(), reasons = emptyMap()),
    )

    assertEquals(TEAM_BUNDLE_CONTRACT_VERSION, bundle.contractVersion)
    assertEquals("team-bundle-foundation", bundle.metadata.bundleId)
  }
}
