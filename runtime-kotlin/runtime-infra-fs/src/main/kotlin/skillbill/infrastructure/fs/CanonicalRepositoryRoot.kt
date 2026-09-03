package skillbill.infrastructure.fs

import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

object CanonicalRepositoryRoot : RepositoryEnclosingRootPort {
  override fun enclosingRepositoryRoot(start: Path): Path {
    val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
    var candidate = resolvedStart
    while (!candidate.resolve(".git").toFile().exists()) {
      candidate = candidate.parent ?: return resolvedStart
    }
    return candidate.toRealPath()
  }
}
