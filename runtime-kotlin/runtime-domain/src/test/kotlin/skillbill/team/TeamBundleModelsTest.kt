package skillbill.team

import skillbill.contracts.team.TEAM_BUNDLE_CONTRACT_VERSION
import skillbill.error.InvalidTeamBundleSchemaError
import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamBundleCompatibility
import skillbill.team.model.TeamBundleExclusions
import skillbill.team.model.TeamBundleHashes
import skillbill.team.model.TeamBundleMetadata
import skillbill.team.model.TeamBundleParser
import skillbill.team.model.TeamBundlePrivacyDefaults
import skillbill.team.model.TeamBundlePrivacyLevel
import skillbill.team.model.TeamBundleSourceCategory
import skillbill.team.model.TeamBundleSourceEntry
import skillbill.team.model.TeamBundleTeamMetadata
import skillbill.team.model.TeamBundleTelemetryDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TeamBundleModelsTest {
  @Test
  fun `enum wire mappings round trip`() {
    assertEquals(TeamBundleChannel.DEVELOPMENT, TeamBundleChannel.fromWireValue("development"))
    assertEquals(TeamBundleChannel.BETA, TeamBundleChannel.fromWireValue("beta"))
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
      exclusions = TeamBundleExclusions(paths = emptyList(), reasons = emptyMap()),
    )

    assertEquals(TEAM_BUNDLE_CONTRACT_VERSION, bundle.contractVersion)
    assertEquals("team-bundle-foundation", bundle.metadata.bundleId)
    assertNull(bundle.teamMetadata)
  }

  @Test
  fun `parser exposes validated wire map as typed team bundle`() {
    val parsed = TeamBundleParser.parse(
      mapOf(
        "contract_version" to TEAM_BUNDLE_CONTRACT_VERSION,
        "bundle_id" to "team-bundle-foundation",
        "version" to "1.0.0",
        "channel" to "preview",
        "created_at" to "2026-07-08T00:00:00Z",
        "created_by" to "platform-team",
        "source_repo" to "skill-bill",
        "source_ref" to "main",
        "content_hash" to "sha256:bundle-content",
        "manifest_hashes" to mapOf("platform-packs/kotlin/platform.yaml" to "sha256:platform"),
        "bundle_checksum" to "sha256:bundle-checksum",
        "sources" to listOf(
          mapOf(
            "category" to "horizontal_skill",
            "path" to "skills/bill-code-check/content.md",
            "content_hash" to "sha256:source",
          ),
        ),
        "compatibility" to mapOf(
          "min_skill_bill_version" to "0.1.0",
          "shell_contract_version" to "1.2",
        ),
        "telemetry_defaults" to mapOf("enabled" to true, "level" to "anonymous"),
        "privacy_defaults" to mapOf(
          "telemetry" to "anonymous",
          "source_paths" to "anonymous",
          "author_identity" to "off",
        ),
        "exclusions" to mapOf("paths" to emptyList<String>(), "reasons" to emptyMap<String, String>()),
      ),
      "bundle.yaml",
    )

    assertEquals(TeamBundleChannel.PREVIEW, parsed.metadata.channel)
    assertEquals(TeamBundleSourceCategory.HORIZONTAL_SKILL, parsed.sources.single().category)
    assertEquals(TeamBundlePrivacyLevel.OFF, parsed.privacyDefaults.authorIdentity)
    assertNull(parsed.teamMetadata)
  }

  @Test
  fun `parser exposes optional team metadata when present`() {
    val parsed = TeamBundleParser.parse(
      minimalBundleMap() + ("team_metadata" to mapOf("team_id" to "platform", "name" to "Platform")),
      "bundle.yaml",
    )

    assertEquals(TeamBundleTeamMetadata(teamId = "platform", name = "Platform"), parsed.teamMetadata)
  }

  @Test
  fun `parser rejects malformed present team metadata with field path`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleParser.parse(minimalBundleMap() + ("team_metadata" to mapOf("team_id" to "platform")), "bundle.yaml")
    }

    assertEquals("team_metadata.name", error.fieldPath)
  }

  private fun minimalBundleMap(): Map<String, Any?> = mapOf(
    "contract_version" to TEAM_BUNDLE_CONTRACT_VERSION,
    "bundle_id" to "team-bundle-foundation",
    "version" to "1.0.0",
    "channel" to "preview",
    "created_at" to "2026-07-08T00:00:00Z",
    "created_by" to "platform-team",
    "source_repo" to "skill-bill",
    "source_ref" to "main",
    "content_hash" to "sha256:bundle-content",
    "manifest_hashes" to mapOf("platform-packs/kotlin/platform.yaml" to "sha256:platform"),
    "bundle_checksum" to "sha256:bundle-checksum",
    "sources" to listOf(
      mapOf(
        "category" to "horizontal_skill",
        "path" to "skills/bill-code-check/content.md",
        "content_hash" to "sha256:source",
      ),
    ),
    "compatibility" to mapOf(
      "min_skill_bill_version" to "0.1.0",
      "shell_contract_version" to "1.2",
    ),
    "telemetry_defaults" to mapOf("enabled" to true, "level" to "anonymous"),
    "privacy_defaults" to mapOf(
      "telemetry" to "anonymous",
      "source_paths" to "anonymous",
      "author_identity" to "off",
    ),
    "exclusions" to mapOf("paths" to emptyList<String>(), "reasons" to emptyMap<String, String>()),
  )
}
