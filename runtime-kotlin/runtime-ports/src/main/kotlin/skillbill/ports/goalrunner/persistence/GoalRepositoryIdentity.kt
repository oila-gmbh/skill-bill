package skillbill.ports.goalrunner.persistence

import java.nio.file.Path

fun goalRepositoryIdentity(repoRoot: Path): String {
  val canonical = runCatching { repoRoot.toRealPath() }
    .getOrElse { repoRoot.toAbsolutePath().normalize() }
  return "repo-root-realpath-v1:$canonical"
}
