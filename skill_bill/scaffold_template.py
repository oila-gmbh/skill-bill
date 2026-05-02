"""Scaffolder-owned section templates (SKILL-15).

These templates render the scaffolder-owned governed sections that MUST stay
byte-identical across every governed specialist:

- ``## Execution``
- ``## Ceremony``

Governed skills also auto-render ``## Descriptor`` from scaffold context plus
manifest-owned area metadata so drift can be detected later by the loader.
Every other section is authored by humans and may vary per skill.

One default per slot: every required H2 section has exactly one default body
here. Callers extend the skill by editing authored sidecars, not the governed
pointer sections; regenerating from the same payload yields byte-identical
output.
"""

from __future__ import annotations

from dataclasses import dataclass
import re


# Compiled once at import-time because :func:`extract_scaffolder_owned`
# is invoked from validator paths that may loop over hundreds of skill files.
_SCAFFOLDER_OWNED_HEADINGS: tuple[str, ...] = ("## Execution", "## Ceremony")

_H2_PATTERN = re.compile(r"^##\s+[^\n]+$", re.MULTILINE)


@dataclass(frozen=True)
class ScaffoldTemplateContext:
  """Inputs to the stored templates.

  Attributes:
    skill_name: canonical ``bill-...`` slug for the skill.
    family: capability family (``code-review``, ``quality-check``, ...).
    platform: platform slug; empty string for horizontal skills.
    area: code-review area slug; empty string for non-area kinds.
    display_name: human-friendly platform label (e.g. ``Java``); empty for
      horizontal skills. Used to render readable default descriptions.
  """

  skill_name: str
  family: str
  platform: str = ""
  area: str = ""
  display_name: str = ""


@dataclass(frozen=True)
class DescriptorMetadata:
  """Manifest-owned descriptor hints for governed skills."""

  area_focus: str = ""


_AREA_DESCRIPTION_PHRASES: dict[str, str] = {
  "architecture": "architecture, boundaries, and dependency direction",
  "performance": "performance risks on hot paths, blocking I/O, and resource usage",
  "platform-correctness": "lifecycle, concurrency, threading, and logic correctness",
  "security": "secrets handling, auth, and sensitive-data exposure",
  "testing": "test coverage quality and regression protection",
  "api-contracts": "API contracts, request validation, and serialization",
  "persistence": "persistence, transactions, migrations, and data consistency",
  "reliability": "timeouts, retries, background work, and observability",
  "ui": "UI correctness and framework usage",
  "ux-accessibility": "UX correctness and accessibility",
}


def default_area_focus(area: str) -> str:
  """Return the canonical manifest-backed focus text for an approved area."""
  return _AREA_DESCRIPTION_PHRASES.get(area, f"{area.replace('-', ' ')} risks")


def infer_skill_description(
  context: ScaffoldTemplateContext,
  *,
  area_focus: str = "",
) -> str:
  """Synthesize a one-line description from the context signals.

  The scaffolder calls this wherever a description has not been supplied by
  the payload, so generated skills ship with readable defaults instead of a
  `TODO` placeholder. The text is intentionally generic; callers who want
  bespoke wording supply ``description`` in the payload.
  """
  label = context.display_name or context.platform
  family = context.family
  area = context.area

  if family == "code-review":
    if area:
      phrase = area_focus or default_area_focus(area)
      if label:
        return f"Use when reviewing {label} changes for {phrase}."
      return f"Use when reviewing changes for {phrase}."
    if label:
      return f"Use when reviewing {label} changes across code-review specialists."
    return "Use when reviewing code changes across code-review specialists."

  if family == "quality-check":
    if label:
      return f"Use when validating {label} changes with the shared quality-check contract."
    return "Use when validating changes with the shared quality-check contract."

  if family == "feature-implement":
    if label:
      return (
        f"Use when implementing a feature end-to-end in {label} codebases, "
        "from design doc to verified code."
      )
    return "Use when implementing a feature end-to-end from design doc to verified code."

  if family == "feature-verify":
    if label:
      return f"Use when verifying a {label} PR against its task spec."
    return "Use when verifying a PR against its task spec."

  if family == "add-on":
    if label:
      return f"Pack-owned supporting asset for the {label} platform pack."
    return "Pack-owned supporting asset."

  if context.skill_name:
    readable = context.skill_name.removeprefix("bill-").replace("-", " ")
    return f"Use for {readable} work."
  return "Use for cross-stack work."


