@file:Suppress("TooGenericExceptionCaught")

package skillbill.install.staging

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

private val stagingSupportLog: Logger = Logger.getLogger("skillbill.install.InstallStagingIO")

internal fun writeInternalStagingFile(tempDir: Path, name: String, bytes: ByteArray): Path {
  val file = tempDir.resolve(name).normalize()
  require(file.parent == tempDir.toAbsolutePath().normalize()) {
    "Internal sidecar '$name' staging path '$file' escapes staging dir '$tempDir'."
  }
  Files.write(file, bytes)
  return file
}

internal fun promoteByBackupAndMove(tempDir: Path, finalStagingDir: Path) {
  val backup = finalStagingDir.resolveSibling(".${finalStagingDir.fileName}.backup-${UUID.randomUUID()}")
  moveWithAtomicFallback(finalStagingDir, backup)
  try {
    Files.move(tempDir, finalStagingDir, StandardCopyOption.REPLACE_EXISTING)
  } catch (error: IOException) {
    restoreInstallStagingBackup(backup, finalStagingDir, error)
    throw error
  } catch (error: RuntimeException) {
    restoreInstallStagingBackup(backup, finalStagingDir, error)
    throw error
  }
  suppressedDelete(backup)
}

internal fun moveWithAtomicFallback(source: Path, target: Path) {
  try {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, target)
  }
}

internal fun restoreInstallStagingBackup(backup: Path, finalStagingDir: Path, primaryError: Throwable) {
  suppressedDelete(finalStagingDir)
  try {
    moveWithAtomicFallback(backup, finalStagingDir)
  } catch (restoreError: Exception) {
    primaryError.addSuppressed(restoreError)
    stagingSupportLog.log(Level.SEVERE, "Failed to restore install staging backup '$backup'.", restoreError)
  }
}

internal fun suppressedDelete(path: Path) {
  if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  try {
    deleteInstallStagingDirectory(path)
  } catch (error: IOException) {
    stagingSupportLog.log(Level.WARNING, "suppressedDelete failed path=$path (cleanup error suppressed)", error)
  } catch (error: RuntimeException) {
    stagingSupportLog.log(Level.WARNING, "suppressedDelete failed path=$path (cleanup error suppressed)", error)
  }
}
