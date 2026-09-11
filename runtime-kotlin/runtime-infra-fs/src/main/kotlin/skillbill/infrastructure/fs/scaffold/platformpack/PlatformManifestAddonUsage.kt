package skillbill.infrastructure.fs.scaffold.platformpack

import skillbill.model.toPath
import skillbill.review.plan.ReviewAddonSelectionPolicy
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun PlatformManifest.addonUsageFor(contentFile: Path): List<GovernedAddonSelection> {
  val dir = packRelativeSkillDir(contentFile)
  return if (dir.startsWith("code-review/")) {
    ReviewAddonSelectionPolicy.select(this, contentFile.parent.fileName.toString())
  } else {
    addonUsage.firstOrNull { usage -> usage.skillRelativeDir == dir }?.addons.orEmpty()
  }
}

internal fun PlatformManifest.declaredSkillRelativeDirs(): Set<String> =
  declaredSkillRelativeDirs(packRoot.toPath(), declaredFiles, declaredQualityCheckFile?.toPath())

internal fun declaredSkillRelativeDirs(
  packRoot: Path,
  declaredFiles: DeclaredFiles,
  declaredQualityCheckFile: Path?,
): Set<String> = buildSet {
  declaredFiles.baseline?.let { add(packRelativeSkillDir(packRoot, it.toPath())) }
  declaredFiles.areas.values.forEach { add(packRelativeSkillDir(packRoot, it.toPath())) }
  declaredQualityCheckFile?.let { add(packRelativeSkillDir(packRoot, it)) }
}

private fun PlatformManifest.packRelativeSkillDir(contentFile: Path): String = packRoot.toPath()
  .toAbsolutePath()
  .normalize()
  .relativize(contentFile.parent.toAbsolutePath().normalize())
  .toString()
  .replace('\\', '/')

private fun packRelativeSkillDir(packRoot: Path, contentFile: Path): String = packRoot.toAbsolutePath().normalize()
  .relativize(contentFile.parent.toAbsolutePath().normalize())
  .toString()
  .replace('\\', '/')