def render_descriptor_section(
  context: ScaffoldTemplateContext,
  *,
  metadata: DescriptorMetadata | None = None,
) -> str:
  """Render the governed ``## Descriptor`` section.

  The descriptor is intentionally deterministic so the loader can recompute it
  from scaffold context and manifest metadata and loud-fail on drift.
  """
  metadata = metadata or DescriptorMetadata()
  description = infer_skill_description(context, area_focus=metadata.area_focus)
  lines = [
    "## Descriptor",
    "",
    f"Governed skill: `{context.skill_name}`",
    f"Family: `{context.family}`",
  ]
  if context.platform:
    label = context.display_name or context.platform
    lines.append(f"Platform pack: `{context.platform}` ({label})")
  if context.area:
    lines.append(f"Area: `{context.area}`")
  lines.append(f"Description: {description}")
  return "\n".join(lines) + "\n"


CANONICAL_EXECUTION_SECTION = (
  "## Execution\n"
  "\n"
  "Follow the instructions in [content.md](content.md).\n"
)


CANONICAL_CEREMONY_SECTION = (
  "## Ceremony\n"
  "\n"
  "Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).\n"
)


def render_ceremony_section(context: ScaffoldTemplateContext) -> str:
  """Render the canonical ``## Ceremony`` section for wrapper skills.

  Keep the wrapper thin: a fixed ceremony preamble plus pointer lines to
  supporting files that the skill actually consumes. No fenced code, no
  embedded templates, no run-context placeholder lines. Run-context state
  (review session id, review run id, applied learnings) lives in
  ``content.md`` for the skills that emit it.
  """
  body = CANONICAL_CEREMONY_SECTION
  if _skill_requires_review_scope(context.skill_name):
    body += "\nDetermine the review scope using [review-scope.md](review-scope.md).\n"
  if _skill_requires_stack_routing(context.skill_name):
    body += "\nWhen stack routing applies, follow [stack-routing.md](stack-routing.md).\n"
  if _skill_requires_specialist_contract(context.skill_name):
    body += "\nWhen delegated specialist review applies, use [specialist-contract.md](specialist-contract.md).\n"
  if _skill_requires_review_delegation(context.skill_name):
    body += "\nWhen delegated review execution applies, follow [review-delegation.md](review-delegation.md).\n"
  if _skill_requires_review_orchestrator(context.skill_name):
    body += "\nWhen review reporting applies, follow [review-orchestrator.md](review-orchestrator.md).\n"
  if _skill_requires_telemetry_contract(context.skill_name):
    body += "\nWhen telemetry applies, follow [telemetry-contract.md](telemetry-contract.md).\n"
  return body


def render_skill_frontmatter(skill_name: str, description: str) -> str:
  """Render canonical skill frontmatter."""
  return (
    "---\n"
    f"name: {skill_name}\n"
    f"description: {description}\n"
    "---\n"
  )


def render_execution_mode_reporting(context: ScaffoldTemplateContext) -> str:
  """Render the ``## Execution Mode Reporting`` section body.

  The body is deliberately terse — a single default per slot, identical
  across every specialist in the same family. Callers that need bespoke
  wording should not extend the template; they should keep the skill
  uniform and capture specialization in other (human-authored) sections.
  """
  family_label = context.family or "skill"
  return (
    "## Execution Mode Reporting\n"
    "\n"
    f"When this {family_label} skill runs, report the execution mode on its own line:\n"
    "\n"
    "```\n"
    "Execution mode: inline | delegated\n"
    "```\n"
    "\n"
    "- `inline` — the current agent handled the work directly.\n"
    "- `delegated` — the current agent dispatched the work to a specialist "
    "subagent or a sibling skill.\n"
  )


