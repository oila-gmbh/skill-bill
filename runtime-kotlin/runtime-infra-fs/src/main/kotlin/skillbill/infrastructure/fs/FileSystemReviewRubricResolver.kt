package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.review.plan.ReviewContentMatcher
import skillbill.review.plan.ReviewPathMatcher
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

  @Suppress("CyclomaticComplexMethod")
  override fun resolve(
    manifest: PlatformManifest?,
    evidence: List<ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric {
    val resolved = resolve(manifest)
    if (manifest == null) return resolved
    val specialist = resolved.specialists.singleOrNull { it.rubricId == specialistSkillName } ?: resolved
    val consumer = "code-review/$specialistSkillName"
    val baselineConsumer = manifest.routedSkillName?.let { "code-review/$it" }
    val selections = manifest.addonUsage.flatMap { usage ->
      when (usage.skillRelativeDir) {
        consumer -> usage.addons
        baselineConsumer -> usage.addons.filter { specialist.area in it.specialistAreas }
        else -> emptyList()
      }
    }.distinctBy { it.slug }
    val selected = selections.filter { selection ->
      val condition = requireNotNull(selection.activation) {
        "Governed review add-on '${selection.slug}' has no structured activation."
      }
      val eligible = evidence.filterNot { file ->
        condition.excludePath.any { ReviewPathMatcher.matches(file.path, it) } ||
          condition.excludeContent.any { ReviewContentMatcher.contains(file.changedContent, it) }
      }
      val eligibleContent = eligible.joinToString("\n") { it.changedContent }
      eligible.isNotEmpty() && ReviewContentMatcher.containsAll(eligibleContent, condition.allContent) &&
        (
          condition.anyPath.any { signal -> eligible.any { ReviewPathMatcher.matches(it.path, signal) } } ||
            condition.anyContent.any { ReviewContentMatcher.contains(eligibleContent, it) } ||
            condition.anyOfAllContent.any { group -> ReviewContentMatcher.containsAll(eligibleContent, group) } ||
            (condition.anyPath.isEmpty() && condition.anyContent.isEmpty() && condition.anyOfAllContent.isEmpty())
          )
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

  private companion object {
    const val MAX_RUBRIC_BYTES = 256 * 1024L
    const val MAX_ADDON_FILE_BYTES = 128 * 1024L
    const val MAX_ADDON_BYTES = 256 * 1024L
    const val GENERIC_RUBRIC_ID = "parallel-code-review"
    const val GENERIC_RUBRIC =
      "Find concrete correctness, security, reliability, performance, and test gaps in the assigned change."
  }
}
