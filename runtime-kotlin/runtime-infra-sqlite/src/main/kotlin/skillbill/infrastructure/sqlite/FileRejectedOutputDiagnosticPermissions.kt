package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.RejectedOutputDiagnosticPermissions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class FileRejectedOutputDiagnosticPermissions(
  private val databasePath: Path,
) : RejectedOutputDiagnosticPermissions {
  override fun applyRestrictivePermissions() {
    databasePath.parent?.let { parent ->
      if (Files.exists(parent)) {
        Files.setPosixFilePermissions(
          parent,
          setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
          ),
        )
      }
    }
    if (Files.exists(databasePath)) {
      Files.setPosixFilePermissions(
        databasePath,
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      )
    }
  }
}