def render_telemetry_ceremony_hooks(context: ScaffoldTemplateContext) -> str:
  """Render the ``## Telemetry Ceremony Hooks`` section body.

  The body points every specialist in the family at the same telemetry
  contract sidecar (``telemetry-contract.md``) rather than duplicating the
  protocol per skill. This is what lets the scaffolder guarantee that the
  section is byte-identical across siblings in a family.
  """
  del context  # intentionally unused; all specialists share the same body
  return (
    "## Telemetry Ceremony Hooks\n"
    "\n"
    "Follow the standalone-first telemetry contract documented in the sibling\n"
    "`telemetry-contract.md` file:\n"
    "\n"
    "- Emit a single `*_started` event at the top of the ceremony.\n"
    "- Emit a single `*_finished` event at the bottom of the ceremony.\n"
    "- Routers aggregate `child_steps` but never emit their own `*_started` or\n"
    "  `*_finished` events.\n"
    "- Degrade gracefully when telemetry is disabled: the skill must still run\n"
    "  to completion without an MCP connection.\n"
  )


def render_description_section(context: ScaffoldTemplateContext) -> str:
  """Render the ``## Description`` section with an inferred default body.

  Unlike the two scaffolder-owned ceremony sections, the Description body
  here is a *seed* — callers may freely edit it after scaffolding. Seeding
  it beats a `TODO:` marker because every signal we need (family, platform,
  area) is already on the context when the skill is created.
  """
  return (
    "## Description\n"
    "\n"
    f"{infer_skill_description(context)}\n"
  )


def _todo_section(heading: str, skill_name: str) -> str:
  humanized = heading.removeprefix("## ").strip().lower()
  return f"{heading}\n\nTODO: author the {humanized} section for `{skill_name}`.\n"


def render_specialist_scope_section(context: ScaffoldTemplateContext) -> str:
  """Render a ``## Specialist Scope`` seed, family- and area-aware.

  Seeds are preferred over ``TODO`` because they describe what the section
  expects. They remain author-editable — callers who want a different
  boundary replace the seed after scaffolding.
  """
  label = context.display_name or context.platform
  family = context.family
  area = context.area

  if family == "code-review":
    if area:
      phrase = _AREA_DESCRIPTION_PHRASES.get(area, f"{area.replace('-', ' ')} risks")
      subject = f"{label} changes" if label else "changes under review"
      return (
        "## Specialist Scope\n"
        "\n"
        f"This specialist covers {phrase} in {subject}.\n"
        "\n"
        "Out of scope: other code-review areas, which are delegated to their own "
        "specialists declared under `declared_code_review_areas` in the owning "
        "`platform.yaml`.\n"
      )
    subject = f"{label} changes" if label else "the diff"
    return (
      "## Specialist Scope\n"
      "\n"
      f"This skill reviews {subject} in one of two modes, chosen at runtime "
      "based on the pack's `declared_code_review_areas` in `platform.yaml`:\n"
      "\n"
      "- **Delegated** — when areas are declared, route the diff to each "
      "specialist and aggregate findings. See the Delegated Mode section below.\n"
      "- **Inline** — when no areas are declared, do the full review here in a "
      "single pass. See the Inline Mode section below.\n"
      "\n"
      "Out of scope: quality-check work (lint, format, tests) and platform "
      "behavior governed by add-ons.\n"
    )

  if family == "feature-implement":
    subject = f"a {label} feature" if label else "a feature"
    return (
      "## Specialist Scope\n"
      "\n"
      f"This skill drives end-to-end implementation of {subject}, from design doc "
      "through planning, implementation, completeness audit, quality-check, and "
      "PR description.\n"
      "\n"
      "Out of scope: verification of an existing PR against a spec — that is the "
      "feature-verify skill.\n"
    )

  if family == "feature-verify":
    subject = f"a {label} PR" if label else "a PR"
    return (
      "## Specialist Scope\n"
      "\n"
      f"This skill verifies {subject} against its task spec. It extracts "
      "acceptance criteria, audits completeness, runs the platform code "
      "review, and reports a pass/fail verdict.\n"
      "\n"
      "Out of scope: authoring the implementation — that is the "
      "feature-implement skill.\n"
    )

  return _todo_section("## Specialist Scope", context.skill_name)


