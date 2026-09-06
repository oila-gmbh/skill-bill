package skillbill.ports.featurespec.model

import skillbill.workflow.decomposition.model.IssueKey
import java.nio.file.Path

data class FeatureSpecPathResolveInput(
  val issueKey: IssueKey,
  val explicitSpecPath: String?,
  val repoRoot: Path,
)

sealed interface FeatureSpecPathResolveResult {
  val specPath: String?

  data class Explicit(override val specPath: String) : FeatureSpecPathResolveResult
  data class SingleMatch(override val specPath: String) : FeatureSpecPathResolveResult
  data class NoMatch(val issueKey: IssueKey, val specsRoot: Path) : FeatureSpecPathResolveResult {
    override val specPath: String? = null
  }

  data class Ambiguous(val issueKey: IssueKey, val matches: List<Path>) : FeatureSpecPathResolveResult {
    override val specPath: String? = null
  }
}
