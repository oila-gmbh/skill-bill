package skillbill.scaffold.rendering

import skillbill.scaffold.model.SkillClassManifest
import skillbill.scaffold.model.SkillClassSection

/**
 * Render the `## Ceremony` block. Body lines are sourced from the matched [skillClass]'s
 * `ceremony_lines`. Skills with no matched class emit just the heading — callers will not have
 * installed any support pointers for them anyway.
 */
internal fun renderCeremonySection(skillClass: SkillClassManifest?): String = buildString {
  appendLine("## Ceremony")
  skillClass?.ceremonyLines?.forEach { line ->
    appendLine()
    append(line)
    appendLine()
  }
}

/**
 * Render the framework-owned H2 sections that sit between `## Descriptor` and `## Execution`.
 * Each section emits as `## <heading>` followed by its verbatim body (one trailing blank line).
 */
internal fun renderClassSections(sections: List<SkillClassSection>): String = buildString {
  sections.forEach { section ->
    append("## ")
    appendLine(section.heading)
    appendLine()
    append(section.body.trimEnd())
    appendLine()
    appendLine()
  }
}
