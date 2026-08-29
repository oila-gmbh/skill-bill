package skillbill.scaffold.runtime

import skillbill.scaffold.authoring.InternalSkillDeclaration
import skillbill.scaffold.authoring.internalSkillClassificationViolations
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal fun validateInternalSidecarCollisions(skills: Map<String, Path>, issues: MutableList<String>) {
  val internalByParent = skills.entries
    .mapNotNull { (skillName, contentFile) ->
      val declaredParent = parseInternalForFrontmatter(contentFile)?.takeIf(String::isNotBlank)
      if (declaredParent != null && declaredParent != skillName && declaredParent in skills) {
        declaredParent to skillName
      } else {
        null
      }
    }
    .groupBy({ it.first }, { it.second })
  internalByParent.forEach { (parentName, children) ->
    val parentFile = skills[parentName] ?: return@forEach
    val parentDir = parentFile.parent
    children.sorted().forEach { childName ->
      val sidecar = parentDir.resolve("$childName.md")
      if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
        issues += "$parentFile: internal sidecar '$childName.md' for parent '$parentName' " +
          "collides with an authored file at '$sidecar'; remove the authored file or rename the " +
          "internal skill."
      }
    }
  }
}

internal fun validatePortableReviewWording(
  skillName: String,
  text: String,
  skillFile: Path,
  issues: MutableList<String>,
  portableReviewSkills: Set<String>,
) {
  if (skillName !in portableReviewSkills) {
    return
  }
  repoValidationNonPortableReviewPatterns.forEach { (pattern, message) ->
    if (pattern.containsMatchIn(text)) {
      issues += "$skillFile: $message"
    }
  }
}
internal fun validateInternalSkillClassification(
  baseSkillFiles: Map<String, Path>,
  platformSkillFiles: Map<String, Path>,
  issues: MutableList<String>,
) {
  val declarations = baseSkillFiles.entries.map { (skillName, contentFile) ->
    InternalSkillDeclaration(
      skillName = skillName,
      contentFile = contentFile,
      declaredParent = parseInternalForFrontmatter(contentFile),
      isBaseSkill = true,
    )
  } + platformSkillFiles.entries.map { (skillName, contentFile) ->
    InternalSkillDeclaration(
      skillName = skillName,
      contentFile = contentFile,
      declaredParent = parseInternalForFrontmatter(contentFile),
      isBaseSkill = false,
    )
  }
  issues += internalSkillClassificationViolations(declarations)
}

internal fun validateInternalSidecarReferences(skillFiles: Map<String, Path>, issues: MutableList<String>) {
  val declaredParents = skillFiles.mapValues { (_, contentFile) ->
    parseInternalForFrontmatter(contentFile)?.takeIf(String::isNotBlank)
  }
  skillFiles.forEach { (skillName, contentFile) ->
    val effectiveParent = declaredParents[skillName] ?: skillName
    repoValidationSidecarReferencePattern.findAll(Files.readString(contentFile)).forEach { match ->
      val referenced = match.groupValues[1]
      if (referenced !in skillFiles || referenced == skillName) {
        return@forEach
      }
      val referencedParent = declaredParents[referenced]
      if (referencedParent == null) {
        issues += "$contentFile: references sidecar '$referenced.md' but '$referenced' is a listed " +
          "skill and renders no sidecar; invoke it via the Skill tool or classify it internal."
      } else if (referencedParent != effectiveParent) {
        issues += "$contentFile: references sidecar '$referenced.md' but '$referenced' is internal to " +
          "'$referencedParent', not co-located with '$skillName' (effective parent '$effectiveParent'); " +
          "the sidecar will not exist in this skill's installed directory."
      }
    }
  }
}
