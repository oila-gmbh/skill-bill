package skillbill.contracts

import java.util.logging.Level
import java.util.logging.Logger

fun logSchemaLoadFailure(
  log: Logger,
  schemaLabel: String,
  classpathResource: String,
  repoRelativePath: String,
  error: Throwable,
) {
  log.log(
    Level.SEVERE,
    "Failed to load canonical $schemaLabel schema: classpath='$classpathResource' " +
      "repoRelativePath='$repoRelativePath' errorType='${error::class.qualifiedName}' " +
      "message='${error.message.orEmpty()}'",
    error,
  )
}
