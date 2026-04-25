package skillbill.install.model

import java.nio.file.Path

data class AgentTarget(
  val name: String,
  val path: Path,
)

data class InstallTransaction(
  val createdSymlinks: MutableList<Path> = mutableListOf(),
)
