package skillbill.infrastructure.fs

import java.nio.file.Path

fun canonicalRepositoryRoot(start: Path): Path {
  val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
  var candidate = resolvedStart
  while (!candidate.resolve(".git").toFile().exists()) {
    candidate = candidate.parent ?: return resolvedStart
  }
  return candidate.toRealPath()
}
