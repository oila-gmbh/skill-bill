@file:Suppress("MatchingDeclarationName")

package skillbill.scaffold.authoring

import skillbill.error.InvalidInternalSkillClassificationError
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * SKILL-102 (PD1): internal-skill classification.
 *
 * An internal skill is declared by exactly one optional `content.md` frontmatter key:
 * `internal-for: <parent-skill-name>`. Presence of the key makes the skill internal; absence
 * means listed.
 *
 * [internalSkillClassificationViolations] is the single rule evaluator. Authoring discovery and
 * install-plan discovery throw the first violation ([requireValidInternalSkillClassification]);
 * `skill-bill validate` collects every violation as an issue string. One implementation so the
 * seams cannot drift apart.
 */
internal data class InternalSkillDeclaration(
  val skillName: String,
  val contentFile: Path,
  val declaredParent: String?,
  val isBaseSkill: Boolean,
)

internal fun internalSkillClassificationViolations(declarations: Collection<InternalSkillDeclaration>): List<String> {
  val byName = declarations.associateBy { declaration -> declaration.skillName }
  return declarations.mapNotNull { declaration ->
    val declaredParent = declaration.declaredParent ?: return@mapNotNull null
    val prefix = "${declaration.contentFile}: internal skill '${declaration.skillName}'"
    when {
      !declaration.isBaseSkill ->
        "$prefix declares 'internal-for:' but is a platform-pack skill; internal skills are only " +
          "supported for base skills under skills/."
      declaredParent.isBlank() ->
        "$prefix declares parent via 'internal-for:' with an empty value; the value must be the " +
          "name of another discovered skill."
      declaredParent == declaration.skillName ->
        "$prefix declares parent '$declaredParent' which is the skill itself; an internal skill's " +
          "parent must be a different discovered skill."
      else -> parentViolation(prefix, declaredParent, byName[declaredParent])
    }
  }
}

private fun parentViolation(prefix: String, declaredParent: String, parent: InternalSkillDeclaration?): String? = when {
  parent == null ->
    "$prefix declares parent '$declaredParent' which is not a discovered skill."
  !parent.isBaseSkill ->
    "$prefix declares parent '$declaredParent' which is a platform-pack skill; an internal " +
      "skill's parent must be a listed base skill under skills/."
  parent.declaredParent != null ->
    "$prefix declares parent '$declaredParent' which is itself an internal skill (chained " +
      "internal-for is not allowed; depth is 1)."
  else -> null
}

internal fun requireValidInternalSkillClassification(declarations: Collection<InternalSkillDeclaration>) {
  internalSkillClassificationViolations(declarations).firstOrNull()?.let { violation ->
    throw InvalidInternalSkillClassificationError(violation)
  }
}

internal fun validateInternalSkillClassification(targets: Map<String, AuthoringTarget>) {
  requireValidInternalSkillClassification(
    targets.values.map { target ->
      InternalSkillDeclaration(
        skillName = target.skillName,
        contentFile = target.contentFile,
        declaredParent = target.internalFor,
        isBaseSkill = target.platform.isBlank(),
      )
    },
  )
}

/**
 * Reads the `internal-for` frontmatter value from a content.md file. Returns null when the key is
 * absent (listed skill); otherwise the trimmed value, which may be empty — an empty declaration
 * loud-fails downstream instead of being treated as listed. When the key appears more than once
 * the first occurrence wins; every seam reads through this function so they cannot disagree.
 */
internal fun parseInternalForFrontmatter(contentFile: Path): String? {
  if (!Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)) {
    return null
  }
  val match = FRONTMATTER_PATTERN.find(Files.readString(contentFile)) ?: return null
  return match.groupValues[1].lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator < 0) {
      null
    } else {
      val parsedKey = line.substring(0, separator).trim()
      val parsedValue = line.substring(separator + 1).trim().trim('"', '\'')
      if (parsedKey == "internal-for") parsedValue else null
    }
  }.firstOrNull()
}

private val FRONTMATTER_PATTERN = Regex("""(?s)\A---\n(.*?)\n---\n""")
