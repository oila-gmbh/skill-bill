package skillbill.install.staging

import skillbill.scaffold.runtime.requiredSupportingFilesForSkill
import skillbill.scaffold.runtime.supportingFileTargets
import java.nio.file.Path

internal data class GeneratedSupportPointer(
  val name: String,
  val target: Path,
)

internal fun generatedSupportPointersFor(
  repoRoot: Path,
  sourceSkillDir: Path,
  skillName: String,
  skillsRoot: Path = repoRoot.resolve("skills"),
): List<GeneratedSupportPointer> {
  val root = repoRoot.toAbsolutePath().normalize()
  val resolvedSkillsRoot = skillsRoot.toAbsolutePath().normalize()
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  if (!resolvedSource.startsWith(resolvedSkillsRoot)) {
    return emptyList()
  }
  val targets = supportingFileTargets(root)
  return requiredSupportingFilesForSkill(skillName, root).mapNotNull { fileName ->
    val target = targets[fileName]?.toAbsolutePath()?.normalize() ?: return@mapNotNull null
    val sourceSidecar = resolvedSource.resolve(fileName).normalize()
    if (target == sourceSidecar) {
      null
    } else {
      GeneratedSupportPointer(fileName, target)
    }
  }
}
