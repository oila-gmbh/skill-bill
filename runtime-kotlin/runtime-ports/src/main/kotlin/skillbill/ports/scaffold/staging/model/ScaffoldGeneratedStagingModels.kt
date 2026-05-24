package skillbill.ports.scaffold.staging.model

import java.nio.file.Path

/**
 * Request to stage a generated artifact at [targetPath] with [content]. Adapters create any
 * required parent directories and record both for rollback.
 */
data class ScaffoldStageFileRequest(
  val targetPath: Path,
  val content: String,
)

/**
 * Result describing what the adapter actually touched for a single stage call so the scaffold
 * transaction can roll back precisely.
 */
data class ScaffoldStageFileResult(
  val createdFile: Path,
  val createdDirectories: List<Path>,
)
