package skillbill.application.system

import me.tatarka.inject.annotations.Inject
import skillbill.ports.system.UninstallPathsPort
import java.nio.file.Path

@Inject
class UninstallFileSystemService(
  private val gateway: UninstallPathsPort,
) {
  fun listImmediateDirectoryNames(root: Path): List<String> = gateway.listImmediateDirectoryNames(root)

  fun exists(path: Path): Boolean = gateway.exists(path)

  fun isSymbolicLink(path: Path): Boolean = gateway.isSymbolicLink(path)

  fun readSymbolicLink(path: Path): Path = gateway.readSymbolicLink(path)

  fun deleteIfExists(path: Path): Boolean = gateway.deleteIfExists(path)

  fun removeTree(path: Path): List<Path> = gateway.removeTree(path)
}
