package skillbill.scaffold.platformpack

import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun PlatformManifest.addonUsageFor(contentFile: Path) =
  addonUsage.firstOrNull { usage -> usage.skillRelativeDir == packRelativeSkillDir(contentFile) }?.addons.orEmpty()

internal fun PlatformManifest.declaredSkillRelativeDirs(): Set<String> =
  declaredSkillRelativeDirs(packRoot, declaredFiles, declaredQualityCheckFile)

internal fun declaredSkillRelativeDirs(
  packRoot: Path,
  declaredFiles: DeclaredFiles,
  declaredQualityCheckFile: Path?,
): Set<String> = buildSet {
  declaredFiles.baseline?.let { add(packRelativeSkillDir(packRoot, it)) }
  declaredFiles.areas.values.forEach { add(packRelativeSkillDir(packRoot, it)) }
  declaredQualityCheckFile?.let { add(packRelativeSkillDir(packRoot, it)) }
}

private fun PlatformManifest.packRelativeSkillDir(contentFile: Path): String = packRoot.toAbsolutePath().normalize()
  .relativize(contentFile.parent.toAbsolutePath().normalize())
  .toString()
  .replace('\\', '/')

private fun packRelativeSkillDir(packRoot: Path, contentFile: Path): String = packRoot.toAbsolutePath().normalize()
  .relativize(contentFile.parent.toAbsolutePath().normalize())
  .toString()
  .replace('\\', '/')