def render_inputs_section(context: ScaffoldTemplateContext) -> str:
  """Render an ``## Inputs`` seed, family-aware."""
  family = context.family
  area = context.area

  if family == "code-review":
    if area:
      return (
        "## Inputs\n"
        "\n"
        "- The slice of the diff relevant to this area.\n"
        "- Sibling supporting files: `stack-routing.md`, `review-orchestrator.md`, "
        "`review-delegation.md`, `telemetry-contract.md`.\n"
        "- Platform manifest `platform.yaml` for routing signals.\n"
      )
    return (
      "## Inputs\n"
      "\n"
      "- PR branch and base branch, so the diff can be computed.\n"
      "- Sibling supporting files: `stack-routing.md`, `review-orchestrator.md`, "
      "`review-delegation.md`, `telemetry-contract.md`.\n"
      "- Platform manifest `platform.yaml` for routing signals and declared "
      "specialists.\n"
    )

  if family == "feature-implement":
    return (
      "## Inputs\n"
      "\n"
      "- Design doc or feature spec describing what to build.\n"
      "- Target branch and working directory.\n"
      "- Any acceptance criteria or constraints called out in the spec.\n"
    )

  if family == "feature-verify":
    return (
      "## Inputs\n"
      "\n"
      "- The PR branch to verify.\n"
      "- The design doc or task spec with acceptance criteria.\n"
      "- Base branch so the diff can be computed.\n"
    )

  return _todo_section("## Inputs", context.skill_name)


def render_outputs_contract_section(context: ScaffoldTemplateContext) -> str:
  """Render an ``## Outputs Contract`` seed, family- and area-aware."""
  family = context.family
  area = context.area

  if family == "code-review":
    if area:
      phrase = _AREA_DESCRIPTION_PHRASES.get(area, f"{area.replace('-', ' ')} risks")
      return (
        "## Outputs Contract\n"
        "\n"
        f"- Findings scoped to {phrase}, each with severity and `file:line` "
        "location.\n"
        "- No findings outside scope — unrelated issues belong in other "
        "specialists' output.\n"
        "- `Execution mode: inline | delegated` reported on its own line.\n"
      )
    return (
      "## Outputs Contract\n"
      "\n"
      "- Structured review with a risk register (CRITICAL / HIGH / MEDIUM / LOW).\n"
      "- Delegated mode: per-specialist findings aggregated under area headings.\n"
      "- Inline mode: findings grouped by concern (architecture, correctness, "
      "security, performance, testing).\n"
      "- Prioritized action items.\n"
      "- `Execution mode: inline | delegated` reported on its own line.\n"
    )

  if family == "feature-implement":
    return (
      "## Outputs Contract\n"
      "\n"
      "- Implementation plan aligned with the spec.\n"
      "- Code changes that satisfy the acceptance criteria.\n"
      "- Completeness audit results.\n"
      "- Generated PR description with test plan.\n"
      "- `Execution mode: inline | delegated` reported on its own line.\n"
    )

  if family == "feature-verify":
    return (
      "## Outputs Contract\n"
      "\n"
      "- Pass/fail verdict against the task spec.\n"
      "- Gap list for any unmet acceptance criteria.\n"
      "- Review summary aggregating specialist findings.\n"
      "- `Execution mode: inline | delegated` reported on its own line.\n"
    )

  return _todo_section("## Outputs Contract", context.skill_name)


def render_delegated_mode_section(context: ScaffoldTemplateContext) -> str:
  """Render the ``## Delegated Mode`` seed for a code-review baseline skill.

  This section is not part of the six-H2 content contract — it is an
  optional runtime-mode seed that the scaffolder inserts for baseline
  code-review skills so the dual-mode behavior is documented in one place.
  """
  del context
  return (
    "## Delegated Mode\n"
    "\n"
    "Requires the owning pack's `declared_code_review_areas` list to be "
    "non-empty.\n"
    "\n"
    "Applies when the diff is large, the risk profile is high, multiple "
    "areas are meaningfully involved, or the safest choice is unclear.\n"
    "\n"
    "- Route the diff to each area's specialist using the "
    "`review-delegation.md` playbook.\n"
    "- Pass each subagent its scoped file list, applicable active learnings, "
    "and the shared specialist contract.\n"
    "- Aggregate specialist findings into the final risk register.\n"
    "- Report `Execution mode: delegated`.\n"
  )


