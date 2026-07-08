package skillbill.team.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.install.model.InstallPlanRequest
import java.nio.file.Path

enum class TeamSyncSourceKind {
  BUNDLE_FILE,
  LOCAL_REGISTRY,
  ROLLBACK,
}

data class TeamSyncRequest(
  val bundlePath: Path? = null,
  val registryRoot: Path? = null,
  val channel: TeamBundleChannel? = null,
  val installRequest: InstallPlanRequest,
)

data class TeamRollbackRequest(
  val installRequest: InstallPlanRequest,
)

data class TeamStatusRequest(
  val home: Path,
)

data class TeamBundleVerificationSummary(
  val checksum: String,
  val contentHash: String,
  val sourceCount: Int,
  val manifestCount: Int,
  val sourceRoot: Path,
) {
  @OpenBoundaryMap("Team sync CLI JSON payload fragment")
  fun toPayload(): Map<String, Any?> = mapOf(
    "checksum" to checksum,
    "content_hash" to contentHash,
    "source_count" to sourceCount,
    "manifest_count" to manifestCount,
    "source_root" to sourceRoot.toString(),
  )
}

data class InstalledTeamBundleRecord(
  val bundleId: String,
  val version: String,
  val channel: TeamBundleChannel,
  val contentHash: String,
  val checksum: String,
  val sourceRef: String,
  val sourceRepo: String,
  val sourceCommit: String?,
  val installedAt: String,
  val archivePath: Path,
  val sourceKind: TeamSyncSourceKind,
  val previous: InstalledTeamBundleRecord? = null,
) {
  @OpenBoundaryMap("Team sync durable state and CLI JSON payload fragment")
  fun toPayload(includePrevious: Boolean = true): Map<String, Any?> {
    val payload = linkedMapOf<String, Any?>(
      "bundle_id" to bundleId,
      "version" to version,
      "channel" to channel.wireValue,
      "content_hash" to contentHash,
      "checksum" to checksum,
      "source_ref" to sourceRef,
      "source_repo" to sourceRepo,
      "source_commit" to sourceCommit,
      "installed_at" to installedAt,
      "archive_path" to archivePath.toString(),
      "source_kind" to sourceKind.name.lowercase(),
    )
    if (includePrevious) {
      payload["previous"] = previous?.toPayload(includePrevious = false)
    }
    return payload
  }
}

data class TeamSyncResult(
  val installed: InstalledTeamBundleRecord,
  val previous: InstalledTeamBundleRecord?,
  val verification: TeamBundleVerificationSummary,
  val rollbackRestored: Boolean = false,
) {
  @OpenBoundaryMap("Team sync CLI JSON payload")
  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to "synced",
    "installed" to installed.toPayload(),
    "previous" to previous?.toPayload(includePrevious = false),
    "verification" to verification.toPayload(),
    "rollback_restored" to rollbackRestored,
  )
}

data class TeamRollbackResult(
  val restored: InstalledTeamBundleRecord,
  val replaced: InstalledTeamBundleRecord,
  val verification: TeamBundleVerificationSummary,
) {
  @OpenBoundaryMap("Team rollback CLI JSON payload")
  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to "rolled_back",
    "restored" to restored.toPayload(),
    "replaced" to replaced.toPayload(includePrevious = false),
    "verification" to verification.toPayload(),
  )
}

data class TeamStatusResult(
  val installed: InstalledTeamBundleRecord?,
) {
  @OpenBoundaryMap("Team status CLI JSON payload")
  fun toPayload(): Map<String, Any?> = linkedMapOf(
    "status" to if (installed == null) "empty" else "installed",
    "installed" to installed?.toPayload(),
  )
}
