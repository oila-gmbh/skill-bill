package skillbill.ports.config.model

import skillbill.config.model.RepoLocalConfig
import java.nio.file.Path

/**
 * The caller supplies the repository root as an inert [Path]; the adapter resolves
 * `.skill-bill/config.yaml` beneath it. No CWD/env reads cross this boundary.
 */
data class ReadRepoLocalConfigRequest(
  val repoRoot: Path,
)

data class ReadRepoLocalConfigResult(
  val config: RepoLocalConfig,
)
