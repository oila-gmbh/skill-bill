@file:Suppress("LongParameterList")

package skillbill.contracts.team

import skillbill.error.InvalidTeamBundleSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamBundleSchemaValidatorTest {
  @Test
  fun `minimal valid bundle validates and returns parsed map`() {
    val parsed = TeamBundleSchemaValidator.validateYamlText(validBundleYaml(), "valid.yaml")

    assertEquals("team-bundle-foundation", parsed["bundle_id"])
    assertEquals(null, parsed["team_metadata"])
  }

  @Test
  fun `optional team metadata validates when present`() {
    val parsed = TeamBundleSchemaValidator.validateYamlText(validBundleYaml(includeTeamMetadata = true), "valid.yaml")

    assertEquals(mapOf("team_id" to "platform", "name" to "Platform", "description" to null), parsed["team_metadata"])
  }

  @Test
  fun `malformed present team metadata fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(
        validBundleYaml(teamMetadataLines = listOf("team_metadata:", "  team_id: platform")),
        "bad-team-metadata.yaml",
      )
    }

    assertEquals("team_metadata", error.fieldPath)
  }

  @Test
  fun `malformed YAML fails with typed schema error`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText("contract_version: [", "malformed.yaml")
    }

    assertContains(error.reason, "YAML is malformed")
    assertEquals("<root>", error.fieldPath)
  }

  @Test
  fun `non object YAML fails with typed schema error`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText("- contract_version: 0.1", "array.yaml")
    }

    assertContains(error.reason, "<root> must be an object")
  }

  @Test
  fun `unknown top-level field fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(validBundleYaml(extraTopLevel = "unexpected: true"), "unknown.yaml")
    }

    assertEquals("<root>", error.fieldPath)
  }

  @Test
  fun `unknown nested field fails`() {
    val yaml = validBundleYaml(extraPrivacyLine = "  unexpected: true")

    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(yaml, "unknown-nested.yaml")
    }

    assertContains(error.fieldPath, "privacy_defaults")
  }

  @Test
  fun `missing required source hash fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(validBundleYaml(includeSourceHash = false), "missing-hash.yaml")
    }

    assertContains(error.fieldPath, "sources")
  }

  @Test
  fun `invalid channel fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(validBundleYaml(channel = "nightly"), "bad-channel.yaml")
    }

    assertEquals("channel", error.fieldPath)
  }

  @Test
  fun `invalid privacy default fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(validBundleYaml(privacyTelemetry = "private"), "bad-privacy.yaml")
    }

    assertEquals("privacy_defaults.telemetry", error.fieldPath)
  }

  @Test
  fun `stale contract version fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(validBundleYaml(contractVersion = "0.0"), "stale.yaml")
    }

    assertEquals("contract_version", error.fieldPath)
  }

  @Test
  fun `missing source entries fails`() {
    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSchemaValidator.validateYamlText(validBundleYaml(includeSources = false), "missing-sources.yaml")
    }

    assertEquals("<root>", error.fieldPath)
  }
}

internal fun validBundleYaml(
  contractVersion: String = TEAM_BUNDLE_CONTRACT_VERSION,
  channel: String = "stable",
  privacyTelemetry: String = "anonymous",
  includeSourceHash: Boolean = true,
  includeSources: Boolean = true,
  includeTeamMetadata: Boolean = false,
  teamMetadataLines: List<String>? = null,
  extraTopLevel: String? = null,
  extraPrivacyLine: String? = null,
): String {
  val lines = mutableListOf(
    "contract_version: \"$contractVersion\"",
    "bundle_id: team-bundle-foundation",
    "version: \"1.0.0\"",
    "channel: $channel",
    "created_at: \"2026-07-08T00:00:00Z\"",
    "created_by: platform-team",
    "source_repo: skill-bill",
    "source_ref: main",
    "source_commit: null",
    "content_hash: sha256:bundle-content",
    "manifest_hashes:",
    "  platform-packs/kotlin/platform.yaml: sha256:platform",
    "bundle_checksum: sha256:bundle-checksum",
  )
  lines += sourceLines(includeSources, includeSourceHash)
  lines += defaultLines(privacyTelemetry, extraPrivacyLine)
  lines += teamMetadataLines(teamMetadataLines, includeTeamMetadata)
  lines += listOf(
    "exclusions:",
    "  paths: []",
    "  reasons: {}",
  )
  if (extraTopLevel != null) {
    lines += extraTopLevel
  }
  return lines.joinToString("\n")
}

private fun sourceLines(includeSources: Boolean, includeSourceHash: Boolean): List<String> {
  if (!includeSources) {
    return emptyList()
  }

  return buildList {
    add("sources:")
    add("  - category: horizontal_skill")
    add("    path: skills/bill-code-check/content.md")
    if (includeSourceHash) {
      add("    content_hash: sha256:source")
    }
    add("    manifest_hash_key: null")
  }
}

private fun defaultLines(privacyTelemetry: String, extraPrivacyLine: String?): List<String> = buildList {
  addAll(
    listOf(
      "compatibility:",
      "  min_skill_bill_version: \"0.1.0\"",
      "  max_skill_bill_version: null",
      "  shell_contract_version: \"1.2\"",
      "  platform_pack_contract_version: null",
      "telemetry_defaults:",
      "  enabled: true",
      "  level: anonymous",
      "privacy_defaults:",
      "  telemetry: $privacyTelemetry",
      "  source_paths: anonymous",
      "  author_identity: \"off\"",
    ),
  )
  if (extraPrivacyLine != null) {
    add(extraPrivacyLine)
  }
}

private fun teamMetadataLines(teamMetadataLines: List<String>?, includeTeamMetadata: Boolean): List<String> = when {
  teamMetadataLines != null -> teamMetadataLines
  includeTeamMetadata -> listOf(
    "team_metadata:",
    "  team_id: platform",
    "  name: Platform",
    "  description: null",
  )
  else -> emptyList()
}
