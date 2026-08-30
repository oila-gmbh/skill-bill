package skillbill.scaffold.platformpack

import java.nio.file.Path

internal fun parseDeclaredAreaFileEntries(
  rawFiles: Map<*, *>,
  slug: String,
  packRoot: Path,
  declaredAreas: List<String>,
): Map<String, Path> {
  val rawAreaFiles = rawFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
  val areaFiles = rawAreaFiles.entries.associate { (key, value) ->
    val area = key as? String
      ?: invalidManifestSchema(
        slug,
        "Platform pack '$slug': 'declared_files.areas' entries must be string->string.",
      )
    val relativePath = value as? String
      ?: invalidManifestSchema(
        slug,
        "Platform pack '$slug': 'declared_files.areas' entries must be string->string.",
      )
    area to packRoot.resolve(relativePath).normalize()
  }
  val extraAreaKeys = areaFiles.keys - declaredAreas.toSet()
  if (extraAreaKeys.isNotEmpty()) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': 'declared_files.areas' contains entries ${extraAreaKeys.sorted()} " +
        "that are not listed in 'declared_code_review_areas'.",
    )
  }
  val missingAreaKeys = declaredAreas.toSet() - areaFiles.keys
  if (missingAreaKeys.isNotEmpty()) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': 'declared_files.areas' is missing entries for ${missingAreaKeys.sorted()}.",
    )
  }
  return areaFiles
}

internal fun parseDeclaredBaselinePath(rawFiles: Map<*, *>, slug: String, packRoot: Path): Path? {
  val baselineRaw = rawFiles["baseline"] as? String ?: return null
  if (baselineRaw.isBlank()) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': 'declared_files.baseline' must be a non-empty path string when present.",
    )
  }
  return packRoot.resolve(baselineRaw).normalize()
}