def render_inline_mode_section(context: ScaffoldTemplateContext) -> str:
  """Render the ``## Inline Mode`` seed for a code-review baseline skill.

  Covers both sub-cases so the skill works regardless of whether the pack
  has specialists yet:
  - specialists declared + small/low-risk scope → run sequentially in-thread;
  - no specialists declared → review the diff directly here.
  """
  label = context.display_name or context.platform or "the diff"
  return (
    "## Inline Mode\n"
    "\n"
    "Applies in either of these cases:\n"
    "\n"
    "- **Specialists declared, small and low-risk scope** — run each declared "
    "specialist sequentially in the current thread, read the specialist skill "
    "file as the primary rubric, keep findings attributed before merging.\n"
    f"- **No specialists declared** — review {label} directly here. Cover "
    "architecture, correctness, security, performance, and testing concerns "
    "in one pass.\n"
    "\n"
    "Common to both:\n"
    "\n"
    "- Apply the shared specialist contract in "
    "`review-orchestrator.md`.\n"
    "- Merge and deduplicate findings into the final risk register.\n"
    "- Report `Execution mode: inline`.\n"
  )


def render_content_body(
  context: ScaffoldTemplateContext,
  *,
  description: str = "",
  content_body: str | None = None,
) -> str:
  """Render a governed ``content.md`` body or a maintainer starter."""
  if content_body is not None:
    body = content_body.rstrip() + "\n"
  else:
    body = _render_governed_content_starter(
      context,
      description=description,
    )

  title = "Content"
  if context.family == "quality-check":
    title = "Quality-Check Content"
  elif context.family == "code-review" and context.area:
    title = f"{context.area.replace('-', ' ').title()} Content"
  elif context.family == "code-review":
    title = "Review Content"
  return f"# {title}\n\n{body.rstrip()}\n"


def _render_governed_content_starter(
  context: ScaffoldTemplateContext,
  *,
  description: str,
) -> str:
  if context.family == "quality-check":
    summary = description or infer_skill_description(context)
    return (
      f"## Purpose\n\n{summary}\n\n"
      "## Execution Steps\n\n"
      "1. Determine the files in scope for the current unit of work.\n"
      "2. Run the platform's quality-check entrypoint and capture the failures.\n"
      "3. Fix only the failures that belong to the scoped work unless the contract says otherwise.\n"
      "4. Re-run the quality check until the scoped failures are resolved.\n\n"
      "## Fix Strategy\n\n"
      "- Prefer root-cause fixes over suppressions or TODO comments.\n"
      "- Keep changes aligned with the project's existing conventions and build tooling.\n"
      "- Call out any blocker that requires a maintainer decision instead of guessing.\n"
    )

  if context.family == "code-review" and context.area:
    summary = description or infer_skill_description(context)
    area_label = context.area.replace("-", " ")
    return (
      f"## Focus\n\n{summary}\n\n"
      "## Review Triggers\n\n"
      f"- Changes that primarily affect {area_label} concerns for this platform.\n"
      "- Project-specific cues, modules, or file patterns that should route into this specialist.\n\n"
      "## Review Guidance\n\n"
      "- Record the concrete risks this specialist should prioritize.\n"
      "- Note any repo-specific heuristics, invariants, or failure modes worth checking.\n"
      "- Keep output format, telemetry, and runtime ceremony in the wrapper or shared sidecars.\n"
    )

  summary = description or infer_skill_description(context)
  return (
    f"## Review Focus\n\n{summary}\n\n"
    "## Review Guidance\n\n"
    "- Document the project-specific risks, heuristics, and judgment calls this skill should apply.\n"
    "- Call out any local modules, file patterns, frameworks, or product areas that should bias review.\n"
    "- Capture repo-specific routing cues here only when they matter to review behavior after selection.\n"
    "- Keep shell ceremony, output formatting rules, and telemetry mechanics out of this file.\n"
    "- Reference governed add-ons here only when they enrich an already-routed platform skill.\n"
  )


