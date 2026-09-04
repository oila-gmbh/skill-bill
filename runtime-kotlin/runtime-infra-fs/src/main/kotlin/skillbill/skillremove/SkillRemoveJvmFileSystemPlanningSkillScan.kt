package skillbill.skillremove

import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ManifestEditKind
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun SkillRemoveJvmFileSystemPlanning.horizontalCascadePaths(
  repoRoot: Path,
  cascadedSkillNames: List<String>,
): List<String> {
  val out = linkedSetOf<String>()
  cascadedSkillNames.forEach { skillName -> collectHorizontalCascadePaths(repoRoot, skillName, out) }
  return out.toList()
}

internal fun SkillRemoveJvmFileSystemPlanning.platformPackCascadePaths(repoRoot: Path, platform: String): List<String> {
  val out = linkedSetOf<String>()
  val pack = repoRoot.resolve("platform-packs/$platform")
  if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) out += "platform-packs/$platform"
  return out.toList()
}

internal fun SkillRemoveJvmFileSystemPlanning.horizontalManifestEdits(
  repoRoot: Path,
  skillName: String,
): List<ManifestEdit> {
  val edits = mutableListOf<ManifestEdit>()
  val packsRoot = repoRoot.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
  Files.list(packsRoot).use { stream ->
    stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
      collectHorizontalManifestEditsForPack(repoRoot, packDir, skillName, edits)
    }
  }
  return edits
}

internal fun SkillRemoveJvmFileSystemPlanning.addonReferenceEdits(
  repoRoot: Path,
  relativePath: String,
): List<ManifestEdit> {
  val normalized = relativePath.replace('\\', '/')
  val parts = normalized.split('/')
  if (!isPackAddonPath(parts)) return emptyList()
  val pointerName = parts[SkillRemoveJvmFileSystemPlanning.ADDON_FILE_SEGMENT]
  val pointerSlug = pointerName.removeSuffix(".md")
  val platformManifest = repoRoot.resolve(
    "platform-packs/${parts[SkillRemoveJvmFileSystemPlanning.ADDON_PLATFORM_SEGMENT]}/platform.yaml",
  )
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

internal fun SkillRemoveJvmFileSystemPlanning.externalAddonReferenceEdits(
  target: SkillRemovalTarget.ExternalAddOn,
): List<ManifestEdit> {
  val manifest = skillRemoveExternalSourceRoot(target)
    .resolve(SkillRemoveJvmFileSystemPlanning.EXTERNAL_ADDON_MANIFEST_FILE).normalize()
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

internal fun SkillRemoveJvmFileSystemPlanning.isPackAddonPath(parts: List<String>): Boolean =
  parts.size == SkillRemoveJvmFileSystemPlanning.ADDON_PATH_SEGMENT_COUNT &&
    parts[SkillRemoveJvmFileSystemPlanning.ADDON_ROOT_SEGMENT] == "platform-packs" &&
    parts[SkillRemoveJvmFileSystemPlanning.ADDON_FOLDER_SEGMENT] == "addons" &&
    parts[SkillRemoveJvmFileSystemPlanning.ADDON_FILE_SEGMENT].endsWith(".md")
