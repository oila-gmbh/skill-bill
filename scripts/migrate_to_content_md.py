#!/usr/bin/env python3
"""Maintainer-only bulk migration from v1.0 single-file SKILL.md to v1.1 shell + content split.

SKILL-21: walks every governed SKILL.md under ``skills/`` and
``platform-packs/``, extracts author prose into a sibling ``content.md``,
regenerates the SKILL.md from the current v1.1 template, and runs the
validator on each rewritten skill. Per-skill isolated rollback keeps one
failing skill from poisoning the rest of the tree.

Usage:

  .venv/bin/python3 scripts/migrate_to_content_md.py [--force] [--strict] [--yes] [--repo-root PATH]

Flags:

  --force       Re-run on skills that already have content.md; by default
                such skills are skipped as idempotent no-ops.
  --strict      Compare required-section bodies byte-for-byte against the
                current scaffolder default rather than normalized prose.
                Required-section edits are captured in content.md either way.
  --yes         Bypass the dirty-repo guard; required when the working tree
                has uncommitted changes other than this migration itself.
  --repo-root   Absolute path to the repo under migration (default:
                the script's parent directory).

This script is intentionally *not* the normal end-user editing workflow.
Use ``skill-bill edit <skill-name>`` for day-to-day skill changes. Use this
script only when maintainers are bulk-migrating legacy single-file governed
skills into the generated-wrapper + authored-content split.

The script emits a summary table on exit. Any per-skill failure returns a
non-zero exit code. No git operations are performed — commit manually.
"""

from __future__ import annotations

import argparse
import datetime as _datetime
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


from skill_bill.constants import (  # noqa: E402
  PRE_SHELL_FAMILIES,
  SHELL_CONTRACT_VERSION,
  TEMPLATE_VERSION,
)
from skill_bill.scaffold import (  # noqa: E402
  infer_plan_from_skill_file,
  _render_skill_body,
)
from skill_bill.scaffold_template import (  # noqa: E402
  render_content_body,
  ScaffoldTemplateContext,
)
from skill_bill.shell_content_contract import (  # noqa: E402
  CANONICAL_EXECUTION_BODY,
  CEREMONY_FREE_FORM_H2S,
  CEREMONY_SECTIONS,
  CONTENT_BODY_FILENAME,
  REQUIRED_CONTENT_SECTIONS,
  REQUIRED_QUALITY_CHECK_SECTIONS,
  assert_content_md_sibling,
  assert_execution_body_matches,
  parse_skill_frontmatter,
)


MIGRATION_CEREMONY_DROP_HEADINGS: tuple[str, ...] = (
  "## Setup",
  "## Additional Resources",
  "## Local Review Learnings",
  "## Output Format",
  "## Output Rules",
  "## Review Output",
  "## Delegated Mode",
  "## Inline Mode",
  "## Routing Rules",
  "## Shared Stack Detection",
  "## Execution Contract",
  "## Overview",
  "## Project Overrides",
)


@dataclass
class SkillMigration:
  skill_file: Path
  skill_name: str
  family: str
  status: str = "pending"
  detail: str = ""
  free_form_h2s: int = 0
  edited_required_sections: int = 0


@dataclass
class MigrationReport:
  migrations: list[SkillMigration] = field(default_factory=list)
  backup_dir: Path | None = None

  def failures(self) -> list[SkillMigration]:
    return [entry for entry in self.migrations if entry.status == "failed"]


REQUIRED_SECTIONS_BY_FAMILY: dict[str, tuple[str, ...]] = {
  "code-review": REQUIRED_CONTENT_SECTIONS,
  "quality-check": REQUIRED_QUALITY_CHECK_SECTIONS,
}


def _family_from_skill_path(repo_root: Path, skill_file: Path) -> str | None:
  """Return the shelled family for a skill file, or None when unshelled.

  The migration script only touches skills under a shelled family because
  the shell+content contract only applies to them. Pre-shell families and
  horizontal router shells stay single-file.
  """
  try:
    relative = skill_file.resolve().relative_to(repo_root.resolve())
  except ValueError:
    return None
  parts = relative.parts
  if parts[0] == "platform-packs" and len(parts) >= 4:
    family = parts[2]
    if family in {"code-review", "quality-check"}:
      return family
  return None


