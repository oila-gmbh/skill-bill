package skillbill.ports.team

import skillbill.ports.team.model.TeamBundleCandidate
import skillbill.ports.team.model.TeamBundleExtractionRequest
import skillbill.ports.team.model.TeamBundleStateReadRequest
import skillbill.ports.team.model.TeamBundleStateWriteRequest
import skillbill.ports.team.model.TeamRegistryResolveRequest
import skillbill.team.model.InstalledTeamBundleRecord
import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleVerificationSummary
import java.nio.file.Path

interface TeamBundleArchiveGateway {
  fun readBundle(path: Path): TeamBundleCandidate

  fun extractCandidate(request: TeamBundleExtractionRequest): Path

  fun verifyExtractedSources(bundle: TeamBundle, sourceRoot: Path): TeamBundleVerificationSummary

  fun cacheBundle(source: Path, home: Path, bundleId: String, checksum: String): Path
}

interface TeamBundleRegistryResolver {
  fun resolveLatest(request: TeamRegistryResolveRequest): TeamBundleCandidate
}

interface TeamBundleStatePersistence {
  fun read(request: TeamBundleStateReadRequest): InstalledTeamBundleRecord?

  fun write(request: TeamBundleStateWriteRequest)
}

class TeamBundleGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
