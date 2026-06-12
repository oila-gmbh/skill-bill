package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.model.EnvironmentContext
import skillbill.ports.review.ReviewInputSource
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewInputSource(
  context: EnvironmentContext,
) : ReviewInputSource {
  constructor() : this(EnvironmentContext())

  private val resolvedContext = context.withProcessDefaults()

  override fun readInput(inputPath: String, stdinText: String?): Pair<String, String?> {
    if (inputPath == "-") {
      require(stdinText != null) { "stdinText is required when inputPath is '-'." }
      return stdinText to null
    }
    val path = expandAndNormalizePath(inputPath)
    return Files.readString(path) to path.toString()
  }

  private fun expandAndNormalizePath(rawPath: String): Path {
    val normalized =
      when {
        rawPath == "~" -> resolvedContext.userHome.toString()
        rawPath.startsWith("~/") -> resolvedContext.userHome.resolve(rawPath.removePrefix("~/")).toString()
        else -> rawPath
      }
    return Path.of(normalized).toAbsolutePath().normalize()
  }
}

internal fun EnvironmentContext.withProcessDefaults(): EnvironmentContext {
  val withUserHome =
    if (userHome == EnvironmentContext.UnspecifiedUserHome) {
      copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
    } else {
      copy(userHome = userHome.toAbsolutePath().normalize())
    }
  return if (withUserHome.environment === EnvironmentContext.UnspecifiedEnvironment) {
    withUserHome.copy(environment = System.getenv())
  } else {
    withUserHome
  }
}
