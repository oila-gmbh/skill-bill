package skillbill.skillremove

import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEditKind
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class SkillRemoveJvmFileSystemPlanning(
  internal val home: Path?,
) {
  fun discoverCascadedSkillNames(request: SkillRemovalRequest): List<String> {
    val target = request.target as? SkillRemovalTarget.HorizontalSkill ?: return emptyList()
    val repoRoot = skillRemoveRepoRoot(request)
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val slug = target.skillName.removePrefix("bill-")
    val out = linkedSetOf<String>()
    Files.list(packsRoot).use { stream ->
      stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
        collectCascadedSkillNamesFromPack(packDir, slug, out)
      }
    }
    return out.toList()
  }

  fun targetExists(request: SkillRemovalRequest): Boolean {
    val root = skillRemoveRepoRoot(request)
    return when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill ->
        Files.isDirectory(root.resolve("skills/${target.skillName}"), LinkOption.NOFOLLOW_LINKS)
      is SkillRemovalTarget.PlatformPack ->
        Files.isDirectory(root.resolve("platform-packs/${target.platform}"), LinkOption.NOFOLLOW_LINKS)
      is SkillRemovalTarget.AddOn ->
        Files.exists(root.resolve(target.relativePath), LinkOption.NOFOLLOW_LINKS)
      is SkillRemovalTarget.ExternalAddOn ->
        Files.exists(skillRemoveExternalAddonFile(target), LinkOption.NOFOLLOW_LINKS)
    }
  }

  fun resolveCascadeFilesystemPaths(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<String> =
    when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> horizontalCascadePaths(skillRemoveRepoRoot(request), cascadedSkillNames)
      is SkillRemovalTarget.PlatformPack -> platformPackCascadePaths(skillRemoveRepoRoot(request), target.platform)
      is SkillRemovalTarget.AddOn -> {
        val absolute = skillRemoveRepoRoot(request).resolve(target.relativePath)
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) listOf(target.relativePath) else emptyList()
      }
      is SkillRemovalTarget.ExternalAddOn -> {
        val absolute = skillRemoveExternalAddonFile(target)
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) listOf(absolute.portablePath()) else emptyList()
      }
    }

  fun planManifestEdits(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<ManifestEdit> {
    require(cascadedSkillNames.size >= 0)
    return when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> horizontalManifestEdits(skillRemoveRepoRoot(request), target.skillName)
      is SkillRemovalTarget.PlatformPack -> emptyList()
      is SkillRemovalTarget.AddOn -> addonReferenceEdits(skillRemoveRepoRoot(request), target.relativePath)
      is SkillRemovalTarget.ExternalAddOn -> externalAddonReferenceEdits(target)
    }
  }

  fun planAgentSymlinkUnlinks(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<AgentSymlinkUnlink> = when (val target = request.target) {
    is SkillRemovalTarget.HorizontalSkill ->
      agentUnlinksForSkills(request, cascadedSkillNames)
    is SkillRemovalTarget.PlatformPack ->
      agentUnlinksForPlatform(request, target.platform)
    is SkillRemovalTarget.AddOn -> emptyList()
    is SkillRemovalTarget.ExternalAddOn -> emptyList()
  }

  fun planReadmeCatalogEdits(request: SkillRemovalRequest): List<ReadmeCatalogEdit> {
    val target = request.target
    if (target !is SkillRemovalTarget.HorizontalSkill) return emptyList()
    val readme = skillRemoveRepoRoot(request).resolve("README.md")
    if (!Files.isRegularFile(readme, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return listOf(
      ReadmeCatalogEdit(
        readmePath = "README.md",
        kind = ReadmeCatalogEditKind.REMOVE_CATALOG_ROW,
        detail = "Remove catalog row for `/${target.skillName}`",
      ),
      ReadmeCatalogEdit(
        readmePath = "README.md",
        kind = ReadmeCatalogEditKind.DECREMENT_SECTION_COUNT,
        detail = "Decrement Canonical Skills section count",
      ),
    )
  }

  internal companion object {
    internal const val ADDON_PATH_SEGMENT_COUNT = 4
    internal const val ADDON_ROOT_SEGMENT = 0
    internal const val ADDON_PLATFORM_SEGMENT = 1
    internal const val ADDON_FOLDER_SEGMENT = 2
    internal const val ADDON_FILE_SEGMENT = 3
    internal const val EXTERNAL_ADDON_MANIFEST_FILE = "addon-manifest.yaml"
  }
}
