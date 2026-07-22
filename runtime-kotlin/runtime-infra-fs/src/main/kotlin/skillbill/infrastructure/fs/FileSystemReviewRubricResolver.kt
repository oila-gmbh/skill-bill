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
    }.orEmpty().filter { specialist.area in it.specialistAreas }
    val selections = (exact + baseline).distinctBy { it.slug }
    val evidence = activationEvidence(diff)
    val selected = selections.filter { selection ->
      val condition = requireNotNull(selection.activation) {
        "Governed review add-on '${selection.slug}' has no structured activation."
      }
      condition.excludePath.none { signal -> evidence.paths.any { matchesPath(it, signal) } } &&
        condition.excludeContent.none(evidence.content::contains) &&
        condition.allContent.all(evidence.content::contains) &&
        (condition.anyPath.any { signal -> evidence.paths.any { matchesPath(it, signal) } } ||
          condition.anyContent.any(evidence.content::contains) ||
          condition.anyOfAllContent.any { group -> group.all(evidence.content::contains) } ||
          (condition.anyPath.isEmpty() && condition.anyContent.isEmpty() && condition.anyOfAllContent.isEmpty()))
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

  private fun activationEvidence(diff: String): ActivationEvidence {
    val paths = Regex("""(?m)^\+\+\+ b/(.+)$""").findAll(diff).map { it.groupValues[1].lowercase() }.toList()
    val content = diff.lineSequence()
      .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
      .joinToString("\n").lowercase()
    return ActivationEvidence(paths, content)
  }

  private fun matchesPath(path: String, signal: String): Boolean {
    val normalized = signal.lowercase().removePrefix("*")
    return if ('/' in normalized) path.contains(normalized.trim('/')) else path.endsWith(normalized) || path.contains(normalized)
  }

  private data class ActivationEvidence(val paths: List<String>, val content: String)

  private companion object {
    const val MAX_RUBRIC_BYTES = 256 * 1024L
    const val MAX_ADDON_FILE_BYTES = 128 * 1024L
    const val MAX_ADDON_BYTES = 256 * 1024L
    const val GENERIC_RUBRIC_ID = "parallel-code-review"
    const val GENERIC_RUBRIC =
      "Find concrete correctness, security, reliability, performance, and test gaps in the assigned change."
  }
}
