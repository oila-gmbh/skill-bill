
package skillbill.scaffold.runtime

import skillbill.error.ScaffoldRollbackError
import skillbill.install.plan.uninstallTargets
import java.io.IOException
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
  recordRollbackFailure(errors, "install rollback") {
    uninstallTargets(txn.installTargets)
  }
}

internal fun rollbackSymlinks(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (link in txn.createdSymlinks.asReversed()) {
    recordRollbackFailure(errors, "symlink $link") {
      if (Files.isSymbolicLink(link) || Files.exists(link)) {
        Files.deleteIfExists(link)
      }
    }
  }
}

internal fun rollbackManifests(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (snapshot in txn.manifestSnapshots.asReversed()) {
    recordRollbackFailure(errors, "manifest ${snapshot.manifestPath}") {
      Files.write(snapshot.manifestPath, snapshot.originalBytes)
    }
  }
}

internal fun rollbackFiles(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (path in txn.createdPaths.asReversed()) {
    recordRollbackFailure(errors, "file $path") {
      if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
        Files.deleteIfExists(path)
      }
    }
  }
}

internal fun rollbackDirs(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (directory in txn.createdDirs.asReversed()) {
    recordRollbackFailure(errors, "dir $directory") {
      if (Files.isDirectory(directory) && Files.list(directory).use { !it.findAny().isPresent }) {
        Files.deleteIfExists(directory)
      }
    }
  }
}

private fun recordRollbackFailure(errors: MutableList<String>, label: String, action: () -> Unit) {
  try {
    action()
  } catch (error: IOException) {
    errors += "$label: ${error.message}"
  } catch (error: IllegalStateException) {
    errors += "$label: ${error.message}"
  }
}

internal const val ADD_ON_INSTALL_NOTE: String =
  "Add-on shipped as a supporting asset of its owning platform package; auto-install does not apply."

internal const val PLATFORM_PACK_INSTALL_NOTE: String =
  "Auto-installed the generated platform-pack skills into detected local agents."

internal fun platformPackManifestPath(repoRoot: Path, platform: String): Path =
  repoRoot.resolve("platform-packs").resolve(platform).resolve("platform.yaml")
