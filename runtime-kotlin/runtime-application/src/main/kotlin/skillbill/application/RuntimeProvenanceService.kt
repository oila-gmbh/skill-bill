package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.RuntimeProvenanceContract
import skillbill.contracts.system.toRuntimeProvenance
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
class RuntimeProvenanceService(
  private val systemService: SystemService,
) {
  fun current(
    executablePathHint: String?,
    classPath: String,
    javaCommand: String?,
    pathSeparator: String,
  ): RuntimeProvenanceContract {
    val executablePath = resolveRuntimeExecutablePath(
      explicitPath = executablePathHint,
      classPath = classPath,
      javaCommand = javaCommand,
      pathSeparator = pathSeparator,
    )
    return systemService.version().toRuntimeProvenance(executablePath = executablePath)
  }
}

internal fun resolveRuntimeExecutablePath(
  explicitPath: String?,
  classPath: String,
  javaCommand: String?,
  pathSeparator: String,
): String = sequenceOf(
  normalizePath(explicitPath),
  runtimeExecutableFromClassPath(classPath, pathSeparator),
  normalizePath(javaCommand),
)
  .firstOrNull { value -> value != null }
  ?: "unknown"

private fun runtimeExecutableFromClassPath(classPath: String, pathSeparator: String): String? = classPath
  .split(pathSeparator)
  .asSequence()
  .mapNotNull { entry -> runtimeExecutableCandidate(entry) }
  .firstOrNull()

private fun runtimeExecutableCandidate(classPathEntry: String): String? {
  val entryPath = parsePath(classPathEntry)
  val libDir = entryPath?.let { path ->
    when {
      path.fileName?.toString() == "*" -> path.parent
      path.fileName?.toString() == "lib" -> path
      path.parent?.fileName?.toString() == "lib" -> path.parent
      else -> null
    }
  }
  val runtimeExecutable = libDir
    ?.parent
    ?.takeIf { runtimeDir -> runtimeDir.fileName?.toString() == "runtime-cli" }
    ?.resolve("bin/runtime-cli")
    ?.toString()
  return normalizePath(runtimeExecutable)
}

private fun normalizePath(candidate: String?): String? {
  val value = candidate?.trim().orEmpty()
  if (value.isBlank()) {
    return null
  }
  return parsePath(value)?.toAbsolutePath()?.normalize()?.toString() ?: value
}

private fun parsePath(value: String): Path? = try {
  Path.of(value)
} catch (_: InvalidPathException) {
  null
}
