package skillbill.ports.scaffold.install.model

import java.nio.file.Path

/**
 * Request to install one scaffolded skill (or every install-path of a freshly scaffolded
 * platform pack) into detected local agents.
 */
data class ScaffoldInstallLinkRequest(
  val repoRoot: Path,
  val installPaths: List<Path>,
)

/**
 * Result of an install link operation: the agent target paths that received symlinks. The
 * scaffold transaction records these so rollback can remove them.
 */
data class ScaffoldInstallLinkResult(
  val installTargets: List<Path>,
)
