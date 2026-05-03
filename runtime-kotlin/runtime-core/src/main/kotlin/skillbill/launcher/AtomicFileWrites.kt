package skillbill.launcher

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun atomicWriteString(path: Path, text: String) {
  val parent = path.parent
  if (parent != null) {
    Files.createDirectories(parent)
  }
  val tempDir = parent ?: Path.of(".")
  val temp = Files.createTempFile(tempDir, "${path.fileName}.", ".tmp")
  try {
    Files.writeString(temp, text)
    try {
      Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, path, REPLACE_EXISTING)
    }
  } finally {
    Files.deleteIfExists(temp)
  }
}
