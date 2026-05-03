from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable

from skill_bill.scaffold_template import (
  CANONICAL_EXECUTION_SECTION,
  DescriptorMetadata,
  ScaffoldTemplateContext,
  render_ceremony_section,
  render_descriptor_section,
)
from skill_bill.shell_content_contract import discover_platform_pack_manifests


FRONTMATTER_PATTERN = re.compile(r"\A---\n.*?\n---\n*", re.DOTALL)
H2_PATTERN = re.compile(r"^##\s+[^\n]+$", re.MULTILINE)
FENCE_PATTERN = re.compile(r"^\s*(?:```|~~~)")


@dataclass(frozen=True)
class UpgradeResult:
  repo_root: Path
  regenerated_files: tuple[Path, ...]


def render_upgrade_targets(
  repo_root: Path | str,
  *,
  skill_names: Iterable[str] | None = None,
) -> dict[Path, str]:
  """Return the canonical wrapper render for each selected skill file."""
  repo_root = Path(repo_root).resolve()
  return _render_upgrade_targets(
    repo_root,
    selected_skill_names=set(skill_names or []),
  )


def upgrade_skill_wrappers(
  repo_root: Path | str,
  *,
  skill_names: Iterable[str] | None = None,
  validate: bool = True,
) -> UpgradeResult:
  """Regenerate scaffold-managed ``SKILL.md`` wrappers in one transaction."""
  repo_root = Path(repo_root).resolve()
  rewritten: dict[Path, bytes] = {}
  regenerated: list[Path] = []
  selected_skill_names = set(skill_names or [])

  try:
    for skill_file, rendered in _render_upgrade_targets(
      repo_root,
      selected_skill_names=selected_skill_names,
    ).items():
      rendered_bytes = rendered.encode("utf-8")
      current_bytes = skill_file.read_bytes()
      if current_bytes == rendered_bytes:
        continue
      rewritten[skill_file] = current_bytes
      skill_file.write_text(rendered, encoding="utf-8")
      regenerated.append(skill_file)

    if validate:
      _run_validator(repo_root)
  except Exception:
    for skill_file, original_bytes in rewritten.items():
      skill_file.write_bytes(original_bytes)
    raise

  return UpgradeResult(
    repo_root=repo_root,
    regenerated_files=tuple(regenerated),
  )


def _render_upgrade_targets(
  repo_root: Path,
  *,
  selected_skill_names: set[str] | None = None,
) -> dict[Path, str]:
  targets: dict[Path, str] = {}
  targets.update(
    _render_horizontal_targets(
      repo_root,
      selected_skill_names=selected_skill_names,
    )
  )
  targets.update(
    _render_governed_targets(
      repo_root,
      selected_skill_names=selected_skill_names,
    )
  )
  return targets


def _render_horizontal_targets(
  repo_root: Path,
  *,
  selected_skill_names: set[str] | None = None,
) -> dict[Path, str]:
  skills_root = repo_root / "skills"
  if not skills_root.is_dir():
    return {}

  rendered: dict[Path, str] = {}
  for skill_file in sorted(skills_root.rglob("SKILL.md")):
    skill_name = skill_file.parent.name
    if selected_skill_names and skill_name not in selected_skill_names:
      continue
    context = ScaffoldTemplateContext(skill_name=skill_name, family=_infer_family(skill_name))
    text = skill_file.read_text(encoding="utf-8")
    frontmatter = _frontmatter_block(text)
    descriptor = render_descriptor_section(context, metadata=DescriptorMetadata())
    ceremony = render_ceremony_section(context)
    rendered[skill_file] = (
      f"{frontmatter.rstrip()}\n\n"
      f"{descriptor.rstrip()}\n\n"
      f"{CANONICAL_EXECUTION_SECTION.rstrip()}\n\n"
      f"{ceremony.rstrip()}\n"
    )
  return rendered


