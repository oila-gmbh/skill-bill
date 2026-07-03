package skillbill.scaffold.validation

import skillbill.scaffold.platformpack.resolveSkillClassForSkill
import skillbill.scaffold.runtime.scaffold
import java.nio.file.Path

private val UNRESOLVED_PLACEHOLDER_PATTERN = Regex("""(?m)^\s*(?:[-*]\s*)?(?:TODO|FIXME)\b""")
private val WRAPPER_BOILERPLATE_HEADING_PATTERN = Regex("""^##\s+(Descriptor|Execution|Ceremony)\s*$""")
private val SELF_REFERENTIAL_CONTENT_POINTER_PATTERN =
  Regex("""Follow the instructions in\s+\[content\.md\]\(content\.md\)\.""", RegexOption.IGNORE_CASE)
private val FENCE_START_PATTERN = Regex("""^\s*(?:```|~~~)""")
private val TITLE_HEADING_PATTERN = Regex("""^#\s+\S.*$""")

/**
 * Support pointer files that the renderer writes into installed staging and references from the
 * auto-generated `## Ceremony` section (see `renderCeremonySection`). Authored `content.md` must
 * not duplicate those links inline — the install-time ceremony already exposes them.
 *
 * Keep this set in lockstep with `renderCeremonySection` in
 * `runtime-core/.../scaffold/ScaffoldCeremonyRendering.kt`.
 */
private val GENERATED_SUPPORT_POINTER_FILENAMES: Set<String> =
  setOf(
    "shell-ceremony.md",
    "review-scope.md",
    "stack-routing.md",
    "specialist-contract.md",
    "review-delegation.md",
    "shell-content-contract.md",
    "review-orchestrator.md",
    "telemetry-contract.md",
    "peak-hours-warner.md",
  )

private val GENERATED_SUPPORT_POINTER_LINK_PATTERN =
  Regex("""\[(${GENERATED_SUPPORT_POINTER_FILENAMES.joinToString("|") { Regex.escape(it) }})]\(\1\)""")

/**
 * The renderer emits `### Subagent Spawn Runtime Notes` automatically when a skill has
 * `native-agents/` sources (see `renderSubagentSpawnRuntimeNotes`). Authored `content.md` must not
 * own that heading — neither at the literal name nor at the common shortened form — because the
 * wrapper will either duplicate it or fight with it after heading demotion.
 */
private val SUBAGENT_RUNTIME_NOTES_HEADING_PATTERN =
  Regex("""^#{1,6}\s+Subagent(?:\s+Spawn)?\s+Runtime\s+Notes\s*$""", RegexOption.IGNORE_CASE)

/**
 * Prose form of the same ceremony pointer the link-pattern check guards. Catches "follow the
 * shared <name> playbook" / "shared <name> contract" wording that re-directs readers to a
 * generated support pointer instead of just stating authored behavior. The bare slug is matched
 * (no `.md` suffix) so authors can't switch to prose to evade the link guard.
 */
private val GENERATED_SUPPORT_POINTER_SLUGS: String =
  GENERATED_SUPPORT_POINTER_FILENAMES.joinToString("|") { Regex.escape(it.removeSuffix(".md")) }

private val GENERATED_SUPPORT_POINTER_PROSE_PATTERN =
  Regex(
    """shared\s+(${GENERATED_SUPPORT_POINTER_SLUGS})\s+(?:playbook|contract|ceremony)""",
    RegexOption.IGNORE_CASE,
  )

