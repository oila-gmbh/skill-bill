package skillbill.cli

import java.nio.file.Files
import java.nio.file.Path

private const val CODEX_HOME_ENV = "CODEX_HOME"
private const val GOAL_CONTINUATION_ENV = "SKILL_BILL_GOAL_CONTINUATION"

internal fun isolatedCliEnvironment(home: Path? = null): Map<String, String> {
  val filtered = System.getenv().filterKeys { key ->
    key != CODEX_HOME_ENV && key != GOAL_CONTINUATION_ENV
  }
  if (home == null) {
    return filtered
  }
  val codexHome = home.resolve(".codex")
  return if (Files.isDirectory(codexHome)) {
    filtered + (CODEX_HOME_ENV to codexHome.toString())
  } else {
    filtered
  }
}