def _render_governed_targets(
  repo_root: Path,
  *,
  selected_skill_names: set[str] | None = None,
) -> dict[Path, str]:
  rendered: dict[Path, str] = {}
  for pack in discover_platform_pack_manifests(repo_root / "platform-packs"):
    baseline_path = pack.declared_files["baseline"]
    if isinstance(baseline_path, Path):
      if not selected_skill_names or baseline_path.parent.name in selected_skill_names:
        rendered[baseline_path] = _render_governed_wrapper(
          skill_file=baseline_path,
          platform=pack.slug,
          display_name=pack.display_name or pack.slug.replace("-", " ").title(),
          family="code-review",
        )

    area_paths = pack.declared_files.get("areas", {})
    if isinstance(area_paths, dict):
      for area, skill_file in area_paths.items():
        if selected_skill_names and skill_file.parent.name not in selected_skill_names:
          continue
        rendered[skill_file] = _render_governed_wrapper(
          skill_file=skill_file,
          platform=pack.slug,
          display_name=pack.display_name or pack.slug.replace("-", " ").title(),
          family="code-review",
          area=area,
          area_focus=pack.area_metadata.get(area, ""),
        )

    if pack.declared_quality_check_file is not None:
      if (
        not selected_skill_names
        or pack.declared_quality_check_file.parent.name in selected_skill_names
      ):
        rendered[pack.declared_quality_check_file] = _render_governed_wrapper(
          skill_file=pack.declared_quality_check_file,
          platform=pack.slug,
          display_name=pack.display_name or pack.slug.replace("-", " ").title(),
          family="quality-check",
        )
  return rendered


def _render_governed_wrapper(
  *,
  skill_file: Path,
  platform: str,
  display_name: str,
  family: str,
  area: str = "",
  area_focus: str = "",
) -> str:
  original_text = skill_file.read_text(encoding="utf-8")
  frontmatter = _frontmatter_block(original_text)
  context = ScaffoldTemplateContext(
    skill_name=skill_file.parent.name,
    family=family,
    platform=platform,
    area=area,
    display_name=display_name,
  )
  descriptor = render_descriptor_section(
    context,
    metadata=DescriptorMetadata(area_focus=area_focus),
  )
  return (
    f"{frontmatter.rstrip()}\n\n"
    f"{descriptor.rstrip()}\n\n"
    f"{CANONICAL_EXECUTION_SECTION.rstrip()}\n\n"
    f"{render_ceremony_section(context).rstrip()}\n"
  )


def _frontmatter_block(text: str) -> str:
  match = FRONTMATTER_PATTERN.match(text)
  if match is None:
    raise ValueError("SKILL.md is missing YAML frontmatter.")
  return match.group(0)


def _replace_top_level_section(
  text: str,
  *,
  heading: str,
  replacement: str,
) -> str:
  spans = _top_level_h2_spans(text)
  target = spans.get(heading)
  if target is None:
    return text
  start, end = target
  return f"{text[:start]}{replacement.rstrip()}\n\n{text[end:].lstrip()}"


def _top_level_h2_spans(text: str) -> dict[str, tuple[int, int]]:
  matches: list[tuple[int, int, str]] = []
  in_fence = False
  offset = 0
  for line in text.splitlines(keepends=True):
    if FENCE_PATTERN.match(line):
      in_fence = not in_fence
    elif not in_fence:
      match = H2_PATTERN.match(line)
      if match:
        matches.append((offset, offset + len(line), match.group(0).strip()))
    offset += len(line)

  spans: dict[str, tuple[int, int]] = {}
  for index, (start, _end, heading) in enumerate(matches):
    next_start = matches[index + 1][0] if index + 1 < len(matches) else len(text)
    spans[heading] = (start, next_start)
  return spans


HORIZONTAL_SKILL_FAMILIES: dict[str, str] = {
  "bill-feature-implement": "workflow",
  "bill-feature-verify": "workflow",
  "bill-grill-plan": "advisor",
  "bill-boundary-decisions": "advisor",
  "bill-boundary-history": "advisor",
  "bill-pr-description": "advisor",
  "bill-create-skill": "advisor",
  "bill-skill-remove": "advisor",
  "bill-feature-guard": "advisor",
  "bill-feature-guard-cleanup": "advisor",
  "bill-unit-test-value-check": "advisor",
  "bill-code-review": "advisor",
  "bill-quality-check": "advisor",
}


def _infer_family(skill_name: str) -> str:
  if skill_name in HORIZONTAL_SKILL_FAMILIES:
    return HORIZONTAL_SKILL_FAMILIES[skill_name]
  if "-code-review-" in skill_name or skill_name.endswith("-code-review"):
    return "code-review"
  if skill_name.endswith("-quality-check"):
    return "quality-check"
  if skill_name.endswith("-feature-implement"):
    return "feature-implement"
  if skill_name.endswith("-feature-verify"):
    return "feature-verify"
  return skill_name.removeprefix("bill-")


def _run_validator(repo_root: Path) -> None:
  script_path = repo_root / "scripts" / "validate_agent_configs"
  if not script_path.is_file():
    return

  result = subprocess.run(
    [str(script_path)],
    cwd=str(repo_root),
    capture_output=True,
    text=True,
  )
  if result.returncode != 0:
    raise RuntimeError(
      f"Validator failed after upgrade (exit {result.returncode}):\n"
      f"{result.stderr or result.stdout}"
    )