def _collect_governed_skills(repo_root: Path) -> list[Path]:
  """Return every governed SKILL.md under shelled families."""
  discovered: list[Path] = []
  platform_packs = repo_root / "platform-packs"
  if platform_packs.is_dir():
    for skill_file in sorted(platform_packs.rglob("SKILL.md")):
      if _family_from_skill_path(repo_root, skill_file) is not None:
        discovered.append(skill_file)
  return discovered


def _parse_h2_sections(text: str) -> list[tuple[str, str]]:
  """Return (heading, body) pairs in document order.

  The body includes the heading line and everything up to the next H2 or
  end-of-file. Fenced code blocks are respected so ``## foo`` inside
  triple-backtick fences does not start a new section.
  """
  import re

  section_pattern = re.compile(r"^##\s+[^\n]+$")
  fence_pattern = re.compile(r"^\s*(?:```|~~~)")

  sections: list[tuple[str, str]] = []
  current_heading: str | None = None
  current_lines: list[str] = []
  in_fence = False

  for line in text.splitlines(keepends=True):
    stripped = line.rstrip("\n")
    if fence_pattern.match(stripped):
      in_fence = not in_fence
      if current_heading is not None:
        current_lines.append(line)
      continue
    if not in_fence and section_pattern.match(stripped):
      if current_heading is not None:
        sections.append((current_heading, "".join(current_lines)))
      current_heading = stripped.strip()
      current_lines = [line]
      continue
    if current_heading is not None:
      current_lines.append(line)
  if current_heading is not None:
    sections.append((current_heading, "".join(current_lines)))
  return sections


def _normalize_body(body: str) -> str:
  """Collapse whitespace for normalized-compare default detection."""
  import re

  collapsed = re.sub(r"\s+", " ", body).strip()
  return collapsed


def _extract_author_content(
  skill_file: Path,
  text: str,
  family: str,
  *,
  strict: bool,
) -> tuple[str, int, int]:
  """Split a v1.0 SKILL.md into (content_body, free_form_count, edited_count).

  - Free-form H2s (anything not in the family's required set and not in
    :data:`CEREMONY_SECTIONS` or :data:`CEREMONY_FREE_FORM_H2S`) flow into
    content.md so authored prose survives.
  - Required H2s whose body diverges from the current scaffolder default
    are also appended so author edits are preserved. Pre-SKILL-21 v1.0
    packs used a different default body; the ceremony-leakage fix
    tightens this check to compare against the current rendered default,
    so unedited v1.0 defaults do not leak into ``content.md``.
  - Ceremony sections (:data:`CEREMONY_SECTIONS`) are shell-owned and
    never copied to ``content.md``. The shell regeneration step emits
    them afresh.
  - Free-form ceremony headings (:data:`CEREMONY_FREE_FORM_H2S`) are
    shell-owned by taxonomy even though the loader does not demand
    them. The migration script drops these headings + their bodies so
    author-owned content.md carries only author knowledge (signals,
    rubrics, routing tables, project-specific rules) and never the
    shell's output contract / orchestration / telemetry ceremony.
  """
  from skill_bill.scaffold_template import render_default_section

  required_for_family = REQUIRED_SECTIONS_BY_FAMILY[family]
  ceremony_headings = set(CEREMONY_SECTIONS)
  ceremony_free_form_headings = set(CEREMONY_FREE_FORM_H2S) | set(MIGRATION_CEREMONY_DROP_HEADINGS)

  frontmatter = parse_skill_frontmatter(skill_file)
  plan = infer_plan_from_skill_file(skill_file, frontmatter)
  context = ScaffoldTemplateContext(
    skill_name=plan["skill_name"],
    family=plan["family"] or family,
    platform=plan["platform"],
    area=plan["area"],
    display_name=plan["display_name"],
  )

  sections = _parse_h2_sections(text)
  kept: list[str] = []
  free_form = 0
  edited = 0
  for heading, body in sections:
    if heading in ceremony_headings:
      continue
    if heading in ceremony_free_form_headings:
      continue
    if heading not in required_for_family:
      kept.append(body.rstrip() + "\n")
      free_form += 1
      continue
    default_body = render_default_section(heading, context)
    if _matches_template_default(body, default_body, strict=strict):
      continue
    kept.append(body.rstrip() + "\n")
    edited += 1

  if not kept:
    description = frontmatter.get("description", "")
    placeholder = render_content_body(context, description=description, content_body=None)
    return placeholder, free_form, edited

  content_text = "\n".join(kept).rstrip() + "\n"
  return content_text, free_form, edited


