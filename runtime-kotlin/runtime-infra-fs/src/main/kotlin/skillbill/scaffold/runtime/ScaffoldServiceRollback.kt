@file:Suppress("TooGenericExceptionCaught", "MaxLineLength")

package skillbill.scaffold.runtime

import skillbill.error.ScaffoldRollbackError
import skillbill.install.plan.uninstallTargets
import java.nio.file.Files
import java.nio.file.Path

internal fun rollback(txn: ScaffoldTransaction) {
  val errors = mutableListOf<String>()
  rollbackInstallTargets(txn, errors)
  rollbackSymlinks(txn, errors)
  rollbackManifests(txn, errors)
  rollbackFiles(txn, errors)
  rollbackDirs(txn, errors)
  if (errors.isNotEmpty()) {
    throw ScaffoldRollbackError(
      "Rollback encountered errors while reverting scaffold: ${errors.joinToString("; ")}",
    )
  }
}

internal fun rollbackInstallTargets(txn: ScaffoldTransaction, errors: MutableList<String>) {
  try {
    uninstallTargets(txn.installTargets)
  } catch (error: Exception) {
    errors += "install rollback: ${error.message}"
  }
}

internal fun rollbackSymlinks(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (link in txn.createdSymlinks.asReversed()) {
    try {
      if (Files.isSymbolicLink(link) || Files.exists(link)) {
        Files.deleteIfExists(link)
      }
    } catch (error: Exception) {
      errors += "symlink $link: ${error.message}"
    }
  }
}

internal fun rollbackManifests(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (snapshot in txn.manifestSnapshots.asReversed()) {
    try {
      Files.write(snapshot.manifestPath, snapshot.originalBytes)
    } catch (error: Exception) {
      errors += "manifest ${snapshot.manifestPath}: ${error.message}"
    }
  }
}

internal fun rollbackFiles(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (path in txn.createdPaths.asReversed()) {
    try {
      if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
        Files.deleteIfExists(path)
      }
    } catch (error: Exception) {
      errors.add("file $path: ${error.message}")
    }
  }
}

internal fun rollbackDirs(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (directory in txn.createdDirs.asReversed()) {
    try {
      if (Files.isDirectory(directory) && Files.list(directory).use { !it.findAny().isPresent }) {
        Files.deleteIfExists(directory)
      }
    } catch (error: Exception) {
      errors.add("dir $directory: ${error.message}")
    }
  }
}

internal const val ADD_ON_INSTALL_NOTE: String =
  "Add-on shipped as a supporting asset of its owning platform package; auto-install does not apply."

internal const val PLATFORM_PACK_INSTALL_NOTE: String =
  "Auto-installed the generated platform-pack skills into detected local agents."

internal fun platformPackManifestPath(repoRoot: Path, platform: String): Path =
  repoRoot.resolve("platform-packs").resolve(platform).resolve("platform.yaml")