_DEFAULT_SECTION_RENDERERS: dict[str, object] = {
  "## Description": render_description_section,
  "## Specialist Scope": render_specialist_scope_section,
  "## Inputs": render_inputs_section,
  "## Outputs Contract": render_outputs_contract_section,
  "## Execution Mode Reporting": render_execution_mode_reporting,
  "## Telemetry Ceremony Hooks": render_telemetry_ceremony_hooks,
  "## Execution": lambda _context: CANONICAL_EXECUTION_SECTION,
  "## Ceremony": render_ceremony_section,
}


def _skill_requires_telemetry_contract(skill_name: str) -> bool:
  if not skill_name.startswith("bill-"):
    return False
  if skill_name.endswith("-code-review"):
    return True
  if "-code-review-" in skill_name:
    return True
  if skill_name.endswith("-quality-check"):
    return True
  if skill_name.endswith("-feature-implement"):
    return True
  if skill_name.endswith("-feature-verify"):
    return True
  if skill_name == "bill-pr-description":
    return True
  return False


def _skill_requires_review_scope(skill_name: str) -> bool:
  if skill_name == "bill-code-review":
    return True
  return skill_name.startswith("bill-") and skill_name.endswith("-code-review")


def _skill_requires_review_orchestrator(skill_name: str) -> bool:
  if not skill_name.startswith("bill-"):
    return False
  if skill_name.endswith("-code-review"):
    return True
  if "-code-review-" in skill_name:
    return True
  return False


def _skill_requires_specialist_contract(skill_name: str) -> bool:
  if skill_name == "bill-code-review":
    return False
  return skill_name.startswith("bill-") and skill_name.endswith("-code-review")


def _skill_requires_stack_routing(skill_name: str) -> bool:
  if not skill_name.startswith("bill-"):
    return False
  if skill_name.endswith("-code-review"):
    return True
  if skill_name.endswith("-quality-check"):
    return True
  return False


def _skill_requires_review_delegation(skill_name: str) -> bool:
  return skill_name.startswith("bill-") and skill_name.endswith("-code-review")


def _skill_is_portable_review_entrypoint(skill_name: str) -> bool:
  return skill_name.startswith("bill-") and skill_name.endswith("-code-review")


def render_default_section(section_name: str, context: ScaffoldTemplateContext) -> str:
  """Render the default body for an authored section slot.

  The scaffolder only owns the two ceremony sections; authored sections
  (``## Description``, ``## Specialist Scope``, ``## Inputs``,
  ``## Outputs Contract``) are authored by humans. This function returns a
  minimal stub so new skills compile against the six-section requirement,
  with one default per slot (callers edit these afterwards).
  """
  if section_name in _DEFAULT_SECTION_RENDERERS:
    renderer = _DEFAULT_SECTION_RENDERERS[section_name]
    return renderer(context)  # type: ignore[operator]

  humanized = section_name.removeprefix("## ").strip()
  return (
    f"{section_name}\n"
    "\n"
    f"TODO: author the {humanized.lower()} section for `{context.skill_name}`.\n"
  )


def extract_scaffolder_owned(markdown_text: str) -> dict[str, str]:
  """Extract the scaffolder-owned section bodies from a rendered SKILL.md.

  Returns a mapping ``heading -> body`` for every scaffolder-owned heading
  present in ``markdown_text``. Tests use this to assert that the
  scaffolder-owned sections are byte-identical across specialists in a
  family. Authored sections are intentionally not returned.
  """
  sections: dict[str, tuple[int, int]] = {}
  match_positions: list[tuple[int, str]] = []
  for match in _H2_PATTERN.finditer(markdown_text):
    match_positions.append((match.start(), match.group(0).strip()))

  for index, (start, heading) in enumerate(match_positions):
    if heading not in _SCAFFOLDER_OWNED_HEADINGS:
      continue
    end = match_positions[index + 1][0] if index + 1 < len(match_positions) else len(markdown_text)
    sections[heading] = (start, end)

  return {
    heading: markdown_text[start:end]
    for heading, (start, end) in sections.items()
  }


