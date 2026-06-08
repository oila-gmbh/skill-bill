package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.SpecScratchStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Filesystem adapter for [SpecScratchStore]. Deletes linear-mode spec scratch idempotently:
 * a missing file or directory is a silent no-op so a re-run after partial deletion is safe.
 */
@Inject
class FileSystemSpecScratchStore : SpecScratchStore {
  override fun deleteFileIfExists(path: Path) {
    Files.deleteIfExists(path)
  }

  override fun deleteDirectoryIfExists(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { paths ->
      paths
        .sorted(Comparator.reverseOrder())
        .forEach(Files::deleteIfExists)
    }
  }
}
