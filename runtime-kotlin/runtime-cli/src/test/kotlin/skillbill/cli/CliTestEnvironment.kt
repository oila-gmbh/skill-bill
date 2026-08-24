package skillbill.cli

import java.nio.file.Path

private const val CODEX_HOME_ENV = "CODEX_HOME"

internal fun isolatedCliEnvironment(home: Path? = null): Map<String, String> {
  val filtered = System.getenv().filterKeys { key -> key != CODEX_HOME_ENV }
  return if (home == null) {
    filtered
  } else {
    filtered + (CODEX_HOME_ENV to home.resolve(".codex").toString())
  }
}
