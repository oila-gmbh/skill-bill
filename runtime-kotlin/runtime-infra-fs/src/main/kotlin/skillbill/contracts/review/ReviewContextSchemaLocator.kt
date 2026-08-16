package skillbill.contracts.review

import skillbill.error.InvalidReviewContextSchemaError
import java.nio.file.Files
import java.nio.file.Path

internal fun readReviewContextSchemaText(): String {
  ReviewContextSchemaValidator::class.java.classLoader
    .getResourceAsStream(REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForReviewContextSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidReviewContextSchemaError(
    sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical review context schema is missing. Expected to find it on the JVM classpath at " +
      "'$REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

internal fun walkForReviewContextSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
