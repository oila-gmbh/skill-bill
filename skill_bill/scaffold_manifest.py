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
import json
import re

from skill_bill.shell_content_contract import SHELL_CONTRACT_VERSION

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

# Matches a fresh manifest that still uses the inline empty-list form for
# ``declared_code_review_areas``.
_AREAS_EMPTY_INLINE_PATTERN = re.compile(
  r"^declared_code_review_areas:\s*\[\s*\]\s*$",
  re.MULTILINE,
)

# Matches a fresh manifest that still uses the inline empty-map form for
# ``declared_files.areas``. The new-platform scaffolder emits this compact
# form and the first specialist append rewrites it into the block form above.
_DECLARED_FILES_EMPTY_INLINE_PATTERN = re.compile(
  r"^(declared_files:\n(?:(?:[ \t]+[^\n]*\n)*?))(  areas:\s*\{\s*\}\s*)",
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
  inline_empty_match = _AREAS_EMPTY_INLINE_PATTERN.search(text)
  if inline_empty_match is not None:
    return (
      text[: inline_empty_match.start()]
      + "declared_code_review_areas:\n  - "
      + area
      + "\n"
      + text[inline_empty_match.end():]
    )

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
  inline_empty_match = _DECLARED_FILES_EMPTY_INLINE_PATTERN.search(text)
  if inline_empty_match is not None:
    block_prefix = inline_empty_match.group(1)
    start, end = inline_empty_match.span()
    return (
      text[:start]
      + block_prefix
      + f"  areas:\n    {area}: {relative_path}\n"
      + text[end:]
    )

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

  The kotlin and kmp packs both use two-space indentation, but
  we still detect it dynamically so a fork that uses four-space indents
  doesn't get a mixed-indent manifest after the scaffolder edits it.
  """
  for line in list_body.splitlines():
    stripped = line.lstrip(" \t")
    if stripped.startswith("- "):
      return line[: len(line) - len(stripped)]
  return ""


# Matches an existing ``declared_quality_check_file:`` top-level key. We
# preserve whatever path the user already wrote (idempotent append).
_QUALITY_CHECK_KEY_PATTERN = re.compile(
  r"^declared_quality_check_file:\s*(.+)$",
  re.MULTILINE,
)

# Matches the end of the ``declared_files:`` block — the block header plus
# any nested indented lines. We append the new top-level key immediately
# after this block (with a blank-line separator) to mirror the manifest
# canon (see ``platform-packs/kotlin/platform.yaml``).
_DECLARED_FILES_BLOCK_PATTERN = re.compile(
  r"^(declared_files:\n(?:(?:[ \t]+[^\n]*\n)*))",
  re.MULTILINE,
)


def set_declared_quality_check_file(
  *,
  manifest_path: Path,
  relative_content_path: str,
) -> None:
  """Register ``declared_quality_check_file`` on a platform.yaml manifest.

  The edit is additive and idempotent: if the key already exists, its value
  is replaced with ``relative_content_path``. Otherwise the key is appended
  as a new top-level entry immediately after the ``declared_files:`` block
  with a blank-line separator, mirroring the manifest canon.
  """
  original_text = manifest_path.read_text(encoding="utf-8")
  match = _QUALITY_CHECK_KEY_PATTERN.search(original_text)
  if match is not None:
    updated = (
      original_text[: match.start()]
      + f"declared_quality_check_file: {relative_content_path}"
      + original_text[match.end():]
    )
  else:
    block_match = _DECLARED_FILES_BLOCK_PATTERN.search(original_text)
    if block_match is None:
      raise ValueError(
        "Manifest is missing 'declared_files:' block; refusing to edit "
        "(declared_quality_check_file must be appended as a sibling)."
      )
    insertion = f"\ndeclared_quality_check_file: {relative_content_path}\n"
    end = block_match.end()
    updated = original_text[:end] + insertion + original_text[end:]

  if updated != original_text:
    manifest_path.write_text(updated, encoding="utf-8")


def render_platform_pack_manifest(
  *,
  platform: str,
  display_name: str,
  strong_signals: list[str],
  tie_breakers: list[str] | None = None,
  addon_signals: list[str] | None = None,
  declared_code_review_areas: list[str] | None = None,
  baseline_content_path: str,
  declared_area_files: dict[str, str] | None = None,
  declared_quality_check_file: str | None = None,
  governs_addons: bool = False,
  notes: str | None = None,
) -> str:
  """Render a canonical ``platform.yaml`` for a newly scaffolded pack.

  The new-platform scaffolder uses this helper to emit a valid manifest from
  a high-level payload. The output intentionally prefers compact YAML for the
  empty lists/maps that future area appends will expand in-place.
  """
  declared_code_review_areas = declared_code_review_areas or []
  declared_area_files = declared_area_files or {}
  tie_breakers = tie_breakers or []
  addon_signals = addon_signals or []

  lines: list[str] = [
    f"platform: {_yaml_scalar(platform)}",
    f"contract_version: {_yaml_scalar(SHELL_CONTRACT_VERSION)}",
    f"display_name: {_yaml_scalar(display_name)}",
    f"governs_addons: {'true' if governs_addons else 'false'}",
    "",
    "routing_signals:",
    "  strong:",
  ]
  lines.extend(f"    - {_yaml_scalar(signal)}" for signal in strong_signals)
  lines.append(
    "  tie_breakers: []" if not tie_breakers else "  tie_breakers:"
  )
  lines.extend(f"    - {_yaml_scalar(entry)}" for entry in tie_breakers)
  lines.append("  addon_signals: []" if not addon_signals else "  addon_signals:")
  lines.extend(f"    - {_yaml_scalar(entry)}" for entry in addon_signals)
  lines.extend(
    [
      "",
      "declared_code_review_areas: []" if not declared_code_review_areas else "declared_code_review_areas:",
    ]
  )
  lines.extend(f"  - {_yaml_scalar(area)}" for area in declared_code_review_areas)
  lines.extend(
    [
      "",
      "declared_files:",
      f"  baseline: {_yaml_scalar(baseline_content_path)}",
    ]
  )
  if not declared_area_files:
    lines.append("  areas: {}")
  else:
    lines.append("  areas:")
    for area in declared_code_review_areas:
      relative_path = declared_area_files.get(area)
      if relative_path is None:
        continue
      lines.append(f"    {area}: {_yaml_scalar(relative_path)}")
  if declared_quality_check_file is not None:
    lines.extend(
      [
        "",
        f"declared_quality_check_file: {_yaml_scalar(declared_quality_check_file)}",
      ]
    )
  if notes is not None:
    lines.extend(["", f"notes: {_yaml_scalar(notes)}"])

  return "\n".join(lines) + "\n"


def _yaml_scalar(value: str) -> str:
  return json.dumps(value)


__all__ = [
  "append_code_review_area",
  "render_platform_pack_manifest",
  "set_declared_quality_check_file",
]
