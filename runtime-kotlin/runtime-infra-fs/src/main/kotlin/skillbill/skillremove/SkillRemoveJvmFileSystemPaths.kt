package skillbill.skillremove

import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.Path

internal fun skillRemoveRepoRoot(request: SkillRemovalRequest): Path =
  Path.of(request.repoRootAbsolutePath).toAbsolutePath().normalize()

internal fun skillRemoveExternalSourceRoot(target: SkillRemovalTarget.ExternalAddOn): Path =
  Path.of(target.sourceRootAbsolutePath).toAbsolutePath().normalize()

internal fun skillRemoveExternalAddonFile(target: SkillRemovalTarget.ExternalAddOn): Path =
  skillRemoveExternalSourceRoot(target).resolve(target.fileName).normalize()

internal fun skillRemoveUserHome(request: SkillRemovalRequest, home: Path?): Path =
  request.userHomeAbsolutePath?.let { Path.of(it).toAbsolutePath().normalize() }
    ?: home
    ?: Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

internal fun describeTargetForLog(target: SkillRemovalTarget): String = when (target) {
  is SkillRemovalTarget.HorizontalSkill -> "skill:${target.skillName}"
  is SkillRemovalTarget.PlatformPack -> "platform:${target.platform}"
  is SkillRemovalTarget.AddOn -> "addon:${target.relativePath}"
  is SkillRemovalTarget.ExternalAddOn -> "external-addon:${target.platform}/${target.fileName}"
}

internal fun Path.portablePath(): String = toString().replace('\\', '/')
