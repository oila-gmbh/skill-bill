package skillbill.install.model

import java.nio.file.Path

data class ExternalAddonSource(
  val path: Path,
  val platform: String,
)

data class ExternalAgentAddonSource(
  val path: Path,
)
