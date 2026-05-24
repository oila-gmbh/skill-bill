package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.validation.RepoValidationGateway
import skillbill.scaffold.ReleaseRefMetadata
import skillbill.scaffold.RepoValidationIssue
import skillbill.scaffold.RepoValidationIssueSeverity
import skillbill.scaffold.RepoValidationReport
import skillbill.scaffold.RepoValidationRuntime
import java.nio.file.Path
import skillbill.ports.validation.model.ReleaseRefMetadata as PortReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationIssue as PortRepoValidationIssue
import skillbill.ports.validation.model.RepoValidationIssueSeverity as PortRepoValidationIssueSeverity
import skillbill.ports.validation.model.RepoValidationReport as PortRepoValidationReport

@Inject
class FileSystemRepoValidationGateway : RepoValidationGateway {
  override fun validateRepo(repoRoot: Path): PortRepoValidationReport =
    RepoValidationRuntime.validateRepo(repoRoot).toPortReport()

  override fun parseReleaseRef(rawRef: String): PortReleaseRefMetadata =
    RepoValidationRuntime.parseReleaseRef(rawRef).toPortMetadata()

  override fun appendGithubOutput(outputPath: Path, metadata: PortReleaseRefMetadata) =
    RepoValidationRuntime.appendGithubOutput(
      outputPath,
      ReleaseRefMetadata(tag = metadata.tag, version = metadata.version, prerelease = metadata.prerelease),
    )

  private fun RepoValidationReport.toPortReport(): PortRepoValidationReport = PortRepoValidationReport(
    issues = issues,
    skillCount = skillCount,
    addonCount = addonCount,
    platformPackCount = platformPackCount,
    nativeAgentCount = nativeAgentCount,
    structuredIssues = structuredIssues.map { issue -> issue.toPortIssue() },
  )

  private fun RepoValidationIssue.toPortIssue(): PortRepoValidationIssue = PortRepoValidationIssue(
    severity = severity.toPortSeverity(),
    message = message,
    sourcePath = sourcePath,
    code = code,
    name = name,
    exceptionName = exceptionName,
  )

  private fun RepoValidationIssueSeverity.toPortSeverity(): PortRepoValidationIssueSeverity = when (this) {
    RepoValidationIssueSeverity.ERROR -> PortRepoValidationIssueSeverity.ERROR
    RepoValidationIssueSeverity.WARNING -> PortRepoValidationIssueSeverity.WARNING
    RepoValidationIssueSeverity.INFO -> PortRepoValidationIssueSeverity.INFO
  }

  private fun ReleaseRefMetadata.toPortMetadata(): PortReleaseRefMetadata =
    PortReleaseRefMetadata(tag = tag, version = version, prerelease = prerelease)
}
