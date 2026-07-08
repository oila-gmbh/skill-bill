package skillbill.ports.team.model

import skillbill.team.model.TeamBundleSourceCategory
import java.nio.file.Path

data class TeamExportCollectedSource(
  val category: TeamBundleSourceCategory,
  val path: String,
  val contentHash: String,
)

data class TeamExportRegistryPublishRequest(
  val registryRoot: Path,
  val channel: String,
  val version: String,
  val bundleId: String,
  val archive: ByteArray,
  val metadataJson: String,
  val checksum: String,
  val failAfterTempWrite: Boolean,
)
