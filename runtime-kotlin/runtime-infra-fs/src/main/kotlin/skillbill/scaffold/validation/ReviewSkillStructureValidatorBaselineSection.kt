package skillbill.scaffold.validation

import java.nio.file.Path

internal fun baselineHeadingSequenceViolation(file: Path, required: List<String>): ReviewSkillStructureViolation? =
  violation(file, "baseline H2 sequence").takeIf { headings(file) != required }

internal fun baselineClassificationViolation(file: Path, classification: String): ReviewSkillStructureViolation? =
  violation(file, "classification decisions")
    .takeIf { !classification.contains("If ") || !classification.contains("Otherwise") }

internal fun baselineRoutingViolation(file: Path, routing: String): ReviewSkillStructureViolation? {
  val declaredAreas = declaredAreasForContent(file)
  val invalid = declaredAreas.isEmpty() || declaredAreas.any { area ->
    !Regex("(?m)^- .+ -> `$area` specialist\\.$").containsMatchIn(routing)
  }
  return violation(file, "signal-to-specialist routing mappings").takeIf { invalid }
}

internal fun baselineMixedDiffRetentionViolation(file: Path, mixedDiffs: String): ReviewSkillStructureViolation? =
  violation(file, "mixed-diff retention")
    .takeUnless {
      containsAll(
        mixedDiffs,
        "keep",
        "whole review",
        "lightweight",
        "file-level classification",
      )
    }

internal fun baselineScopingExclusionsViolation(file: Path, mixedDiffs: String): ReviewSkillStructureViolation? =
  violation(file, "scoping exclusions")
    .takeUnless {
      containsAll(
        mixedDiffs,
        "specialist",
        "scope",
        "generated",
        "vendored",
        "non-stack",
      )
    }

internal fun baselineFindingDisciplineViolation(file: Path, discipline: String): ReviewSkillStructureViolation? =
  violation(file, "finding discipline").takeUnless { containsAll(discipline, "severity", "precondition") }

internal fun baselineSubagentOrderingViolation(file: Path, composedMixedDiffs: String): ReviewSkillStructureViolation? =
  violation(file, "deterministic subagent launch ordering")
    .takeUnless { containsAll(composedMixedDiffs, "deterministic", "subagent", "harness") }

internal fun baselineResultRetentionViolation(file: Path, composedMixedDiffs: String): ReviewSkillStructureViolation? =
  violation(file, "selected specialist result retention")
    .takeUnless { containsAll(composedMixedDiffs, "retain", "every selected", "result") }

internal fun baselineAttributedMergeViolation(file: Path, composedDiscipline: String): ReviewSkillStructureViolation? =
  violation(file, "attributed finding merge")
    .takeUnless { containsAll(composedDiscipline, "attributed", "merge") }

internal fun baselineDeduplicationViolation(file: Path, composedDiscipline: String): ReviewSkillStructureViolation? =
  violation(file, "evidence-preserving deduplication")
    .takeUnless { containsAll(composedDiscipline, "deduplicat", "without losing", "evidence") }
