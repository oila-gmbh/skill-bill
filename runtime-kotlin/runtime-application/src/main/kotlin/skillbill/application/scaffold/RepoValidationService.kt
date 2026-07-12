package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.repoRoot
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationReport
import java.nio.file.Path

@Inject
class RepoValidationService(
  private val gateway: RepoValidationGateway,
) {
  fun validateRepo(repoRoot: Path): RepoValidationReport = gateway.validateRepo(repoRoot)

  fun validateReleaseRef(repoRoot: Path, rawRef: String, forcePrerelease: Boolean): ReleaseRefMetadata =
    gateway.validateReleaseRef(repoRoot, rawRef, forcePrerelease)

  fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) =
    gateway.appendGithubOutput(outputPath, metadata)
}
