@file:Suppress("ReturnCount", "MaxLineLength")

package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * F-S01: centralized input validation for every [SkillRemovalTarget] before it touches the
 * filesystem. The CLI accepts free-form `skill:<name>` / `platform:<slug>` / `addon:<path>`
 * arguments and the desktop tree handler can synthesize them too; in both cases the value MUST
 * be validated BEFORE [SkillRemove.previewRemoval] / [SkillRemove.executeRemoval] resolve it
 * against `repoRoot`. `Path.resolve("/etc/passwd")` returns `/etc/passwd` because absolute
 * arguments replace the base, so any laxness here is a real path-traversal vector.
 *
 * Rules:
 * - `skillName` / `platform` must be non-blank, must not equal `.` or `..`, must not start with
 *   `-`, and must consist solely of `[A-Za-z0-9._-]`.
 * - `AddOn.relativePath` must be a relative path, contain no `..` segments, contain no `\\`, and
 *   the normalized resolved absolute path must lie under `<repoRoot>/platform-packs/`.
 *
 * Implementation note: validation functions return a problem string (null = valid) and the
 * single public entry throws once. This keeps detekt's ThrowsCount happy without dropping
 * coverage.
 */
internal object TargetValidation {
  private val NAME_REGEX: Regex = Regex("^[A-Za-z0-9._-]+$")

  fun validateOrRefuse(request: SkillRemovalRequest) {
    val repoRoot = Paths.get(request.repoRootAbsolutePath).toAbsolutePath().normalize()
    val problem: String? = when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> nameProblem(target.skillName, "skillName")
      is SkillRemovalTarget.PlatformPack -> nameProblem(target.platform, "platform")
      is SkillRemovalTarget.AddOn -> addOnPathProblem(target.relativePath, repoRoot)
    }
    if (problem != null) {
      throw SkillRemovalRefusedException(SkillRemovalRefusalReason.INVALID_TARGET, problem)
    }
  }

  private fun nameProblem(name: String, field: String): String? = when {
    name.isBlank() -> "Invalid $field: must not be blank."
    name == "." || name == ".." -> "Invalid $field '$name': '.' and '..' are not valid identifiers."
    name.startsWith("-") -> "Invalid $field '$name': must not start with '-'."
    !NAME_REGEX.matches(name) -> "Invalid $field '$name': only [A-Za-z0-9._-] characters are allowed."
    else -> null
  }

  private fun addOnPathProblem(relative: String, repoRoot: Path): String? {
    if (relative.isBlank()) return "Invalid add-on path: must not be blank."
    if (relative.contains('\\')) return "Invalid add-on path '$relative': backslashes are not allowed."
    val parsed = try {
      Paths.get(relative)
    } catch (error: InvalidPathException) {
      // Cause is intentionally not chained — domain refusal exceptions are user-facing strings and
      // the original InvalidPathException carries the underlying detail in `error.message`, which
      // we propagate verbatim.
      @Suppress("SwallowedException")
      return "Invalid add-on path '$relative': ${error.message.orEmpty()}"
    }
    val resolved = repoRoot.resolve(parsed).normalize()
    val packsRoot = repoRoot.resolve("platform-packs").normalize()
    return when {
      parsed.isAbsolute -> "Invalid add-on path '$relative': absolute paths are not allowed."
      parsed.any { it.toString() == ".." } -> "Invalid add-on path '$relative': '..' segments are not allowed."
      !resolved.startsWith(repoRoot) -> "Invalid add-on path '$relative': resolves outside the repository root."
      !resolved.startsWith(packsRoot) -> "Invalid add-on path '$relative': add-ons must live under 'platform-packs/'."
      else -> null
    }
  }
}