internal fun validateAuthoredContent(contentFile: Path, text: String): List<String> {
  val body = markdownBodyAfterFrontmatter(text)
  val issues = mutableListOf<String>()
  val visibleLines = bodyVisibleLines(body)
  val hasSelfReferentialPointer = SELF_REFERENTIAL_CONTENT_POINTER_PATTERN.containsMatchIn(body)
  val skillClass = resolveSkillClassForPath(contentFile)
  val classDeclaresSections = skillClass != null && skillClass.sections.isNotEmpty()

  if (hasSelfReferentialPointer) {
    issues += "$contentFile: content.md contains self-referential wrapper pointer text instead of authored guidance"
  }
  // Skills whose class fully describes them (governed shells) may have an empty content.md body —
  // the framework prose lives in `orchestration/skill-classes/<class>.yaml` and is rendered into
  // SKILL.md at install time. For everyone else, we still require authored guidance beyond the title.
  if (!classDeclaresSections) {
    if (visibleLines.isEmpty()) {
      issues += "$contentFile: content.md is missing required authored content"
    } else if (guidanceLinesBeyondTitle(visibleLines).isEmpty()) {
      issues += "$contentFile: content.md must include authored guidance beyond the title heading"
    }
  }
  if (UNRESOLVED_PLACEHOLDER_PATTERN.containsMatchIn(body)) {
    issues += "$contentFile: content.md contains an unresolved TODO/FIXME placeholder"
  }
  wrapperBoilerplateHeadings(body).forEach { heading ->
    issues += "$contentFile: content.md must not contain generated wrapper boilerplate heading '$heading'"
  }
  generatedSupportPointerLinks(body).forEach { pointer ->
    issues += "$contentFile: content.md must not link to generated support pointer '$pointer' — " +
      "the install-time ceremony already exposes it"
  }
  subagentRuntimeNotesHeadings(body).forEach { heading ->
    issues += "$contentFile: content.md must not contain auto-generated subagent runtime notes heading '$heading'"
  }
  generatedSupportPointerProse(body).forEach { phrase ->
    issues += "$contentFile: content.md must not redirect readers to ceremony pointer '$phrase' — " +
      "state authored behavior directly and let the install-time ceremony expose the shared contract"
  }
  if (skillClass != null) {
    classSectionHeadingClashes(body, skillClass.sections.map { it.heading }).forEach { heading ->
      issues += "$contentFile: content.md must not redeclare class-owned section heading '## $heading' — " +
        "framework prose lives in orchestration/skill-classes/${skillClass.classId}.yaml"
    }
  }

  return issues
}

private fun bodyVisibleLines(body: String): List<String> {
  val lines = mutableListOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence) {
      line.trim().takeIf(String::isNotEmpty)?.let(lines::add)
    }
  }
  return lines
}

private fun guidanceLinesBeyondTitle(visibleLines: List<String>): List<String> {
  val bodyAfterTitle = if (visibleLines.firstOrNull()?.let(TITLE_HEADING_PATTERN::matches) == true) {
    visibleLines.drop(1)
  } else {
    visibleLines
  }
  return bodyAfterTitle.filterNot { line -> SELF_REFERENTIAL_CONTENT_POINTER_PATTERN.matches(line) }
}

private fun wrapperBoilerplateHeadings(body: String): List<String> {
  val headings = mutableSetOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence) {
      WRAPPER_BOILERPLATE_HEADING_PATTERN.matchEntire(line.trim())?.let { match ->
        headings += "## ${match.groupValues[1]}"
      }
    }
  }
  return headings.toList()
}

private fun generatedSupportPointerLinks(body: String): List<String> {
  val pointers = mutableSetOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence) {
      GENERATED_SUPPORT_POINTER_LINK_PATTERN.findAll(line).forEach { match ->
        pointers += match.groupValues[1]
      }
    }
  }
  return pointers.toList()
}

private fun subagentRuntimeNotesHeadings(body: String): List<String> {
  val headings = mutableListOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence && SUBAGENT_RUNTIME_NOTES_HEADING_PATTERN.matches(line.trim())) {
      headings += line.trim()
    }
  }
  return headings
}

private fun generatedSupportPointerProse(body: String): List<String> {
  val phrases = mutableSetOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence) {
      GENERATED_SUPPORT_POINTER_PROSE_PATTERN.findAll(line).forEach { match ->
        phrases += match.value.lowercase()
      }
    }
  }
  return phrases.toList()
}

private fun classSectionHeadingClashes(body: String, classHeadings: List<String>): List<String> {
  if (classHeadings.isEmpty()) return emptyList()
  val target = classHeadings.map { it.trim().lowercase() }.toSet()
  val seen = mutableSetOf<String>()
  var inFence = false
  body.lineSequence().forEach { line ->
    if (FENCE_START_PATTERN.containsMatchIn(line)) {
      inFence = !inFence
      return@forEach
    }
    if (!inFence && line.startsWith("## ")) {
      val candidate = line.removePrefix("## ").trim()
      if (candidate.lowercase() in target) {
        seen += candidate
      }
    }
  }
  return seen.toList()
}

/**
 * Resolve a skill class for a content.md file by walking up to the repo root and using the same
 * loader as the renderer. Returns null when no class manifest matches — that path lets the
 * validator stay permissive for non-governed test fixtures and ad-hoc repos.
 */
private fun resolveSkillClassForPath(contentFile: Path): skillbill.scaffold.model.SkillClassManifest? {
  val skillName = contentFile.parent?.fileName?.toString() ?: return null
  return runCatching { resolveSkillClassForSkill(skillName, contentFile) }.getOrNull()
}
