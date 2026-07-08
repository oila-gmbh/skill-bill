package skillbill.ports.team.model

import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleChannel
import java.nio.file.Path

data class TeamBundleCandidate(
  val archivePath: Path,
  val metadata: Map<String, Any?>,
  val bundle: TeamBundle,
  val checksum: String,
)

data class TeamBundleExtractionRequest(
  val archivePath: Path,
  val bundle: TeamBundle,
  val candidateRoot: Path,
)

data class TeamRegistryResolveRequest(
  val registryRoot: Path,
  val channel: TeamBundleChannel,
)

data class TeamBundleStateReadRequest(
  val home: Path,
)

data class TeamBundleStateWriteRequest(
  val home: Path,
  val record: skillbill.team.model.InstalledTeamBundleRecord,
)
