package skillbill.ports.system

import java.nio.file.Path

interface UninstallFileSystemGateway {
  fun listImmediateDirectoryNames(root: Path): List<String>

  fun exists(path: Path): Boolean

  fun isSymbolicLink(path: Path): Boolean

  fun readSymbolicLink(path: Path): Path

  fun deleteIfExists(path: Path): Boolean

  fun removeTree(path: Path): List<Path>
}
