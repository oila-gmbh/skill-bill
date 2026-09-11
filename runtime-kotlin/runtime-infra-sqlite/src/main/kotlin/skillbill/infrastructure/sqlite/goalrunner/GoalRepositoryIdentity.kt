<<<<<<<< HEAD:runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/persistence/GoalRepositoryIdentity.kt
package skillbill.ports.goalrunner.persistence
========
package skillbill.infrastructure.sqlite.goalrunner
>>>>>>>> 9d724a13f (SKILL-233: Engine module and package roots):runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/goalrunner/GoalRepositoryIdentity.kt

import java.nio.file.Path

fun goalRepositoryIdentity(repoRoot: Path): String {
  val canonical = runCatching { repoRoot.toRealPath() }
    .getOrElse { repoRoot.toAbsolutePath().normalize() }
  return "repo-root-realpath-v1:$canonical"
}
