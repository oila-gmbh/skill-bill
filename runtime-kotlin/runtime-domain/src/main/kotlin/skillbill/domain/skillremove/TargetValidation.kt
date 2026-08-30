@file:Suppress("MaxLineLength")

package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.Paths

internal object TargetValidation {
  private val NAME_REGEX: Regex = Regex("^[A-Za-z0-9._-]+$")

  fun validateOrRefuse(request: SkillRemovalRequest) {
    val repoRoot = Paths.get(request.repoRootAbsolutePath).toAbsolutePath().normalize()
    val problem: String? = when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> nameProblem(target.skillName, "skillName")
      is SkillRemovalTarget.PlatformPack -> nameProblem(target.platform, "platform")
      is SkillRemovalTarget.AddOn -> validateAddOnRelativePath(target.relativePath, repoRoot)
      is SkillRemovalTarget.ExternalAddOn ->
        nameProblem(target.platform, "platform")
          ?: validateExternalAddOnPaths(target.sourceRootAbsolutePath, target.fileName)
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
}
