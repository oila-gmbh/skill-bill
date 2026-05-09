package skillbill.install.model

import java.nio.file.Path

data class AgentTarget(
  val name: String,
  val path: Path,
)

data class InstallTransaction(
  val createdSymlinks: MutableList<Path> = mutableListOf(),
)

data class McpMutationResult(
  val agent: String,
  val configPath: Path,
  val changed: Boolean,
)

/**
 * Materialized staging directory for an installed skill.
 *
 * SKILL-40 subtask 2 stages skill installs into a content-addressable cache outside the repo
 * (`~/.skill-bill/installed-skills/<slug>-<hash>/`) so the source tree stays read-only. This DTO
 * exposes the staging layout so callers (install primitives, tests) can assert what was rendered.
 */
data class RenderedSkill(
  val skillName: String,
  val sourceSkillDir: Path,
  val stagingDir: Path,
  val renderedSkillFile: Path,
  val renderedPointerFiles: List<Path>,
  val copiedAuthoredFiles: List<Path>,
  val contentHash: String,
)
