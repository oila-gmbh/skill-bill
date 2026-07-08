package skillbill.ports.team

import skillbill.ports.team.model.TeamExportCollectedSource
import skillbill.ports.team.model.TeamExportRegistryPublishRequest
import skillbill.team.model.TeamExportRegistryDestination
import java.nio.file.Path

interface TeamExportFileGateway {
  fun collectSources(repoRoot: Path): List<TeamExportCollectedSource>

  fun archiveBytes(metadataJson: String, sources: List<TeamExportCollectedSource>, repoRoot: Path): ByteArray

  fun writeDirectBundle(path: Path, archive: ByteArray, metadataJson: String, checksum: String)

  fun publishRegistry(request: TeamExportRegistryPublishRequest): TeamExportRegistryDestination
}

class TeamExportFileGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
