package skillbill.scaffold.validation

import skillbill.error.InvalidReviewSkillStructureError
import java.nio.file.Path

internal fun violation(path: Path, rule: String) = ReviewSkillStructureViolation(path, rule)

internal fun invalidNativeAgentBundle(path: Path, error: Exception): Nothing = throw InvalidReviewSkillStructureError(
  "$path: invalid native-agent source bundle: ${error.message}",
  error,
)
