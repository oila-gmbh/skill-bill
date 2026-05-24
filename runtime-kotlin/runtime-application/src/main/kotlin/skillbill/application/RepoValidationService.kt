package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationReport
import java.nio.file.Path

@Inject
class RepoValidationService(
  private val gateway: RepoValidationGateway,
) {
  fun validateRepo(repoRoot: Path): RepoValidationReport = gateway.validateRepo(repoRoot)

  fun parseReleaseRef(rawRef: String): ReleaseRefMetadata = gateway.parseReleaseRef(rawRef)

  fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) =
    gateway.appendGithubOutput(outputPath, metadata)
}
