package skillbill.skillremove

import skillbill.domain.skillremove.model.AgentSymlinkProvider
import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ManifestEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEditKind
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.install.support.claudeConfigRoots
import skillbill.install.support.codexAgentsTargets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class SkillRemoveJvmFileSystemPlanning(
  private val home: Path?,
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
        val platform = packDir.fileName.toString()
        val basePrefix = "bill-$platform-$slug"
        listOf("code-review", "quality-check").forEach { family ->
          val familyDir = packDir.resolve(family)
          if (Files.isDirectory(familyDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(familyDir).use { areaStream ->
              areaStream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { areaDir ->
                val name = areaDir.fileName.toString()
                if (name == basePrefix || name.startsWith("$basePrefix-")) {
                  out += name
                }
              }
            }
          }
        }
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

  fun resolveCascadeFilesystemPaths(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<String> = when (val target = request.target) {
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

  fun planManifestEdits(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<ManifestEdit> =
    when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> horizontalManifestEdits(skillRemoveRepoRoot(request), target.skillName)
      is SkillRemovalTarget.PlatformPack -> emptyList() // the manifest itself is deleted with the tree
      is SkillRemovalTarget.AddOn -> addonReferenceEdits(skillRemoveRepoRoot(request), target.relativePath)
      is SkillRemovalTarget.ExternalAddOn -> externalAddonReferenceEdits(target)
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

  private fun horizontalCascadePaths(repoRoot: Path, cascadedSkillNames: List<String>): List<String> {
    val out = linkedSetOf<String>()
    cascadedSkillNames.forEach { skillName ->
      val direct = repoRoot.resolve("skills/$skillName")
      if (Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) out += "skills/$skillName"
      val packs = repoRoot.resolve("platform-packs")
      if (Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) {
        Files.list(packs).use { stream ->
          stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
            listOf("code-review", "quality-check").forEach { family ->
              val candidate = packDir.resolve(family).resolve(skillName)
              if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                out += repoRoot.relativize(candidate).toString().replace('\\', '/')
              }
            }
          }
        }
      }
    }
    return out.toList()
  }

  private fun platformPackCascadePaths(repoRoot: Path, platform: String): List<String> {
    val out = linkedSetOf<String>()
    val pack = repoRoot.resolve("platform-packs/$platform")
    if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) out += "platform-packs/$platform"
    return out.toList()
  }

  private fun horizontalManifestEdits(repoRoot: Path, skillName: String): List<ManifestEdit> {
    val slug = skillName.removePrefix("bill-")
    val edits = mutableListOf<ManifestEdit>()
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    Files.list(packsRoot).use { stream ->
      stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
        val manifest = packDir.resolve("platform.yaml")
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return@forEach
        val manifestRel = repoRoot.relativize(manifest).toString().replace('\\', '/')
        val platform = packDir.fileName.toString()
        val baselineName = "bill-$platform-$slug"
        val baselineDir = packDir.resolve("code-review").resolve(baselineName)
        val baselineExists = Files.isDirectory(baselineDir, LinkOption.NOFOLLOW_LINKS)
        if (baselineExists) {
          edits += ManifestEdit(
            manifestRel,
            ManifestEditKind.REMOVE_DECLARED_FILES_BASELINE,
            "remove declared_files.baseline",
          )
          edits += ManifestEdit(
            manifestRel,
            ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY,
            "code-review/$baselineName",
          )
        }
        val areaPrefix = "$baselineName-"
        val codeReviewDir = packDir.resolve("code-review")
        if (Files.isDirectory(codeReviewDir, LinkOption.NOFOLLOW_LINKS)) {
          Files.list(codeReviewDir).use { areaStream ->
            areaStream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { areaDir ->
              val name = areaDir.fileName.toString()
              if (name.startsWith(areaPrefix)) {
                val area = name.removePrefix(areaPrefix)
                edits += ManifestEdit(manifestRel, ManifestEditKind.REMOVE_CODE_REVIEW_AREA, area)
                edits += ManifestEdit(
                  manifestRel,
                  ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY,
                  "code-review/$name",
                )
              }
            }
          }
        }
        val qcName = "bill-$platform-$slug"
        val qcDir = packDir.resolve("quality-check").resolve(qcName)
        if (Files.exists(qcDir, LinkOption.NOFOLLOW_LINKS)) {
          edits += ManifestEdit(
            manifestRel,
            ManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE,
            "remove declared_quality_check_file",
          )
          edits += ManifestEdit(
            manifestRel,
            ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY,
            "quality-check/$qcName",
          )
        }
      }
    }
    return edits
  }

  private fun addonReferenceEdits(repoRoot: Path, relativePath: String): List<ManifestEdit> {
    val normalized = relativePath.replace('\\', '/')
    val parts = normalized.split('/')
    if (!isPackAddonPath(parts)) return emptyList()
    val pointerName = parts[ADDON_FILE_SEGMENT]
    val pointerSlug = pointerName.removeSuffix(".md")
    val platformManifest = repoRoot.resolve("platform-packs/${parts[ADDON_PLATFORM_SEGMENT]}/platform.yaml")
    val edits = mutableListOf<ManifestEdit>()
    if (Files.isRegularFile(platformManifest, LinkOption.NOFOLLOW_LINKS)) {
      val text = Files.readString(platformManifest)
      if (text.contains(pointerName) || text.contains(normalized)) {
        edits += ManifestEdit(
          repoRoot.relativize(platformManifest).toString().replace('\\', '/'),
          ManifestEditKind.REMOVE_ADDON_REFERENCES,
          pointerName,
        )
      }
    }
    val skillClassesDir = repoRoot.resolve("orchestration/skill-classes")
    if (Files.isDirectory(skillClassesDir, LinkOption.NOFOLLOW_LINKS)) {
      Files.list(skillClassesDir).use { stream ->
        stream
          .filter { path ->
            Files.isRegularFile(
              path,
              LinkOption.NOFOLLOW_LINKS,
            ) && path.fileName.toString().endsWith(".yaml")
          }
          .forEach { manifest ->
            val text = Files.readString(manifest)
            if (Regex(
                "^\\s*-\\s*['\"]?${Regex.escape(pointerSlug)}['\"]?\\s*$",
                RegexOption.MULTILINE,
              ).containsMatchIn(text)
            ) {
              edits += ManifestEdit(
                repoRoot.relativize(manifest).toString().replace('\\', '/'),
                ManifestEditKind.REMOVE_SKILL_CLASS_POINTER,
                pointerSlug,
              )
            }
          }
      }
    }
    return edits
  }

  private fun externalAddonReferenceEdits(target: SkillRemovalTarget.ExternalAddOn): List<ManifestEdit> {
    val manifest = skillRemoveExternalSourceRoot(target).resolve(EXTERNAL_ADDON_MANIFEST_FILE).normalize()
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val text = Files.readString(manifest)
    if (!text.contains(target.fileName)) return emptyList()
    return listOf(
      ManifestEdit(
        manifest.portablePath(),
        ManifestEditKind.REMOVE_ADDON_REFERENCES,
        target.fileName,
      ),
    )
  }

  private fun isPackAddonPath(parts: List<String>): Boolean = parts.size == ADDON_PATH_SEGMENT_COUNT &&
    parts[ADDON_ROOT_SEGMENT] == "platform-packs" &&
    parts[ADDON_FOLDER_SEGMENT] == "addons" &&
    parts[ADDON_FILE_SEGMENT].endsWith(".md")

  private fun agentUnlinksForSkills(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<AgentSymlinkUnlink> {
    val resolvedHome = skillRemoveUserHome(request, home)
    val environment = request.environment.ifEmpty { System.getenv() }
    val out = mutableListOf<AgentSymlinkUnlink>()
    cascadedSkillNames.forEach { name ->
      AgentSymlinkProvider.values().forEach { provider ->
        agentHomeDirs(provider, resolvedHome, environment).forEach { dir ->
          val candidate = dir.resolve("$name.md")
          out += AgentSymlinkUnlink(provider = provider, path = candidate.toString().replace('\\', '/'))
        }
      }
    }
    return out
  }

  private fun agentUnlinksForPlatform(request: SkillRemovalRequest, platform: String): List<AgentSymlinkUnlink> {
    val resolvedHome = skillRemoveUserHome(request, home)
    val environment = request.environment.ifEmpty { System.getenv() }
    val out = mutableListOf<AgentSymlinkUnlink>()
    AgentSymlinkProvider.values().forEach { provider ->
      agentHomeDirs(provider, resolvedHome, environment).forEach { dir ->
        out += AgentSymlinkUnlink(
          provider = provider,
          path = dir.resolve("bill-$platform-*").toString().replace('\\', '/'),
        )
      }
    }
    return out
  }

  private fun agentHomeDirs(provider: AgentSymlinkProvider, home: Path, environment: Map<String, String>): List<Path> =
    when (provider) {
      AgentSymlinkProvider.CLAUDE -> claudeConfigRoots(home, environment).map { it.resolve("agents") }
      AgentSymlinkProvider.CODEX -> codexAgentsTargets(home, environment)
      AgentSymlinkProvider.JUNIE -> listOf(home.resolve(".junie/agents"))
      AgentSymlinkProvider.CURSOR -> listOf(home.resolve(".cursor/agents"))
    }

  private companion object {
    private const val ADDON_PATH_SEGMENT_COUNT = 4
    private const val ADDON_ROOT_SEGMENT = 0
    private const val ADDON_PLATFORM_SEGMENT = 1
    private const val ADDON_FOLDER_SEGMENT = 2
    private const val ADDON_FILE_SEGMENT = 3
    private const val EXTERNAL_ADDON_MANIFEST_FILE = "addon-manifest.yaml"
  }
}
