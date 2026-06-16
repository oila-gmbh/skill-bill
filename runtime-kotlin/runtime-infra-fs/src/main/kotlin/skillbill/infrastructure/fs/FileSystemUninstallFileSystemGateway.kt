package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.system.UninstallFileSystemGateway
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Inject
class FileSystemUninstallFileSystemGateway : UninstallFileSystemGateway {
  override fun listImmediateDirectoryNames(root: Path): List<String> {
    if (!Files.isDirectory(root)) {
      return emptyList()
    }
    return Files.newDirectoryStream(root).use { entries ->
      entries.map { entry -> entry.fileName.toString() }
    }
  }

  override fun exists(path: Path): Boolean = Files.exists(path)

  override fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)

  override fun readSymbolicLink(path: Path): Path = Files.readSymbolicLink(path)

  override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

  override fun removeTree(path: Path): List<Path> {
    val removed = mutableListOf<Path>()
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach { entry ->
        if (Files.deleteIfExists(entry)) {
          removed.add(entry)
        }
      }
    }
    return removed
  }
}
