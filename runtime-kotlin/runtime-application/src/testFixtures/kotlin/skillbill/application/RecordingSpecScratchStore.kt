package skillbill.application

import skillbill.ports.workflow.specscratch.SpecScratchStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class RecordingSpecScratchStore(
  private val performRealDeletion: Boolean = true,
) : SpecScratchStore {
  val deletedFiles: MutableList<Path> = mutableListOf()
  val deletedDirectories: MutableList<Path> = mutableListOf()
  val deletions: MutableList<Path> = mutableListOf()

  override fun deleteFileIfExists(path: Path) {
    deletedFiles.add(path)
    deletions.add(path)
    if (performRealDeletion) {
      Files.deleteIfExists(path)
    }
  }

  override fun deleteDirectoryIfExists(directory: Path) {
    deletedDirectories.add(directory)
    deletions.add(directory)
    if (performRealDeletion && Files.exists(directory)) {
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }
}
