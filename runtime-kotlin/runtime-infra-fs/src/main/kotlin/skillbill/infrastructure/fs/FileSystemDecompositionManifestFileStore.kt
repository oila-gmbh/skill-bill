package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.DecompositionManifestFileStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Inject
class FileSystemDecompositionManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = Files.readString(path)

  override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

  override fun writeTextAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
    Files.writeString(temp, content)
    try {
      Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, target, REPLACE_EXISTING)
    }
  }
}
