package skillbill.scaffold.validation

import java.nio.file.Files
import java.nio.file.Path

internal fun baselineViolations(file: Path): List<ReviewSkillStructureViolation> {
  val required = listOf(
    "Classification Rules",
    "Diff-Signal Routing Table",
    "Mixed Diffs",
    "Finding Discipline",
  )
  val content = Files.readString(file)
  val classification = h2Section(content, "Classification Rules")
  val routing = h2Section(content, "Diff-Signal Routing Table")
  val mixedDiffs = h2Section(content, "Mixed Diffs")
  val discipline = h2Section(content, "Finding Discipline")
  val composedMixedDiffs = composedBaselineSections(file, "Mixed Diffs")
  val composedDiscipline = composedBaselineSections(file, "Finding Discipline")
  return listOfNotNull(
    baselineHeadingSequenceViolation(file, required),
    baselineClassificationViolation(file, classification),
    baselineRoutingViolation(file, routing),
    baselineMixedDiffRetentionViolation(file, mixedDiffs),
    baselineScopingExclusionsViolation(file, mixedDiffs),
    baselineFindingDisciplineViolation(file, discipline),
    baselineSubagentOrderingViolation(file, composedMixedDiffs),
    baselineResultRetentionViolation(file, composedMixedDiffs),
    baselineAttributedMergeViolation(file, composedDiscipline),
    baselineDeduplicationViolation(file, composedDiscipline),
  )
}