def _matches_template_default(body: str, default_body: str, *, strict: bool) -> bool:
  """Return True when ``body`` matches the current template default.

  Used by :func:`_extract_author_content` to decide whether a required H2
  body counts as an author edit. In strict mode we require a byte match
  after stripping; in non-strict mode we collapse whitespace so prose
  reflows (e.g. line-wrap differences introduced by formatters) do not
  spuriously classify an unedited default as an author edit.
  """
  if strict:
    return body.strip() == default_body.strip()
  return _normalize_body(body) == _normalize_body(default_body)


def _create_backup(backup_dir: Path, skill_file: Path, repo_root: Path) -> None:
  relative = skill_file.relative_to(repo_root)
  target = backup_dir / relative
  target.parent.mkdir(parents=True, exist_ok=True)
  shutil.copy2(skill_file, target)


def _migrate_one(
  skill_file: Path,
  repo_root: Path,
  *,
  force: bool,
  strict: bool,
  backup_dir: Path,
) -> SkillMigration:
  family = _family_from_skill_path(repo_root, skill_file)
  if family is None:
    return SkillMigration(
      skill_file=skill_file,
      skill_name=skill_file.parent.name,
      family="unknown",
      status="skipped",
      detail="Not a shelled governed skill; skipped.",
    )

  content_path = skill_file.parent / CONTENT_BODY_FILENAME
  if content_path.is_file() and not force:
    return SkillMigration(
      skill_file=skill_file,
      skill_name=skill_file.parent.name,
      family=family,
      status="skipped",
      detail="content.md already exists; pass --force to overwrite.",
    )

  original_skill_bytes = skill_file.read_bytes()
  original_content_bytes = content_path.read_bytes() if content_path.is_file() else None
  _create_backup(backup_dir, skill_file, repo_root)
  if original_content_bytes is not None:
    _create_backup(backup_dir, content_path, repo_root)

  text = skill_file.read_text(encoding="utf-8")
  frontmatter = parse_skill_frontmatter(skill_file)
  try:
    content_text, free_form, edited = _extract_author_content(
      skill_file, text, family, strict=strict
    )
  except Exception as error:
    return SkillMigration(
      skill_file=skill_file,
      skill_name=skill_file.parent.name,
      family=family,
      status="failed",
      detail=f"content extraction failed: {error}",
    )

  plan = infer_plan_from_skill_file(skill_file, frontmatter)
  plan["family"] = family
  description = frontmatter.get("description", "")
  new_skill_body = _render_skill_body(plan, {"description": description})

  try:
    content_path.write_text(content_text, encoding="utf-8")
    skill_file.write_text(new_skill_body, encoding="utf-8")
    assert_execution_body_matches(
      skill_file,
      context_label=f"migration of '{skill_file.parent.name}'",
    )
    assert_content_md_sibling(
      skill_file,
      context_label=f"migration of '{skill_file.parent.name}'",
    )
  except Exception as error:
    skill_file.write_bytes(original_skill_bytes)
    if original_content_bytes is None:
      if content_path.is_file():
        content_path.unlink()
    else:
      content_path.write_bytes(original_content_bytes)
    return SkillMigration(
      skill_file=skill_file,
      skill_name=skill_file.parent.name,
      family=family,
      status="failed",
      detail=f"post-write validation failed: {error}",
    )

  return SkillMigration(
    skill_file=skill_file,
    skill_name=skill_file.parent.name,
    family=family,
    status="migrated",
    detail=f"{free_form} free-form H2s, {edited} edited required sections captured",
    free_form_h2s=free_form,
    edited_required_sections=edited,
  )


