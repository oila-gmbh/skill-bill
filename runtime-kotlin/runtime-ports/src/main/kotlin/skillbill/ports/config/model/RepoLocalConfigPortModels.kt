package skillbill.ports.config.model

import skillbill.config.model.RepoLocalConfig
import java.nio.file.Path

data class ReadRepoLocalConfigRequest(
  val repoRoot: Path,
)

data class ReadRepoLocalConfigResult(
  val config: RepoLocalConfig,
)
