"""Manifest-edit helpers for the new-skill scaffolder (SKILL-15).

Appends new entries to ``platform.yaml`` while preserving key order and any
human-authored comments as best-effort. The scaffolder snapshots the original
bytes before calling in, so rollback is byte-identical on failure. This module
only needs to perform an additive edit — pack renames, deletions, and moves
are not supported here.

The append is conservative: we operate on the text form of the YAML so that
PyYAML's round-trip rewrite doesn't reshuffle keys or strip comments. PyYAML
by itself does not preserve comments, so a pure ``yaml.safe_dump`` round-trip
would drop every ``#`` line in existing packs.
"""

from __future__ import annotations

from pathlib import Path
import re


# Matches the top-level ``declared_code_review_areas:`` list. Capture groups:
# 1. the existing list body (zero or more lines beginning with a ``-``)
_AREAS_LIST_PATTERN = re.compile(
  r"^declared_code_review_areas:\s*\n((?:[ \t]+-[^\n]*\n)*)",
  re.MULTILINE,
)

# Matches the ``declared_files:`` block and then the nested ``areas:`` list
# body. The manifest canon (see ``platform-packs/kotlin/platform.yaml``) uses
# two-space indentation so we match that literal shape.
_AREAS_FILES_PATTERN = re.compile(
  r"^(declared_files:\n(?:(?:[ \t]+[^\n]*\n)*?))(  areas:\n)((?:    [^\n]+\n)*)",
  re.MULTILINE,
)


def append_code_review_area(
  *,
  manifest_path: Path,
  area: str,
  relative_content_path: str,
) -> None:
  """Append ``area`` to ``declared_code_review_areas`` and ``declared_files.areas``.

  Args:
    manifest_path: absolute path to ``platform.yaml``.
    area: approved code-review area slug (e.g. ``"performance"``).
    relative_content_path: path to the new SKILL.md, relative to the
      platform-pack root (e.g.
      ``"code-review/bill-kotlin-code-review-performance/SKILL.md"``).

  The edit is additive and idempotent: attempting to append an area that is
  already declared is a no-op. Callers that want strict-add semantics should
  rely on :class:`SkillAlreadyExistsError` raised upstream when the skill
  directory already exists.
  """
  original_text = manifest_path.read_text(encoding="utf-8")
  updated = original_text

  updated = _append_area_to_list(updated, area)
  updated = _append_area_to_declared_files(updated, area, relative_content_path)

  if updated != original_text:
    manifest_path.write_text(updated, encoding="utf-8")


def _append_area_to_list(text: str, area: str) -> str:
  match = _AREAS_LIST_PATTERN.search(text)
  if match is None:
    raise ValueError(
      "Manifest is missing required 'declared_code_review_areas:' block; refusing to edit."
    )
  existing_body = match.group(1)
  if re.search(rf"^[ \t]+-\s*{re.escape(area)}\s*$", existing_body, re.MULTILINE):
    return text
  indent = _detect_list_indent(existing_body) or "  "
  insertion = f"{indent}- {area}\n"
  start, end = match.span()
  return text[:start] + f"declared_code_review_areas:\n{existing_body}{insertion}" + text[end:]


def _append_area_to_declared_files(text: str, area: str, relative_path: str) -> str:
  match = _AREAS_FILES_PATTERN.search(text)
  if match is None:
    raise ValueError(
      "Manifest is missing 'declared_files.areas:' block; refusing to edit."
    )
  block_prefix = match.group(1)
  areas_header = match.group(2)
  existing_body = match.group(3)
  if re.search(rf"^    {re.escape(area)}:\s", existing_body, re.MULTILINE):
    return text
  insertion = f"    {area}: {relative_path}\n"
  start, end = match.span()
  return text[:start] + block_prefix + areas_header + existing_body + insertion + text[end:]


def _detect_list_indent(list_body: str) -> str:
  """Pick up the existing list indent so we append with the same prefix.

  The kotlin/backend-kotlin/kmp packs all use two-space indentation, but
  we still detect it dynamically so a fork that uses four-space indents
  doesn't get a mixed-indent manifest after the scaffolder edits it.
  """
  for line in list_body.splitlines():
    stripped = line.lstrip(" \t")
    if stripped.startswith("- "):
      return line[: len(line) - len(stripped)]
  return ""


__all__ = [
  "append_code_review_area",
]
