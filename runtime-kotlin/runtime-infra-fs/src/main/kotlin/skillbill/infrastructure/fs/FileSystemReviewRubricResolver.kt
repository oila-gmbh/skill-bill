package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files

@Inject
class FileSystemReviewRubricResolver : ReviewRubricResolver {
  override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
    if (manifest == null) return ResolvedReviewRubric(GENERIC_RUBRIC_ID, GENERIC_RUBRIC)
    val baseline = requireNotNull(manifest.declaredFiles.baseline) {
      "Platform pack '${manifest.slug}' does not declare a code-review baseline."
    }.toRealPath()
    val packRoot = manifest.packRoot.toRealPath()
    require(baseline.startsWith(packRoot) && Files.isRegularFile(baseline) && !Files.isSymbolicLink(baseline)) {
      "Platform pack '${manifest.slug}' declares an unreadable code-review baseline."
    }
    val specialists = manifest.declaredCodeReviewAreas.map { area ->
      val file = requireNotNull(manifest.declaredFiles.areas[area]) {
        "Platform pack '${manifest.slug}' does not declare its '$area' specialist file."
      }.toRealPath()
      require(file.startsWith(packRoot) && Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
        "Platform pack '${manifest.slug}' declares an unreadable '$area' code-review rubric."
      }
      val body = Files.readString(file)
      require(body.toByteArray().size <= MAX_RUBRIC_BYTES) {
        "Platform pack '${manifest.slug}' declares a '$area' rubric larger than $MAX_RUBRIC_BYTES bytes."
      }
      ResolvedReviewRubric(
        rubricId = "bill-${manifest.slug}-code-review-$area",
        body = body,
        area = area,
      )
    }
    val baselineBody = Files.readString(baseline)
    require(baselineBody.toByteArray().size <= MAX_RUBRIC_BYTES) {
      "Platform pack '${manifest.slug}' declares a code-review baseline larger than $MAX_RUBRIC_BYTES bytes."
    }
    return ResolvedReviewRubric(
      requireNotNull(manifest.routedSkillName),
      baselineBody,
      specialists = specialists,
    )
  }

  override fun resolve(manifest: PlatformManifest?, diff: String, specialistSkillName: String): ResolvedReviewRubric {
    val resolved = resolve(manifest)
    if (manifest == null) return resolved
    val specialist = resolved.specialists.singleOrNull { it.rubricId == specialistSkillName } ?: resolved
    val consumer = "code-review/$specialistSkillName"
    val exact = manifest.addonUsage.firstOrNull { it.skillRelativeDir == consumer }?.addons.orEmpty()
    val baseline = manifest.routedSkillName?.let { baselineName ->
      manifest.addonUsage.firstOrNull { it.skillRelativeDir == "code-review/$baselineName" }?.addons.orEmpty()
    }.orEmpty().filter { applicableToArea(it.slug, specialist.area) }
    val selections = (exact + baseline).distinctBy { it.slug }
    val normalizedDiff = diff.lowercase()
    val commonMainOnly = normalizedDiff.contains("/commonmain/") &&
      !normalizedDiff.contains("/androidmain/") && !normalizedDiff.contains("androidmanifest.xml")
    val selected = selections.filter { selection ->
      val entrypoint = resolveAddonFile(manifest, selection.entrypoint)
      val entryBody = readBounded(entrypoint)
      !commonMainOnly && selection.activation?.let { condition ->
        condition.exclude.none(normalizedDiff::contains) &&
          condition.all.all(normalizedDiff::contains) &&
          (condition.any.any(normalizedDiff::contains) ||
            condition.anyOfAll.any { group -> group.all(normalizedDiff::contains) } ||
            (condition.any.isEmpty() && condition.anyOfAll.isEmpty()))
      } ?: activationTerms(entryBody, selection.slug).any(normalizedDiff::contains)
    }
    if (selected.isEmpty()) return specialist
    val guidance = selected.joinToString("\n\n") { selection ->
      (listOf(selection.entrypoint) + selection.companionPointers).joinToString("\n\n") { pointer ->
        readBounded(resolveAddonFile(manifest, pointer))
      }
    }
    require(guidance.toByteArray().size <= MAX_ADDON_BYTES) {
      "Selected add-on guidance for '$specialistSkillName' is larger than $MAX_ADDON_BYTES bytes."
    }
    return specialist.copy(
      body = specialist.body + "\n\n## Selected governed add-on guidance\n\n" + guidance,
      selectedAddOns = selected.map { it.slug },
    )
  }

  private fun resolveAddonFile(manifest: PlatformManifest, pointer: String): java.nio.file.Path {
    val candidate = manifest.packRoot.resolve("addons").resolve(pointer).normalize()
    require(!Files.isSymbolicLink(candidate)) {
      "Platform pack '${manifest.slug}' declares a symbolic add-on '$pointer'."
    }
    return candidate.toRealPath().also { path ->
      val addonsRoot = manifest.packRoot.resolve("addons").toRealPath()
      require(path.startsWith(addonsRoot) && Files.isRegularFile(path)) {
        "Platform pack '${manifest.slug}' declares an unreadable or escaping add-on '$pointer'."
      }
    }
  }

  private fun readBounded(path: java.nio.file.Path): String = Files.readString(path).also { body ->
    require(body.toByteArray().size <= MAX_ADDON_FILE_BYTES) {
      "Selected add-on '${path.fileName}' is larger than $MAX_ADDON_FILE_BYTES bytes."
    }
  }

  private fun activationTerms(body: String, slug: String): Set<String> {
    val activation = body.substringAfter("## Activation signals", "")
      .substringBefore("\n## ")
      .lowercase()
    return (TOKEN.findAll(activation).map { it.value } + slug.split('-').asSequence())
      .filter { it.length >= MIN_ACTIVATION_TERM_LENGTH && it !in STOP_WORDS }
      .toSet()
  }

  private fun applicableToArea(slug: String, area: String?): Boolean = when {
    slug == "android-r8" -> area == "platform-correctness"
    slug.startsWith("android-") -> area == "ui"
    slug == "offline-first" -> area in setOf("persistence", "reliability", "platform-correctness")
    else -> false
  }

  private companion object {
    const val MAX_RUBRIC_BYTES = 256 * 1024L
    const val MAX_ADDON_FILE_BYTES = 128 * 1024L
    const val MAX_ADDON_BYTES = 256 * 1024L
    const val MIN_ACTIVATION_TERM_LENGTH = 5
    val TOKEN = Regex("[a-z][a-z0-9_-]{4,}")
    val STOP_WORDS = setOf("select", "scoped", "includes", "contains", "changes", "review", "android")
    const val GENERIC_RUBRIC_ID = "parallel-code-review"
    const val GENERIC_RUBRIC =
      "Find concrete correctness, security, reliability, performance, and test gaps in the assigned change."
  }
}
