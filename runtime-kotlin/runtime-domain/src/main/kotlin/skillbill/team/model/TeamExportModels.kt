package skillbill.team.model

import java.nio.file.Path

data class TeamExportRequest(
  val repoRoot: Path,
  val version: String,
  val channel: TeamBundleChannel,
  val outputPath: Path? = null,
  val registryRoot: Path? = null,
  val dryRun: Boolean = false,
  val createdAt: String,
  val createdBy: String,
  val sourceRepo: String,
  val sourceRef: String,
  val sourceCommit: String? = null,
  val minSkillBillVersion: String = "0.1",
  val shellContractVersion: String = "1.2",
  val platformPackContractVersion: String? = "1.2",
  val failAfterRegistryTempWrite: Boolean = false,
)

data class TeamExportSourceEntryHash(
  val path: String,
  val contentHash: String,
)

data class TeamExportValidationSummary(
  val status: String,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
) {
  fun toPayload(): Map<String, Any?> = mapOf(
    "status" to status,
    "skill_count" to skillCount,
    "governed_addon_count" to addonCount,
    "platform_pack_count" to platformPackCount,
    "native_agent_count" to nativeAgentCount,
  )
}

data class TeamExportRegistryDestination(
  val path: Path,
  val channel: String,
  val version: String,
  val bundleId: String,
) {
  fun toPayload(): Map<String, Any?> = mapOf(
    "path" to path.toString(),
    "channel" to channel,
    "version" to version,
    "bundle_id" to bundleId,
  )
}

data class TeamExportResult(
  val bundlePath: Path?,
  val bundleId: String,
  val version: String,
  val channel: TeamBundleChannel,
  val contentHash: String,
  val checksum: String,
  val sourceRef: String,
  val validationSummary: TeamExportValidationSummary,
  val registryDestination: TeamExportRegistryDestination? = null,
  val sourceEntryHashes: List<TeamExportSourceEntryHash>,
) {
  fun toPayload(): Map<String, Any?> {
    val payload = linkedMapOf<String, Any?>(
      "bundle_path" to bundlePath?.toString(),
      "bundle_id" to bundleId,
      "version" to version,
      "channel" to channel.wireValue,
      "content_hash" to contentHash,
      "checksum" to checksum,
      "source_ref" to sourceRef,
      "validation_summary" to validationSummary.toPayload(),
    )
    if (registryDestination != null) {
      payload["registry_destination"] = registryDestination.toPayload()
    }
    return payload
  }
}