def _title_case_specialist(name: str) -> str:
  return " ".join(part.capitalize() for part in name.split("-") if part)


def render_codex_agent_toml_stub(name: str, parent_skill: str) -> str:
  description = (
    f"TODO: one-line description for the {name} specialist subagent. Fill in before shipping."
  )
  body_lines = [
    f"name = \"{name}\"",
    f"description = \"{description}\"",
    "",
    "developer_instructions = \"\"\"",
    f"# {_title_case_specialist(name)} Specialist",
    "",
    "TODO: replace this placeholder with the specialist briefing.",
    "",
    (
      "Specialist contract pointer: see specialist-contract.md for the F-XXX Risk "
      f"Register format used by this orchestrator's review specialists (parent skill: {parent_skill})."
    ),
    "\"\"\"",
    "",
  ]
  return "\n".join(body_lines)


def render_opencode_agent_md_stub(name: str, parent_skill: str) -> str:
  description = (
    f"TODO: one-line description for the {name} specialist subagent. Fill in before shipping."
  )
  return (
    "---\n"
    f"name: {name}\n"
    f"description: {description}\n"
    "mode: subagent\n"
    "---\n"
    "\n"
    f"# {_title_case_specialist(name)} Specialist\n"
    "\n"
    "TODO: replace this placeholder with the specialist briefing.\n"
    "\n"
    "Specialist contract pointer: see specialist-contract.md for the F-XXX Risk "
    f"Register format used by this orchestrator's review specialists (parent skill: {parent_skill}).\n"
  )


def render_subagent_spawn_runtime_notes(orchestrator_name: str, specialists: list[str]) -> str:
  if not specialists:
    return ""
  example_specialist = specialists[0]
  backticked = ", ".join(f"`@{name}`" for name in specialists)

  paragraphs: list[str] = ["## Subagent Spawn Runtime Notes"]
  paragraphs.append(
    "Specialist spawn instructions in this orchestrator are runtime-neutral. Each phrase "
    f"such as \"spawn the `{example_specialist}` subagent\" maps to the native subagent "
    f"surface of the host runtime. On Claude, the spawn becomes an `Agent` tool call "
    f"against a matching subagent definition for `{orchestrator_name}`. On Codex, the "
    "spawn is a natural-language directive and Codex resolves it by `name` against the "
    "installed TOML files in the Codex user agents directory (with the legacy Agents "
    "agents fallback), respecting `agents.max_threads` and `agents.max_depth`. On "
    "OpenCode, the spawn resolves by filename-derived `name` against markdown agents "
    "installed in the OpenCode user agents directory; operators can also invoke the same "
    f"specialists manually with {backticked}."
  )
  if len(specialists) > 6:
    paragraphs.append(
      "Selected fan-out exceeds Codex's `agents.max_threads = 6` default; run waves of "
      "at most 6 specialists, with the orchestrator merging wave outputs before final review."
    )
  paragraphs.append(
    "OpenCode does not document a different native concurrency cap; keep the conservative "
    "limit of 6 or fewer specialists per wave."
  )

  return "\n\n".join(paragraphs)


__all__ = [
  "render_codex_agent_toml_stub",
  "render_content_body",
  "render_opencode_agent_md_stub",
  "render_skill_frontmatter",
  "render_subagent_spawn_runtime_notes",
  "ScaffoldTemplateContext",
  "CANONICAL_CEREMONY_SECTION",
  "CANONICAL_EXECUTION_SECTION",
  "DescriptorMetadata",
  "default_area_focus",
  "extract_scaffolder_owned",
  "infer_skill_description",
  "render_default_section",
  "render_descriptor_section",
  "render_delegated_mode_section",
  "render_description_section",
  "render_execution_mode_reporting",
  "render_inline_mode_section",
  "render_inputs_section",
  "render_outputs_contract_section",
  "render_specialist_scope_section",
  "render_telemetry_ceremony_hooks",
]
