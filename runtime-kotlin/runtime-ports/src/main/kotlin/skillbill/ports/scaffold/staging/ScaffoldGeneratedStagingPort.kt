package skillbill.ports.scaffold.staging

import skillbill.ports.scaffold.staging.model.ScaffoldStageFileRequest
import skillbill.ports.scaffold.staging.model.ScaffoldStageFileResult
import java.nio.file.Path

/**
 * Capability port for staging scaffold-generated artifact files (content.md sheets, native-agent
 * bundle stubs, etc.) atomically with rollback support. Pure-policy callers describe the file
 * they want written; the adapter owns directory creation and tracks every touched path.
 *
 * IO ownership stays in `runtime-infra-fs`.
 */
interface ScaffoldGeneratedStagingPort {
  /**
   * Stages a generated file at the requested target path. Throws if the target already exists
   * (the scaffold contract refuses to clobber existing skill files).
   */
  fun stageFile(request: ScaffoldStageFileRequest): ScaffoldStageFileResult

  /**
   * Deletes a previously staged file as part of rollback. Idempotent: missing files are tolerated.
   */
  fun rollbackFile(path: Path)

  /**
   * Removes an empty directory that was created during staging. Idempotent: non-empty or missing
   * directories are tolerated.
   */
  fun rollbackDirectory(path: Path)
}
