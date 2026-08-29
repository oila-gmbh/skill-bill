package skillbill.scaffold.validation

import java.nio.file.Path

internal object ReviewSkillStructureValidatorManifest {
  fun manifestViolations(pack: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val manifestFile = pack.resolve("platform.yaml")
    val declaredFiles = manifest["declared_files"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val areas = declaredFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val declaredContent = ReviewSkillStructureValidatorHelpers.declaredContentFiles(declaredFiles, areas)
    val actualContent = ReviewSkillStructureValidatorHelpers.contentFiles(pack).map { pack.relativize(it).let(ReviewSkillStructureValidatorHelpers::portablePath) }.toSet()
    val declaredAreas = (manifest["declared_code_review_areas"] as? List<*>)?.map(Any?::toString)?.toSet().orEmpty()
    val areaKeys = areas.keys.map(Any?::toString).toSet()
    val metadata = manifest["area_metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val metadataKeys = metadata.keys.map(Any?::toString).toSet()
    val packLabel = (manifest["display_name"] ?: manifest["platform"]).toString()
    val focuses = metadata.mapNotNull { (rawArea, rawMetadata) ->
      val area = rawArea as? String ?: return@mapNotNull null
      val focus = (rawMetadata as? Map<*, *>)?.get("focus") as? String ?: return@mapNotNull null
      area to focus
    }
    return buildList {
      if (declaredContent != actualContent) add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "manifest declares every review content file"))
      if (declaredAreas != areaKeys || areaKeys != metadataKeys) {
        add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "manifest review area parity"))
      }
      if (!ReviewSkillStructureValidatorHelpers.hasBespokeFocuses(focuses, metadata.size, packLabel)) {
        add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "manifest bespoke area metadata"))
      }
      addAll(routingViolations(manifestFile, manifest))
      addAll(pointerViolations(manifestFile, manifest))
    }
  }

  private fun routingViolations(manifestFile: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val routing = manifest["routing_signals"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val strongSignals = (routing["strong"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val tieBreakers = (routing["tie_breakers"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val fallbackOnly = (manifest["fallback_capabilities"] as? List<*>)
      ?.filterIsInstance<String>()
      ?.isNotEmpty() == true
    return buildList {
      strongSignals.filter { it.matches(Regex("\\*?\\.[A-Za-z0-9]+")) }.forEach { signal ->
        val counterpart = if (signal.startsWith("*.")) signal.removePrefix("*") else "*$signal"
        if (counterpart !in strongSignals) add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "routing bare/glob pair"))
      }
      if (fallbackOnly) return@buildList
      if (tieBreakers.none(ReviewSkillStructureValidatorHelpers::statesPositivePackDominance)) {
        add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "routing positive pack dominance"))
      }
      if (tieBreakers.none(ReviewSkillStructureValidatorHelpers::statesAdjacentPackDisambiguation)) {
        add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "routing adjacent-pack disambiguation"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(tieBreakers.joinToString(" "), "exclude", "generated", "vendor", "dominan")) {
        add(ReviewSkillStructureValidatorHelpers.violation(manifestFile, "routing generated and vendored exclusion"))
      }
    }
  }

  private fun pointerViolations(manifestFile: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val declaredFiles = manifest["declared_files"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val areas = declaredFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val pointers = manifest["pointers"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val expected = ReviewSkillStructureValidatorHelpers.declaredContentFiles(declaredFiles, areas).map { it.removeSuffix("/content.md") }.toSet()
    val actual = pointers.keys.map(Any?::toString).filter { it.startsWith("code-review/") }.toSet()
    return if (actual == expected) {
      emptyList()
    } else {
      listOf(
        ReviewSkillStructureValidatorHelpers.violation(manifestFile, "generated pointers for declared review skills"),
      )
    }
  }
}
