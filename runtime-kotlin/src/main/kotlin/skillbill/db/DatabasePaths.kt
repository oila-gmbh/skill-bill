package skillbill.db

import java.nio.file.Path
import java.nio.file.Paths

object DatabasePaths {
  fun resolveDbPath(
    cliValue: String?,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Paths.get(System.getProperty("user.home")),
  ): Path {
    val candidate = cliValue ?: environment[DbConstants.DB_ENVIRONMENT_KEY]
    return if (candidate != null) {
      expandUserPath(candidate = candidate, userHome = userHome)
    } else {
      DbConstants.defaultDbPath(userHome).toAbsolutePath().normalize()
    }
  }

  private fun expandUserPath(candidate: String, userHome: Path): Path {
    val expandedCandidate =
      when {
        candidate == "~" -> userHome.toString()
        candidate.startsWith("~/") || candidate.startsWith("~\\") ->
          userHome.resolve(candidate.drop(2)).toString()
        else -> candidate
      }
    return Paths.get(expandedCandidate).toAbsolutePath().normalize()
  }
}
