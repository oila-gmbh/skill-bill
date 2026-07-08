package skillbill.team.model

import skillbill.contracts.team.TEAM_BUNDLE_CONTRACT_VERSION

enum class TeamBundleChannel(val wireValue: String) {
  STABLE("stable"),
  PREVIEW("preview"),
  EXPERIMENTAL("experimental"),
  ;

  companion object {
    fun fromWireValue(value: String): TeamBundleChannel? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class TeamBundlePrivacyLevel(val wireValue: String) {
  OFF("off"),
  ANONYMOUS("anonymous"),
  FULL("full"),
  ;

  companion object {
    fun fromWireValue(value: String): TeamBundlePrivacyLevel? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class TeamBundleSourceCategory(val wireValue: String) {
  HORIZONTAL_SKILL("horizontal_skill"),
  PLATFORM_PACK("platform_pack"),
  ADDON("addon"),
  PLATFORM_OVERRIDE("platform_override"),
  NATIVE_AGENT_SOURCE("native_agent_source"),
  ORCHESTRATION_CONTRACT_OR_SUPPORT("orchestration_contract_or_support"),
  ;

  companion object {
    fun fromWireValue(value: String): TeamBundleSourceCategory? = entries.firstOrNull { it.wireValue == value }
  }
}

data class TeamBundleHashes(
  val contentHash: String,
  val manifestHashes: Map<String, String>,
  val bundleChecksum: String,
)

data class TeamBundleSourceEntry(
  val category: TeamBundleSourceCategory,
  val path: String,
  val contentHash: String,
  val manifestHashKey: String? = null,
)

data class TeamBundleCompatibility(
  val minSkillBillVersion: String,
  val maxSkillBillVersion: String? = null,
  val shellContractVersion: String,
  val platformPackContractVersion: String? = null,
)

data class TeamBundleTelemetryDefaults(
  val enabled: Boolean,
  val level: TeamBundlePrivacyLevel,
)

data class TeamBundlePrivacyDefaults(
  val telemetry: TeamBundlePrivacyLevel,
  val sourcePaths: TeamBundlePrivacyLevel,
  val authorIdentity: TeamBundlePrivacyLevel,
)

data class TeamBundleTeamMetadata(
  val teamId: String,
  val name: String,
  val description: String? = null,
)

data class TeamBundleExclusions(
  val paths: List<String>,
  val reasons: Map<String, String>,
)

data class TeamBundleMetadata(
  val bundleId: String,
  val version: String,
  val channel: TeamBundleChannel,
  val createdAt: String,
  val createdBy: String,
  val sourceRepo: String,
  val sourceRef: String,
  val sourceCommit: String? = null,
)

data class TeamBundle(
  val contractVersion: String = TEAM_BUNDLE_CONTRACT_VERSION,
  val metadata: TeamBundleMetadata,
  val hashes: TeamBundleHashes,
  val sources: List<TeamBundleSourceEntry>,
  val compatibility: TeamBundleCompatibility,
  val telemetryDefaults: TeamBundleTelemetryDefaults,
  val privacyDefaults: TeamBundlePrivacyDefaults,
  val teamMetadata: TeamBundleTeamMetadata? = null,
  val exclusions: TeamBundleExclusions,
)
