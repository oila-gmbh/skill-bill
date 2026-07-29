package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.RejectedOutputDiagnostic
import skillbill.ports.persistence.model.RejectedOutputDiagnosticError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun validateRejectedOutputPayloadFile(path: Path, metadata: RejectedOutputDiagnostic) {
  val size = readPayloadFileSize(path)
  if (size != metadata.byteSize) {
    throw RejectedOutputDiagnosticError.Persistence("captured-output-size-mismatch")
  }
  if (readPayloadFileDigest(path) != metadata.sha256) {
    throw RejectedOutputDiagnosticError.Persistence("captured-output-digest-mismatch")
  }
}

private fun readPayloadFileSize(path: Path): Long = try {
  Files.size(path)
} catch (error: IOException) {
  throw RejectedOutputDiagnosticError.Persistence("read-captured-output", error)
}

private fun readPayloadFileDigest(path: Path): String = try {
  Files.newInputStream(path).use { input ->
    val checksum = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(FILE_DIGEST_BUFFER_BYTES)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      checksum.update(buffer, 0, read)
    }
    checksum.digest().joinToString("") { "%02x".format(it) }
  }
} catch (error: IOException) {
  throw RejectedOutputDiagnosticError.Persistence("read-captured-output", error)
}

private const val FILE_DIGEST_BUFFER_BYTES = 65_536
