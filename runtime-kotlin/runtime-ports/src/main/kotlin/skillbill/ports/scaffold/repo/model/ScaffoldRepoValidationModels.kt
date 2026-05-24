package skillbill.ports.scaffold.repo.model

import java.nio.file.Path

/**
 * Request to validate the post-scaffold state of a single authoring target (`content.md`,
 * generated `SKILL.md`, etc.) against the governed skill contract.
 */
data class ScaffoldAuthoringValidationRequest(
  val repoRoot: Path,
  val skillName: String,
  val packageName: String,
  val platform: String,
  val displayName: String,
  val family: String,
  val area: String,
  val skillFile: Path,
  val contentFile: Path,
)

/**
 * Validation result. An empty [issues] list means the target passes the governed-skill checks.
 */
data class ScaffoldAuthoringValidationResult(
  val issues: List<String>,
)
