@file:Suppress("ThrowsCount")

package skillbill.scaffold.authoring

import skillbill.error.InvalidInternalSkillClassificationError
import java.nio.file.Path

/**
 * SKILL-102 subtask 1 (PD1): internal-skill classification.
 *
 * An internal skill is declared by exactly one optional `content.md` frontmatter key:
 * `internal-for: <parent-skill-name>`. Presence of the key makes the skill internal; absence
 * means listed. The classification is enforced with typed, actionable loud-fail errors that
 * name the offending skill, the declared parent, and the rule violated.
 *
 * Rules (all enforced here):
 *  - `internal-for` value must be the `name` of another discovered skill.
 *  - The parent must not itself carry `internal-for` (no chains, depth is 1).
 *  - The parent must not be the skill itself.
 *  - A missing or empty value is an error, not "treat as listed".
 *
 * This module is pure: it takes the discovered targets and returns nothing on success or throws
 * [InvalidInternalSkillClassificationError] with an actionable message. Both the authoring
 * discovery seam and the repo-validation seam call into it so the rules are enforced identically
 * wherever the classification is read.
 */
internal fun validateInternalSkillClassification(targets: Map<String, AuthoringTarget>) {
  targets.values.forEach { target ->
    val declaredParent = target.internalFor
    if (declaredParent == null) {
      return@forEach
    }
    if (declaredParent.isBlank()) {
      throw InvalidInternalSkillClassificationError(
        "${target.contentFile}: internal skill '${target.skillName}' declares parent via " +
          "'internal-for:' with an empty value; the value must be the name of another discovered skill.",
      )
    }
    if (declaredParent == target.skillName) {
      throw InvalidInternalSkillClassificationError(
        "${target.contentFile}: internal skill '${target.skillName}' declares parent " +
          "'$declaredParent' which is the skill itself; an internal skill's parent must be a " +
          "different discovered skill.",
      )
    }
    val parentTarget = targets[declaredParent]
    if (parentTarget == null) {
      throw InvalidInternalSkillClassificationError(
        "${target.contentFile}: internal skill '${target.skillName}' declares parent " +
          "'$declaredParent' which is not a discovered skill.",
      )
    }
    if (parentTarget.internalFor != null) {
      throw InvalidInternalSkillClassificationError(
        "${target.contentFile}: internal skill '${target.skillName}' declares parent " +
          "'$declaredParent' which is itself an internal skill (chained internal-for is not allowed; " +
          "depth is 1).",
      )
    }
  }
}

// Kept for parity with the install-plan and repo-validation variants. The single-quoted skill
// name convention ('<skill>') is enforced by InternalSkillClassificationTest.
internal fun internalClassificationMessageFragment(skillName: String): String = "'$skillName'"

/**
 * Read the `internal-for` frontmatter value from a content.md file. Returns null when the key is
 * absent (listed skill), the non-blank trimmed value otherwise. Used by both discovery paths so
 * the parse seam lives in one place.
 */
internal fun parseInternalForFrontmatter(contentFile: Path): String? {
  val text = contentFrontmatterValue(contentFile, "internal-for")
  return text
}

// Reads a single frontmatter value from a content.md file using the canonical frontmatter regex.
// Mirrors `parseSkillFrontmatter` semantics so the parse seam stays consistent across validators.
private fun contentFrontmatterValue(contentFile: Path, key: String): String? {
  if (!java.nio.file.Files.isRegularFile(contentFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
    return null
  }
  val text = java.nio.file.Files.readString(contentFile)
  val match = FRONTMATTER_PATTERN.find(text) ?: return null
  val frontmatter = match.groupValues[1]
  return frontmatter.lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator < 0) {
      null
    } else {
      val parsedKey = line.substring(0, separator).trim()
      val parsedValue = line.substring(separator + 1).trim().trim('"', '\'')
      if (parsedKey == key) parsedValue else null
    }
  }.firstOrNull()
}

private val FRONTMATTER_PATTERN = Regex("""(?s)\A---\n(.*?)\n---\n""")
