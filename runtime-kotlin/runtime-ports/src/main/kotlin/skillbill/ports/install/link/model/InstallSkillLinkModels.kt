package skillbill.ports.install.link.model

import java.nio.file.Path

data class InstallSkillLinkRequest(
  val source: Path,
  val targetDir: Path,
  val agent: String,
  val repoRoot: Path?,
  val home: Path?,
)

data class InstallSkillLinkResult(
  val linkedPaths: List<Path>,
)
