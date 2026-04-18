"""Scaffolder-owned section templates (SKILL-15).

These templates render the two scaffolder-owned H2 sections that MUST be
byte-identical across every specialist in a family:

- ``## Execution Mode Reporting``
- ``## Telemetry Ceremony Hooks``

Every other section is authored by humans and may vary per skill. Keeping the
scaffolder-owned sections in one place (and emitted from stored templates)
guarantees that a family's specialists share the exact same ceremony contract.

One default per slot: every required H2 section has exactly one default body
here. Callers extend the skill by editing the authored sections, not the
scaffolder-owned ones; regenerating from the same payload yields byte-identical
output.
"""

from __future__ import annotations

from dataclasses import dataclass
import re


# Compiled once at import-time because :func:`extract_scaffolder_owned`
# is invoked from validator paths that may loop over hundreds of skill files.
_SCAFFOLDER_OWNED_HEADINGS: tuple[str, ...] = (
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)

_H2_PATTERN = re.compile(r"^##\s+[^\n]+$", re.MULTILINE)


@dataclass(frozen=True)
class ScaffoldTemplateContext:
  """Inputs to the stored templates.

  Attributes:
    skill_name: canonical ``bill-...`` slug for the skill.
    family: capability family (``code-review``, ``quality-check``, ...).
    platform: platform slug; empty string for horizontal skills.
    area: code-review area slug; empty string for non-area kinds.
  """

  skill_name: str
  family: str
  platform: str = ""
  area: str = ""


def render_project_overrides(context: ScaffoldTemplateContext) -> str:
  """Render the ``## Project Overrides`` section body.

  Skills that land under ``skills/`` (horizontal and pre-shell platform
  overrides) are validated by :func:`validate_skill_file` in
  ``scripts/validate_agent_configs.py``, which requires the literal
  ``## Project Overrides`` heading and a reference to
  ``.agents/skill-overrides.md``. The block here mirrors the wording used
  by existing skills (see e.g. ``skills/base/bill-skill-scaffold``)
  and encodes the precedence: a matching ``## <skill-name>`` section in
  ``.agents/skill-overrides.md`` beats ``AGENTS.md``, which beats the
  built-in defaults below.

  Platform-pack skills (shelled overrides, code-review-area specialists)
  are validated by the lighter :func:`validate_platform_pack_skill_file`
  and intentionally do NOT receive this section, to keep platform-pack
  skills lean.
  """
  skill_name = context.skill_name or "this skill"
  return (
    "## Project Overrides\n"
    "\n"
    f"If `.agents/skill-overrides.md` exists in the project root and contains a "
    f"`## {skill_name}` section, read that section and apply it as the highest-priority "
    "instruction for this skill. The matching section may refine or replace parts of the "
    "default workflow below.\n"
    "\n"
    "If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.\n"
    "\n"
    f"Precedence for this skill: matching `.agents/skill-overrides.md` section > "
    "`AGENTS.md` > built-in defaults.\n"
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


_DEFAULT_SECTION_RENDERERS: dict[str, object] = {
  "## Execution Mode Reporting": render_execution_mode_reporting,
  "## Telemetry Ceremony Hooks": render_telemetry_ceremony_hooks,
}


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


__all__ = [
  "ScaffoldTemplateContext",
  "extract_scaffolder_owned",
  "render_default_section",
  "render_execution_mode_reporting",
  "render_project_overrides",
  "render_telemetry_ceremony_hooks",
]
