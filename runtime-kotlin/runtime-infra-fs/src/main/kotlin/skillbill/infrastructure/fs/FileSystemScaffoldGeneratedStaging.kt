package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.SkillAlreadyExistsError
import skillbill.ports.scaffold.staging.ScaffoldGeneratedStagingPort
import skillbill.ports.scaffold.staging.model.ScaffoldStageFileRequest
import skillbill.ports.scaffold.staging.model.ScaffoldStageFileResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Filesystem adapter for [ScaffoldGeneratedStagingPort]. Owns the directory-creation /
 * file-write seam that the scaffold transaction uses to produce generated artifacts. Mirrors
 * the historical `stageFile` helper inside `ScaffoldService.kt` — refuses to clobber an
 * existing target and reports every directory it created so rollback can clean up precisely.
 *
 * The legacy scaffold pipeline still calls `stageFile` directly inside its rollback transaction
 * because reusing that transaction context here is out of scope for subtask 2.
 */
@Inject
class FileSystemScaffoldGeneratedStaging : ScaffoldGeneratedStagingPort {
  override fun stageFile(request: ScaffoldStageFileRequest): ScaffoldStageFileResult {
    val path = request.targetPath
    if (Files.exists(path)) {
      throw SkillAlreadyExistsError(
        "Skill target '$path' already exists. Remove it or pick a new name before retrying.",
      )
    }
    val createdDirs = mutableListOf<Path>()
    var cursor = path.parent
    while (cursor != null && !Files.exists(cursor)) {
      createdDirs.add(cursor)
      cursor = cursor.parent
    }
    createdDirs.asReversed().forEach { dir ->
      Files.createDirectories(dir)
    }
    Files.writeString(path, request.content)
    return ScaffoldStageFileResult(
      createdFile = path,
      createdDirectories = createdDirs.toList(),
    )
  }

  override fun rollbackFile(path: Path) {
    if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
      Files.deleteIfExists(path)
    }
  }

  override fun rollbackDirectory(path: Path) {
    if (Files.isDirectory(path) && Files.list(path).use { !it.findAny().isPresent }) {
      Files.deleteIfExists(path)
    }
  }
}