def _is_working_tree_dirty(repo_root: Path) -> bool:
  try:
    result = subprocess.run(
      ["git", "status", "--porcelain"],
      cwd=repo_root,
      capture_output=True,
      text=True,
      check=True,
    )
  except (FileNotFoundError, subprocess.CalledProcessError):
    return False
  return bool(result.stdout.strip())


def _format_summary(report: MigrationReport) -> str:
  lines = ["Migration summary:"]
  for entry in report.migrations:
    symbol = {
      "migrated": "ok",
      "skipped": "skip",
      "failed": "fail",
    }.get(entry.status, entry.status)
    rel = entry.skill_file
    lines.append(
      f"  [{symbol}] {entry.skill_name} ({entry.family}) — {entry.detail or rel}"
    )
  if report.backup_dir is not None:
    lines.append(f"Backups at: {report.backup_dir}")
  return "\n".join(lines)


def _run_validator(repo_root: Path) -> int:
  validator = repo_root / "scripts" / "validate_agent_configs.py"
  if not validator.is_file():
    return 0
  result = subprocess.run(
    [sys.executable, str(validator)],
    cwd=repo_root,
    capture_output=True,
    text=True,
  )
  if result.returncode != 0:
    print("Post-migration validator failed:", file=sys.stderr)
    print(result.stdout, file=sys.stderr)
    print(result.stderr, file=sys.stderr)
  return result.returncode


def migrate(
  repo_root: Path,
  *,
  force: bool,
  strict: bool,
  yes: bool,
) -> MigrationReport:
  if _is_working_tree_dirty(repo_root) and not yes:
    raise SystemExit(
      "Working tree is dirty; commit or stash changes before running the "
      "migration, or pass --yes to bypass this guard."
    )

  skills = _collect_governed_skills(repo_root)
  report = MigrationReport()
  if not skills:
    return report

  timestamp = _datetime.datetime.now().strftime("%Y-%m-%dT%H-%M-%S")
  backup_dir = repo_root / "_migration_backup" / timestamp
  backup_dir.mkdir(parents=True, exist_ok=True)
  report.backup_dir = backup_dir

  for skill_file in skills:
    result = _migrate_one(
      skill_file,
      repo_root,
      force=force,
      strict=strict,
      backup_dir=backup_dir,
    )
    report.migrations.append(result)

  return report


def main(argv: Iterable[str] | None = None) -> int:
  parser = argparse.ArgumentParser(
    description=(
      "Maintainer-only bulk migration for governed SKILL.md files to the v1.1 "
      "shell+content split."
    ),
  )
  parser.add_argument(
    "--force",
    action="store_true",
    help="Re-run on skills that already have content.md (bulk-maintainer mode only).",
  )
  parser.add_argument(
    "--strict",
    action="store_true",
    help="Byte-match required sections against the current scaffolder default.",
  )
  parser.add_argument(
    "--yes",
    action="store_true",
    help="Bypass the dirty-repo guard for maintainer-run bulk migration.",
  )
  parser.add_argument(
    "--repo-root",
    default=str(ROOT),
    help="Absolute path to the repo under migration.",
  )
  args = parser.parse_args(list(argv) if argv is not None else None)

  repo_root = Path(args.repo_root).resolve()
  report = migrate(
    repo_root,
    force=args.force,
    strict=args.strict,
    yes=args.yes,
  )
  print(
    "Maintainer workflow: bulk migration only. "
    "For normal skill edits, use `skill-bill edit <skill-name>`."
  )
  print(_format_summary(report))
  print(
    f"Shell contract: {SHELL_CONTRACT_VERSION}; "
    f"template: {TEMPLATE_VERSION}; "
    f"canonical Execution body length: {len(CANONICAL_EXECUTION_BODY)}."
  )
  if report.failures():
    return 1
  return _run_validator(repo_root)


if __name__ == "__main__":
  raise SystemExit(main())
