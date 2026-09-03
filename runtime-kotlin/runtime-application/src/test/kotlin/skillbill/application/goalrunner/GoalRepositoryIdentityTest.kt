package skillbill.application.goalrunner

import skillbill.application.TestRepositoryEnclosingRoot
import java.nio.file.Path

fun goalRepositoryIdentity(repoRoot: Path): String = goalRepositoryIdentity(repoRoot, TestRepositoryEnclosingRoot)
