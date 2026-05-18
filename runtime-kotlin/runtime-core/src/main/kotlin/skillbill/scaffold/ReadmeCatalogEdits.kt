@file:Suppress("MaxLineLength", "ReturnCount")

package skillbill.scaffold

import java.nio.file.Path

/**
 * Helpers that edit the repository-level `README.md` Canonical Skills catalog when a horizontal
 * skill is removed (SKILL-46 AC3).
 *
 * The README catalog has two cross-references for every shipped horizontal skill:
 *
 * - A `| /<skill-name> | <purpose> |` row inside the `### Canonical Skills (N skills)` table.
 * - A `(N skills)` section-count badge embedded in the heading itself.
 *
 * We never throw on a missing landmark; the caller surfaces the [ReadmeEditOutcome.LandmarksMissing]
 * variant so the desktop dialog and the CLI can warn the user instead of blowing up the cascade.
 * This mirrors the loud-but-recoverable posture of the install-cleanup primitives.
 */
object ReadmeCatalogEdits {
  // Matches a catalog row like `| `/bill-foo` | description ... |` regardless of whether the
  // table uses straight or curly backticks for the skill name. We anchor on the leading `|` so a
  // skill name fragment inside body prose cannot match by accident.
  private fun catalogRowPattern(skillName: String): Regex = Regex(
    "^\\|\\s*[`\"]?/?${Regex.escape(skillName)}[`\"]?\\s*\\|[^\\n]*\\n",
    RegexOption.MULTILINE,
  )

  // Matches the `### Canonical Skills (N skills)` heading. We rewrite the integer in place; we do
  // NOT try to derive the count from the table itself because the table is in the middle of being
  // mutated when this helper fires.
  private val SECTION_COUNT_PATTERN = Regex(
    "^(### Canonical Skills \\()(\\d+)( skills?\\))\\s*$",
    RegexOption.MULTILINE,
  )

  /**
   * Strip the table row that mentions `/<skillName>` from the Canonical Skills table. Idempotent;
   * if no row matches the file is left untouched and the outcome reports
   * [ReadmeEditOutcome.LandmarksMissing].
   */
  fun removeCatalogRow(readmePath: Path, skillName: String): ReadmeEditOutcome {
    val original = readmePath.toFile().readText()
    val pattern = catalogRowPattern(skillName)
    val match = pattern.find(original) ?: return ReadmeEditOutcome.LandmarksMissing(
      "No catalog row found for `/$skillName` in README.md.",
    )
    val updated = original.replaceRange(match.range, "")
    if (updated == original) {
      return ReadmeEditOutcome.LandmarksMissing("Catalog row for `/$skillName` did not change.")
    }
    readmePath.toFile().writeText(updated)
    return ReadmeEditOutcome.Applied
  }

  /**
   * Decrement the `(N skills)` count in the Canonical Skills heading. Idempotent in the sense
   * that calling it with a 0 count is reported as [ReadmeEditOutcome.LandmarksMissing] rather than
   * silently underflowing.
   */
  fun decrementSectionCount(readmePath: Path): ReadmeEditOutcome {
    val original = readmePath.toFile().readText()
    val match = SECTION_COUNT_PATTERN.find(original) ?: return ReadmeEditOutcome.LandmarksMissing(
      "Canonical Skills heading with count badge not found in README.md.",
    )
    val current = match.groupValues[2].toIntOrNull()
      ?: return ReadmeEditOutcome.LandmarksMissing("Canonical Skills heading count is not an integer.")
    if (current <= 0) {
      return ReadmeEditOutcome.LandmarksMissing("Canonical Skills heading count is already 0.")
    }
    val next = current - 1
    val suffix = if (next == 1) " skill)" else " skills)"
    val replacement = "${match.groupValues[1]}$next$suffix"
    val updated = original.replaceRange(match.range, replacement)
    if (updated == original) {
      return ReadmeEditOutcome.LandmarksMissing("Canonical Skills heading count did not change.")
    }
    readmePath.toFile().writeText(updated)
    return ReadmeEditOutcome.Applied
  }
}

/** Sealed outcome reported by both helper functions. */
sealed class ReadmeEditOutcome {
  object Applied : ReadmeEditOutcome()

  data class LandmarksMissing(val reason: String) : ReadmeEditOutcome()
}
