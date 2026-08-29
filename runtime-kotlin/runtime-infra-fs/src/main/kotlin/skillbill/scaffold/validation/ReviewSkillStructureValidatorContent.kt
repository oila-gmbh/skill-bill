package skillbill.scaffold.validation

import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal object ReviewSkillStructureValidatorContent {
  fun contentViolations(pack: Path, manifest: Map<*, *>, file: Path): List<ReviewSkillStructureViolation> {
    val parentViolation = if (ReviewSkillStructureValidatorHelpers.hasInternalParent(file, "bill-code-review")) {
      emptyList()
    } else {
      listOf(ReviewSkillStructureValidatorHelpers.violation(file, "code-review internal parent"))
    }
    val relativeFile = pack.relativize(file).let(ReviewSkillStructureValidatorHelpers::portablePath)
    return parentViolation + if (relativeFile == ReviewSkillStructureValidatorHelpers.declaredBaseline(manifest)) {
      baselineViolations(file)
    } else {
      specialistViolations(file, ReviewSkillStructureValidatorHelpers.declaredAreaForFile(manifest, relativeFile))
    }
  }

  private fun baselineViolations(file: Path): List<ReviewSkillStructureViolation> {
    val required = listOf("Classification Rules", "Diff-Signal Routing Table", "Mixed Diffs", "Finding Discipline")
    val content = Files.readString(file)
    val classification = ReviewSkillStructureValidatorHelpers.h2Section(content, "Classification Rules")
    val routing = ReviewSkillStructureValidatorHelpers.h2Section(content, "Diff-Signal Routing Table")
    val mixedDiffs = ReviewSkillStructureValidatorHelpers.h2Section(content, "Mixed Diffs")
    val discipline = ReviewSkillStructureValidatorHelpers.h2Section(content, "Finding Discipline")
    val composedMixedDiffs = ReviewSkillStructureValidatorHelpers.composedBaselineSections(file, "Mixed Diffs")
    val composedDiscipline = ReviewSkillStructureValidatorHelpers.composedBaselineSections(file, "Finding Discipline")
    return buildList {
      if (ReviewSkillStructureValidatorHelpers.headings(file) != required) add(ReviewSkillStructureValidatorHelpers.violation(file, "baseline H2 sequence"))
      if (!classification.contains("If ") || !classification.contains("Otherwise")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "classification decisions"))
      }
      val declaredAreas = ReviewSkillStructureValidatorHelpers.declaredAreasForContent(file)
      if (declaredAreas.isEmpty() || declaredAreas.any { area ->
          !Regex("(?m)^- .+ -> `$area` specialist\\.$").containsMatchIn(routing)
        }
      ) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "signal-to-specialist routing mappings"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(mixedDiffs, "keep", "whole review", "lightweight", "file-level classification")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "mixed-diff retention"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(mixedDiffs, "specialist", "scope", "generated", "vendored", "non-stack")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "scoping exclusions"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(discipline, "severity", "precondition")) add(ReviewSkillStructureValidatorHelpers.violation(file, "finding discipline"))
      if (!ReviewSkillStructureValidatorHelpers.containsAll(composedMixedDiffs, "deterministic", "subagent", "harness")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "deterministic subagent launch ordering"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(composedMixedDiffs, "retain", "every selected", "result")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "selected specialist result retention"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(composedDiscipline, "attributed", "merge")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "attributed finding merge"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(composedDiscipline, "deduplicat", "without losing", "evidence")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "evidence-preserving deduplication"))
      }
    }
  }

  private fun specialistViolations(file: Path, area: String?): List<ReviewSkillStructureViolation> {
    val required = listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules")
    val content = Files.readString(file)
    val projectRules = ReviewSkillStructureValidatorHelpers.h2Section(content, "Project-Specific Rules")
    return buildList {
      if (ReviewSkillStructureValidatorHelpers.headings(file) != required && ReviewSkillStructureValidatorHelpers.headings(file) != required + "Repo-Local Knowledge") {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "specialist H2 sequence"))
      }
      if (!projectRules.contains("### ")) add(ReviewSkillStructureValidatorHelpers.violation(file, "specialist H3 grouping"))
      if (ReviewSkillStructureValidatorHelpers.definesOwnSeverityVocabulary(content)) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "specialist defines own severity vocabulary"))
      }
      if (!ReviewSkillStructureValidatorHelpers.hasCanonicalSeverityCloser(area, content)) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "missing canonical severity closer"))
      }
      if (!projectRules.contains('`') ||
        !Regex("(?i)\\b(must|do not|never|require|reject|verify|preserve)\\b").containsMatchIn(projectRules) ||
        !Regex("(?i)failure|error|invariant|boundary").containsMatchIn(projectRules)
      ) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "enforceable stack failure modes"))
      }
      if (Regex("(?i)\\b(run|invoke|spawn)\\b[^\\n]*bill-[a-z0-9-]+-code-review-").containsMatchIn(content)) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "sibling specialist invocation"))
      }
      if (area == "ui" && !ReviewSkillStructureValidatorHelpers.containsAll(ReviewSkillStructureValidatorHelpers.ignoreSection(content), "ux-accessibility", "security")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "UI lane deferrals"))
      }
      if (area == "ux-accessibility" && !ReviewSkillStructureValidatorHelpers.containsAll(ReviewSkillStructureValidatorHelpers.ignoreSection(content), "ui", "security")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "UX accessibility lane deferrals"))
      }
    }
  }

  fun nativeAgentViolations(pack: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val baseline = ReviewSkillStructureValidatorHelpers.declaredBaseline(manifest) ?: return emptyList()
    val agentsFile = pack.resolve(baseline).parent.resolve("native-agents/agents.yaml")
    if (!Files.isRegularFile(agentsFile)) return listOf(ReviewSkillStructureValidatorHelpers.violation(agentsFile, "native-agent source bundle"))
    val agents = try {
      parseNativeAgentBundle(agentsFile)
    } catch (error: IllegalArgumentException) {
      ReviewSkillStructureValidatorHelpers.invalidNativeAgentBundle(agentsFile, error)
    } catch (error: IOException) {
      ReviewSkillStructureValidatorHelpers.invalidNativeAgentBundle(agentsFile, error)
    }
    val displayName = (manifest["display_name"] ?: manifest["platform"]).toString()
    val areaMetadata = manifest["area_metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val expectedDescriptions = areaMetadata.mapNotNull { (rawArea, rawMetadata) ->
      val area = rawArea as? String ?: return@mapNotNull null
      val focus = (rawMetadata as? Map<*, *>)?.get("focus") as? String ?: return@mapNotNull null
      "bill-${pack.name}-code-review-$area" to
        "$displayName ${area.replace('-', ' ')} specialist — $focus."
    }.toMap()
    return buildList {
      if (agents.any { agent -> expectedDescriptions[agent.name] != agent.description }) {
        add(ReviewSkillStructureValidatorHelpers.violation(agentsFile, "native-agent description pattern"))
      }
      val expectedNames = ReviewSkillStructureValidatorHelpers.declaredAreas(manifest).map { "bill-${pack.name}-code-review-$it" }.toSet()
      if (agents.map { it.name }.toSet() != expectedNames) {
        add(ReviewSkillStructureValidatorHelpers.violation(agentsFile, "native-agent specialist coverage"))
      }
    }
  }

  fun qualityCheckViolations(pack: Path, manifest: Map<*, *>): List<ReviewSkillStructureViolation> {
    val declared = manifest["declared_quality_check_file"] as? String ?: return emptyList()
    val file = pack.resolve(declared)
    if (!Files.isRegularFile(file)) return listOf(ReviewSkillStructureValidatorHelpers.violation(file, "declared quality-check source"))
    val content = Files.readString(file)
    val execution = ReviewSkillStructureValidatorHelpers.h2Section(content, "Execution Steps")
    val fixStrategy = ReviewSkillStructureValidatorHelpers.h2Section(content, "Fix Strategy")
    return buildList {
      if (!ReviewSkillStructureValidatorHelpers.hasInternalParent(file, "bill-code-check")) add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check internal parent"))
      if (ReviewSkillStructureValidatorHelpers.headings(file) != listOf("Purpose", "Execution Steps", "Fix Strategy")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check H2 sequence"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(execution, "build file", "wrapper", "CI")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check command discovery"))
      }
      if (!ReviewSkillStructureValidatorHelpers.orderedFragments(execution, "build file", "wrapper", "CI configuration", "before falling back")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check fallback ordering"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(execution, "files in scope")) add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check scoped files"))
      if (!ReviewSkillStructureValidatorHelpers.containsAll(execution, "pack's quality-check entrypoint")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check pack entrypoint"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(fixStrategy, "priority-ordered", "never suppress")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check fix discipline"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(fixStrategy, "Repair Window") || !ReviewSkillStructureValidatorHelpers.containsAll(fixStrategy, "do not invoke")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check repair window"))
      }
      if (!ReviewSkillStructureValidatorHelpers.containsAll(fixStrategy, "full suite when targeted checks cannot establish safety")) {
        add(ReviewSkillStructureValidatorHelpers.violation(file, "quality-check escalation"))
      }
    }
  }

  fun authoredSidecarViolations(
    reviewFiles: List<Path>,
    manifest: Map<*, *>,
  ): List<ReviewSkillStructureViolation> = reviewFiles
    .filter { !it.parent.name.endsWith("code-review") }
    .flatMap { contentFile ->
      Files.list(contentFile.parent).use { siblings ->
        val sidecars = siblings.filter { it.fileName.toString().endsWith(".md") && it != contentFile }.toList()
        if (sidecars.size > 1) return@use listOf(ReviewSkillStructureValidatorHelpers.violation(contentFile, "one authored rubric sidecar"))
        val sidecar = sidecars.singleOrNull() ?: return@use emptyList()
        val sourceContent = Files.readString(contentFile)
        val sidecarContent = Files.readString(sidecar)
        buildList {
          if (sidecar.fileName.toString().lowercase() in ReviewSkillStructureValidatorHelpers.reservedGeneratedSidecarNames(manifest)) {
            add(ReviewSkillStructureValidatorHelpers.violation(sidecar, "reserved generated sidecar name"))
          }
          if (ReviewSkillStructureValidatorHelpers.containsWrapperOrProviderOutput(sidecarContent)) {
            add(ReviewSkillStructureValidatorHelpers.violation(sidecar, "wrapper or provider sidecar content"))
          }
          if (!ReviewSkillStructureValidatorHelpers.isSpecialistRubric(sidecarContent)) add(ReviewSkillStructureValidatorHelpers.violation(sidecar, "specialist rubric sidecar content"))
          if (!sourceContent.contains(sidecar.fileName.toString()) ||
            !Regex("(?i)insufficient").containsMatchIn(sourceContent)
          ) {
            add(ReviewSkillStructureValidatorHelpers.violation(contentFile, "authored rubric sidecar rationale"))
          }
        }
      }
    }
}
