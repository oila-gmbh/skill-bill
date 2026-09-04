package skillbill.skillremove

import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ManifestEditKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun collectCascadedSkillNamesFromPack(packDir: Path, basePrefix: String, out: MutableSet<String>) {
  val platform = packDir.fileName.toString()
  val prefix = "bill-$platform-$basePrefix"
  listOf("code-review", "quality-check").forEach { family ->
    collectCascadedSkillNamesFromFamily(packDir.resolve(family), prefix, out)
  }
}

internal fun collectCascadedSkillNamesFromFamily(familyDir: Path, basePrefix: String, out: MutableSet<String>) {
  if (!Files.isDirectory(familyDir, LinkOption.NOFOLLOW_LINKS)) return
  Files.list(familyDir).use { areaStream ->
    areaStream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { areaDir ->
      val name = areaDir.fileName.toString()
      if (name == basePrefix || name.startsWith("$basePrefix-")) {
        out += name
      }
    }
  }
}

internal fun collectHorizontalCascadePaths(repoRoot: Path, skillName: String, out: MutableSet<String>) {
  val direct = repoRoot.resolve("skills/$skillName")
  if (Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) out += "skills/$skillName"
  val packs = repoRoot.resolve("platform-packs")
  if (!Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) return
  Files.list(packs).use { stream ->
    stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
      collectPackCascadePaths(repoRoot, packDir, skillName, out)
    }
  }
}

internal fun collectPackCascadePaths(repoRoot: Path, packDir: Path, skillName: String, out: MutableSet<String>) {
  listOf("code-review", "quality-check").forEach { family ->
    val candidate = packDir.resolve(family).resolve(skillName)
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      out += repoRoot.relativize(candidate).toString().replace('\\', '/')
    }
  }
}

internal fun collectHorizontalManifestEditsForPack(
  repoRoot: Path,
  packDir: Path,
  skillName: String,
  edits: MutableList<ManifestEdit>,
) {
  val manifest = packDir.resolve("platform.yaml")
  if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return
  val manifestRel = repoRoot.relativize(manifest).toString().replace('\\', '/')
  val platform = packDir.fileName.toString()
  val slug = skillName.removePrefix("bill-")
  val baselineName = "bill-$platform-$slug"
  collectBaselineManifestEdits(packDir, manifestRel, baselineName, edits)
  collectAreaManifestEdits(packDir, manifestRel, baselineName, edits)
  collectQualityCheckManifestEdits(packDir, manifestRel, platform, slug, edits)
}

internal fun collectBaselineManifestEdits(
  packDir: Path,
  manifestRel: String,
  baselineName: String,
  edits: MutableList<ManifestEdit>,
) {
  val baselineDir = packDir.resolve("code-review").resolve(baselineName)
  if (!Files.isDirectory(baselineDir, LinkOption.NOFOLLOW_LINKS)) return
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

internal fun collectAreaManifestEdits(
  packDir: Path,
  manifestRel: String,
  baselineName: String,
  edits: MutableList<ManifestEdit>,
) {
  val areaPrefix = "$baselineName-"
  val codeReviewDir = packDir.resolve("code-review")
  if (!Files.isDirectory(codeReviewDir, LinkOption.NOFOLLOW_LINKS)) return
  Files.list(codeReviewDir).use { areaStream ->
    areaStream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { areaDir ->
      val name = areaDir.fileName.toString()
      if (!name.startsWith(areaPrefix)) return@forEach
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

internal fun collectQualityCheckManifestEdits(
  packDir: Path,
  manifestRel: String,
  platform: String,
  slug: String,
  edits: MutableList<ManifestEdit>,
) {
  val qcName = "bill-$platform-$slug"
  val qcDir = packDir.resolve("quality-check").resolve(qcName)
  if (!Files.exists(qcDir, LinkOption.NOFOLLOW_LINKS)) return
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
