"""Shell+content contract loader for governed code-review platform packs.

This module is the runtime authority for loading and validating user-owned
platform packs against the versioned contract documented in
``orchestration/shell-content-contract/PLAYBOOK.md``.

The loader is intentionally strict: missing or malformed content raises a
specific named exception rather than silently falling back. The shell relies
on this behavior to refuse to run on broken packs and to print an actionable
error.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import re

from skill_bill.constants import SHELL_CONTRACT_VERSION
from skill_bill.scaffold_template import (
  CANONICAL_EXECUTION_SECTION,
  DescriptorMetadata,
  ScaffoldTemplateContext,
  default_area_focus,
  render_ceremony_section,
  render_descriptor_section,
)


def _import_yaml():
  """Import PyYAML lazily so importing this module does not require it.

  The shell+content loader requires PyYAML at runtime, but it lives in a
  package that is frequently imported from tooling entry points with
  narrower deps. Loading YAML on demand keeps that boundary clean without
  silently ignoring a broken install.

  Raises:
    PyYAMLMissingError: when PyYAML cannot be imported. Callers get an
      actionable message instead of a raw ``ModuleNotFoundError`` traceback.
  """
  try:
    import yaml  # type: ignore[import-untyped]
  except ImportError as error:
    raise PyYAMLMissingError(
      "PyYAML is required to load platform packs. Install it via the project "
      "venv (`./.venv/bin/pip install pyyaml>=6`) or run the validator through "
      "`.venv/bin/python3 scripts/validate_agent_configs.py`."
    ) from error
  return yaml


APPROVED_CODE_REVIEW_AREAS: frozenset[str] = frozenset(
  {
    "api-contracts",
    "architecture",
    "performance",
    "persistence",
    "platform-correctness",
    "reliability",
    "security",
    "testing",
    "ui",
    "ux-accessibility",
  }
)

REQUIRED_GOVERNED_SECTIONS: tuple[str, ...] = (
  "## Descriptor",
  "## Execution",
  "## Ceremony",
)
CEREMONY_SECTIONS: tuple[str, ...] = REQUIRED_GOVERNED_SECTIONS
REQUIRED_CONTENT_SECTIONS: tuple[str, ...] = (
  "## Description",
  "## Specialist Scope",
  "## Inputs",
  "## Outputs Contract",
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)
REQUIRED_QUALITY_CHECK_SECTIONS: tuple[str, ...] = REQUIRED_CONTENT_SECTIONS
CONTENT_BODY_FILENAME: str = "content.md"
CANONICAL_EXECUTION_BODY: str = CANONICAL_EXECUTION_SECTION
CEREMONY_FREE_FORM_H2S: tuple[str, ...] = (
  "## Execution",
  "## Ceremony",
)

CANONICAL_SKILL_MD_FRONTMATTER_ALLOWED_KEYS: frozenset[str] = frozenset({"name", "description"})

CANONICAL_SKILL_MD_BANLIST_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
  ("fenced code block", re.compile(r"^\s*(?:```|~~~)")),
  ("markdown table", re.compile(r"^\s*\|.*\|\s*$")),
  ("'## Step N:' heading", re.compile(r"^##\s+Step\s+\d+[a-z]?\b", re.IGNORECASE)),
  ("MCP install gate", re.compile(r"npm install -g|readian-mcp", re.IGNORECASE)),
  ("telemetry instructions", re.compile(r"\b(?:_started|_finished)\b\s*MCP|telemetry_proxy_capabilities|skillbill_[a-z_]+_(?:started|finished)")),
  ("routing rule", re.compile(r"^\s*(?:Route|Routing rule|Routing rules):", re.IGNORECASE)),
  ("run-context placeholder line", re.compile(r"^\s*`(?:Review session ID|Review run ID|Applied learnings):")),
  ("H1 heading", re.compile(r"^#\s+\S")),
  ("H3+ heading", re.compile(r"^#{3,}\s+\S")),
)

MANIFEST_FILENAME: str = "platform.yaml"

_SECTION_HEADING_PATTERN = re.compile(r"^##\s+[^\n]+$", re.MULTILINE)
# Fenced code-block markers recognized when scanning for H2 sections.
# Covers both triple-backtick and triple-tilde fences (with or without a
# language tag). A fence line must have the marker as the first non-space
# characters on the line.
_FENCE_PATTERN = re.compile(r"^\s*(?:```|~~~)")


class ShellContentContractError(Exception):
  """Base class for all contract failures.

  Callers (the shell, the validator, tests) catch this base type to surface
  any contract failure uniformly, but each concrete subclass names the
  specific failure mode so operators know exactly which artifact to fix.
  """


class MissingManifestError(ShellContentContractError):
  """Raised when a platform pack directory has no ``platform.yaml``."""


class InvalidManifestSchemaError(ShellContentContractError):
  """Raised when ``platform.yaml`` fails schema validation."""


class ContractVersionMismatchError(ShellContentContractError):
  """Raised when a pack's ``contract_version`` does not match the shell."""


class MissingContentFileError(ShellContentContractError):
  """Raised when a declared content file path does not exist on disk."""


class MissingRequiredSectionError(ShellContentContractError):
  """Raised when a declared content file is missing a required H2 section."""


class InvalidDescriptorSectionError(ShellContentContractError):
  """Raised when a governed skill's ``## Descriptor`` section drifts."""


class InvalidExecutionSectionError(ShellContentContractError):
  """Raised when a governed skill's ``## Execution`` section drifts."""


class InvalidCeremonySectionError(ShellContentContractError):
  """Raised when a governed skill's ``## Ceremony`` section drifts."""


class MissingShellCeremonyFileError(ShellContentContractError):
  """Raised when a governed skill is missing its ``shell-ceremony.md`` sidecar."""


class InvalidSkillMdShapeError(ShellContentContractError):
  """Raised when a SKILL.md file violates the canonical shape contract.

  The canonical shape requires a frontmatter block with only the allowed
  keys, the three governed H2 sections (``## Descriptor``, ``## Execution``,
  ``## Ceremony``) in that order, and bans fenced code, tables, ``## Step``
  headings, embedded templates, install gates, telemetry instructions,
  routing rules, run-context placeholder lines, H1, H3+, and intro
  paragraphs.
  """


class PyYAMLMissingError(ShellContentContractError):
  """Raised when PyYAML is not installed in the active Python environment.

  The loader requires PyYAML to parse ``platform.yaml``. Instead of letting
  the raw ``ModuleNotFoundError`` bubble up, the loader catches it and
  raises this subclass with an actionable install message, so the CLI and
  the validator print a friendly error instead of a traceback.
  """


@dataclass(frozen=True)
class RoutingSignals:
  """Normalized routing signals for a platform pack."""

  strong: tuple[str, ...]
  tie_breakers: tuple[str, ...]


@dataclass(frozen=True)
class PlatformPack:
  """A loaded and validated platform pack.

  Attributes mirror the manifest schema but normalize collections into tuples
  so the loaded pack is hashable and immutable.
  """

  slug: str
  pack_root: Path
  contract_version: str
  routing_signals: RoutingSignals
  declared_code_review_areas: tuple[str, ...]
  declared_files: dict[str, Path] = field(hash=False)
  area_metadata: dict[str, str] = field(hash=False)
  display_name: str | None = None
  notes: str | None = None
  declared_quality_check_file: Path | None = None

  @property
  def routed_skill_name(self) -> str:
    """Return the contract-preserving routed skill name for this pack."""

    return f"bill-{self.slug}-code-review"


def load_platform_manifest(pack_root: Path | str) -> PlatformPack:
  """Load a single platform manifest without validating governed skill files.

  This helper exists for migration and upgrade flows that need manifest
  metadata from older wrapper shapes before rewriting those wrappers to the
  current contract. It still enforces manifest presence, YAML validity, and
  manifest schema rules, but it deliberately skips governed skill validation.
  """

  pack_root = Path(pack_root).resolve()
  slug = pack_root.name
  manifest_path = pack_root / MANIFEST_FILENAME

  if not manifest_path.is_file():
    raise MissingManifestError(
      f"Platform pack '{slug}': expected manifest at '{manifest_path}' but it is missing."
    )

  yaml = _import_yaml()
  try:
    raw = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
  except yaml.YAMLError as error:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest '{manifest_path}' is not valid YAML: {error}"
    ) from error

  return _build_pack(
    slug=slug,
    pack_root=pack_root,
    manifest_path=manifest_path,
    raw=raw,
  )


def parse_skill_frontmatter(skill_file: Path | str) -> dict[str, str]:
  """Parse simple YAML-like skill frontmatter into a string mapping."""
  skill_path = Path(skill_file)
  text = skill_path.read_text(encoding="utf-8")
  if not text.startswith("---\n"):
    return {}
  try:
    _, frontmatter_text, _ = text.split("\n---\n", 2)
  except ValueError:
    return {}
  parsed: dict[str, str] = {}
  for line in frontmatter_text.splitlines():
    if ":" not in line:
      continue
    key, value = line.split(":", 1)
    parsed[key.strip()] = value.strip()
  return parsed


def load_platform_pack(pack_root: Path | str) -> PlatformPack:
  """Load and validate a single platform pack.

  Args:
    pack_root: path to ``platform-packs/<slug>/``.

  Raises:
    MissingManifestError: when ``platform.yaml`` is absent.
    InvalidManifestSchemaError: when the manifest is malformed.
    ContractVersionMismatchError: when ``contract_version`` is wrong.
    MissingContentFileError: when a declared file does not exist.
    MissingRequiredSectionError: when a content file lacks a required
      H2 section.

  Returns:
    A validated :class:`PlatformPack`.
  """
  pack = load_platform_manifest(pack_root)
  validate_platform_pack(pack, contract_version=SHELL_CONTRACT_VERSION)
  return pack


def validate_platform_pack(pack: PlatformPack, contract_version: str) -> None:
  """Enforce loud-fail rules on a previously built pack.

  The function raises the first error it detects; callers that want to
  enumerate every failure across a tree should load packs individually and
  accumulate exceptions.
  """

  if pack.contract_version != contract_version:
    raise ContractVersionMismatchError(
      f"Platform pack '{pack.slug}': declares contract_version "
      f"'{pack.contract_version}' but the shell expects '{contract_version}'."
    )

  expected_areas = set(pack.declared_code_review_areas)
  declared_area_files = pack.declared_files.get("areas", {})
  if not isinstance(declared_area_files, dict):
    raise InvalidManifestSchemaError(
      f"Platform pack '{pack.slug}': declared_files.areas must be a mapping."
    )
  missing_area_slots = expected_areas - set(declared_area_files.keys())
  if missing_area_slots:
    raise InvalidManifestSchemaError(
      f"Platform pack '{pack.slug}': declared_files.areas is missing entries for "
      f"{sorted(missing_area_slots)}."
    )

  baseline_path = pack.declared_files.get("baseline")
  if not isinstance(baseline_path, Path):
    raise InvalidManifestSchemaError(
      f"Platform pack '{pack.slug}': declared_files.baseline is required."
    )

  _assert_governed_skill_ok(
    pack,
    slot="baseline",
    skill_path=baseline_path,
    family="code-review",
    area="",
  )

  for area in pack.declared_code_review_areas:
    area_path = declared_area_files[area]
    if not isinstance(area_path, Path):
      raise InvalidManifestSchemaError(
        f"Platform pack '{pack.slug}': declared_files.areas['{area}'] must resolve to a path."
      )
    _assert_governed_skill_ok(
      pack,
      slot=f"areas.{area}",
      skill_path=area_path,
      family="code-review",
      area=area,
    )


def discover_platform_packs(platform_packs_root: Path | str) -> list[PlatformPack]:
  """Discover and load every platform pack under the given root.

  The first loader error aborts discovery with the specific exception so
  callers can act on a single precise message.
  """

  packs_root = Path(platform_packs_root).resolve()
  if not packs_root.is_dir():
    return []

  discovered: list[PlatformPack] = []
  for entry in sorted(packs_root.iterdir()):
    if not entry.is_dir():
      continue
    if entry.name.startswith("."):
      continue
    discovered.append(load_platform_pack(entry))
  return discovered


def discover_platform_pack_manifests(platform_packs_root: Path | str) -> list[PlatformPack]:
  """Discover platform manifests without validating governed skill wrappers."""

  packs_root = Path(platform_packs_root).resolve()
  if not packs_root.is_dir():
    return []

  discovered: list[PlatformPack] = []
  for entry in sorted(packs_root.iterdir()):
    if not entry.is_dir():
      continue
    if entry.name.startswith("."):
      continue
    discovered.append(load_platform_manifest(entry))
  return discovered


def _build_pack(
  *,
  slug: str,
  pack_root: Path,
  manifest_path: Path,
  raw: Any,
) -> PlatformPack:
  if not isinstance(raw, dict):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest '{manifest_path}' must be a YAML mapping at the top level."
    )

  declared_platform = raw.get("platform")
  if declared_platform != slug:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest 'platform' field is "
      f"'{declared_platform}', expected '{slug}' to match the directory name."
    )

  contract_version_raw = raw.get("contract_version")
  if contract_version_raw is None:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest is missing required field 'contract_version'."
    )
  contract_version = str(contract_version_raw)

  routing_raw = raw.get("routing_signals")
  if not isinstance(routing_raw, dict):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest field 'routing_signals' must be a mapping."
    )

  routing_signals = RoutingSignals(
    strong=_as_string_tuple(slug, routing_raw.get("strong"), "routing_signals.strong"),
    tie_breakers=_as_string_tuple(slug, routing_raw.get("tie_breakers"), "routing_signals.tie_breakers"),
  )

  declared_areas_raw = raw.get("declared_code_review_areas")
  if declared_areas_raw is None:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest is missing required field 'declared_code_review_areas'."
    )
  if not isinstance(declared_areas_raw, list):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'declared_code_review_areas' must be a list."
    )
  declared_areas: list[str] = []
  for entry in declared_areas_raw:
    if not isinstance(entry, str):
      raise InvalidManifestSchemaError(
        f"Platform pack '{slug}': every entry in 'declared_code_review_areas' must be a string."
      )
    if entry not in APPROVED_CODE_REVIEW_AREAS:
      raise InvalidManifestSchemaError(
        f"Platform pack '{slug}': declared area '{entry}' is not approved; "
        f"must be one of {sorted(APPROVED_CODE_REVIEW_AREAS)}."
      )
    declared_areas.append(entry)

  declared_files_raw = raw.get("declared_files")
  if not isinstance(declared_files_raw, dict):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest field 'declared_files' must be a mapping."
    )
  baseline_raw = declared_files_raw.get("baseline")
  if not isinstance(baseline_raw, str) or not baseline_raw:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'declared_files.baseline' must be a non-empty path string."
    )
  areas_raw = declared_files_raw.get("areas", {})
  if not isinstance(areas_raw, dict):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'declared_files.areas' must be a mapping."
    )
  for area_key, area_path_value in areas_raw.items():
    if not isinstance(area_key, str) or not isinstance(area_path_value, str):
      raise InvalidManifestSchemaError(
        f"Platform pack '{slug}': 'declared_files.areas' entries must be string->string."
      )

  # Loud-fail when a manifest declares an area in ``declared_files.areas`` that
  # is not listed in ``declared_code_review_areas``. A typo or stale entry
  # used to be silently dropped, which weakens the loud-fail contract.
  extra_area_keys = sorted(set(areas_raw.keys()) - set(declared_areas))
  if extra_area_keys:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'declared_files.areas' contains entries "
      f"{extra_area_keys} that are not listed in 'declared_code_review_areas'. "
      "Remove the extras or add them to the declared area list."
    )

  declared_files: dict[str, Any] = {
    "baseline": (pack_root / baseline_raw).resolve(),
    "areas": {area: (pack_root / areas_raw[area]).resolve() for area in declared_areas if area in areas_raw},
  }

  area_metadata_raw = raw.get("area_metadata")
  if area_metadata_raw is None:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest is missing required field 'area_metadata'."
    )
  if not isinstance(area_metadata_raw, dict):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': manifest field 'area_metadata' must be a mapping."
    )
  extra_area_metadata = sorted(set(area_metadata_raw.keys()) - set(declared_areas))
  if extra_area_metadata:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': area_metadata contains entries {extra_area_metadata} "
      "that are not listed in 'declared_code_review_areas'."
    )
  area_metadata: dict[str, str] = {
    area: default_area_focus(area)
    for area in declared_areas
  }
  for area, metadata in area_metadata_raw.items():
    if not isinstance(metadata, dict):
      raise InvalidManifestSchemaError(
        f"Platform pack '{slug}': area_metadata['{area}'] must be a mapping."
      )
    focus = metadata.get("focus")
    if not isinstance(focus, str) or not focus:
      raise InvalidManifestSchemaError(
        f"Platform pack '{slug}': area_metadata['{area}'].focus must be a non-empty string."
      )
    area_metadata[area] = focus

  display_name_raw = raw.get("display_name")
  if display_name_raw is not None and not isinstance(display_name_raw, str):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'display_name' must be a string when provided."
    )

  notes_raw = raw.get("notes")
  if notes_raw is not None and not isinstance(notes_raw, str):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'notes' must be a string when provided."
    )

  declared_quality_check_raw = raw.get("declared_quality_check_file")
  declared_quality_check_path: Path | None
  if declared_quality_check_raw is None:
    declared_quality_check_path = None
  elif isinstance(declared_quality_check_raw, str) and declared_quality_check_raw:
    declared_quality_check_path = (pack_root / declared_quality_check_raw).resolve()
  else:
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'declared_quality_check_file' must be a non-empty path string when provided."
    )

  return PlatformPack(
    slug=slug,
    pack_root=pack_root,
    contract_version=contract_version,
    routing_signals=routing_signals,
    declared_code_review_areas=tuple(declared_areas),
    declared_files=declared_files,
    area_metadata=area_metadata,
    display_name=display_name_raw,
    notes=notes_raw,
    declared_quality_check_file=declared_quality_check_path,
  )


def _as_string_tuple(slug: str, value: Any, field_label: str) -> tuple[str, ...]:
  if value is None:
    return ()
  if not isinstance(value, list):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': '{field_label}' must be a list of strings."
    )
  result: list[str] = []
  for entry in value:
    if not isinstance(entry, str):
      raise InvalidManifestSchemaError(
        f"Platform pack '{slug}': every entry in '{field_label}' must be a string."
      )
    result.append(entry)
  return tuple(result)


def _assert_governed_skill_ok(
  pack: PlatformPack,
  *,
  slot: str,
  skill_path: Path,
  family: str,
  area: str,
) -> None:
  if not skill_path.is_file():
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': declared content file for slot '{slot}' "
      f"is missing at '{skill_path}'."
    )

  text = skill_path.read_text(encoding="utf-8")
  sections = _collect_top_level_h2_sections(text)
  headings = set(sections)
  for required in REQUIRED_GOVERNED_SECTIONS:
    if required not in headings:
      raise MissingRequiredSectionError(
        f"Platform pack '{pack.slug}': skill file '{skill_path}' is missing "
        f"required section '{required}'."
      )

  content_path = skill_path.with_name("content.md")
  if not content_path.is_file():
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': sibling content file for slot '{slot}' "
      f"is missing at '{content_path}'."
    )

  ceremony_path = skill_path.with_name("shell-ceremony.md")
  if not ceremony_path.is_file():
    raise MissingShellCeremonyFileError(
      f"Platform pack '{pack.slug}': sibling shell ceremony file for slot '{slot}' "
      f"is missing at '{ceremony_path}'."
    )

  expected_descriptor = _render_expected_descriptor(
    pack,
    skill_name=skill_path.parent.name,
    family=family,
    area=area,
  )
  expected_context = _governed_context(
    pack,
    skill_name=skill_path.parent.name,
    family=family,
    area=area,
  )
  if sections["## Execution"] != CANONICAL_EXECUTION_BODY:
    raise InvalidExecutionSectionError(
      f"Platform pack '{pack.slug}': skill file '{skill_path}' has a drifted "
      "## Execution section."
    )
  expected_ceremony = render_ceremony_section(expected_context)
  if sections["## Ceremony"] != expected_ceremony:
    raise InvalidCeremonySectionError(
      f"Platform pack '{pack.slug}': skill file '{skill_path}' has a drifted "
      "## Ceremony section."
    )
  if sections["## Descriptor"] != expected_descriptor:
    raise InvalidDescriptorSectionError(
      f"Platform pack '{pack.slug}': skill file '{skill_path}' has a drifted "
      "## Descriptor section."
    )


def load_quality_check_content(pack: PlatformPack) -> Path:
  """Return the resolved path to a pack's sibling quality-check `content.md`.

  Loud-fail rules:

  - Raises :class:`MissingContentFileError` when ``declared_quality_check_file``
    is ``None`` (callers must gate the call) or the referenced file does not
    exist on disk.
  - Raises :class:`MissingRequiredSectionError` when the content file is
    missing one of the :data:`REQUIRED_QUALITY_CHECK_SECTIONS` H2 sections.

  The function validates the declared quality-check `SKILL.md`, then returns
  the sibling `content.md` path that contains the authored execution content.
  It never silently falls back to another pack. The ``bill-quality-check``
  shell implements the explicit ``kmp`` → ``kotlin`` routing fallback by
  selecting a different pack before calling this loader.
  """
  if pack.declared_quality_check_file is None:
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': declared_quality_check_file not set "
      "(call is only valid after checking pack.declared_quality_check_file is not None)."
    )

  file_path = pack.declared_quality_check_file
  _assert_governed_skill_ok(
    pack,
    slot="quality-check",
    skill_path=file_path,
    family="quality-check",
    area="",
  )
  return file_path.with_name(CONTENT_BODY_FILENAME)


def assert_execution_body_matches(
  skill_file: Path | str,
  *,
  context_label: str,
) -> None:
  """Ensure a governed wrapper keeps the canonical Execution section."""
  skill_path = Path(skill_file)
  sections = _collect_top_level_h2_sections(skill_path.read_text(encoding="utf-8"))
  execution = sections.get("## Execution")
  if execution != CANONICAL_EXECUTION_BODY:
    raise InvalidExecutionSectionError(
      f"{context_label}: skill file '{skill_path}' has a drifted ## Execution section."
    )


def assert_content_md_sibling(
  skill_file: Path | str,
  *,
  context_label: str,
) -> None:
  """Ensure a governed wrapper has a sibling ``content.md`` file."""
  skill_path = Path(skill_file)
  content_path = skill_path.with_name(CONTENT_BODY_FILENAME)
  if not content_path.is_file():
    raise MissingContentFileError(
      f"{context_label}: expected sibling content file at '{content_path}'."
    )


def _collect_top_level_h2_sections(text: str) -> dict[str, str]:
  """Return the real H2 sections outside fenced code blocks.

  A naive regex scan would incorrectly match ``## Specialist Scope`` inside
  a fenced code block — that would let a pack author silently omit a real
  section while "documenting" it in a code example. This walker tracks
  fence state line-by-line and only collects headings while outside fences.
  """
  visible_lines: list[str] = []
  in_fence = False
  for line in text.splitlines():
    if _FENCE_PATTERN.match(line):
      in_fence = not in_fence
      continue
    if not in_fence:
      visible_lines.append(line)
  visible_text = "\n".join(visible_lines)
  matches = list(_SECTION_HEADING_PATTERN.finditer(visible_text))
  sections: dict[str, str] = {}
  for index, match in enumerate(matches):
    heading = match.group(0).strip()
    end = matches[index + 1].start() if index + 1 < len(matches) else len(visible_text)
    section_text = visible_text[match.start():end].strip() + "\n"
    sections[heading] = section_text
  return sections


def _render_expected_descriptor(
  pack: PlatformPack,
  *,
  skill_name: str,
  family: str,
  area: str,
) -> str:
  context = _governed_context(
    pack,
    skill_name=skill_name,
    family=family,
    area=area,
  )
  metadata = DescriptorMetadata(area_focus=pack.area_metadata.get(area, ""))
  return render_descriptor_section(context, metadata=metadata)


def _governed_context(
  pack: PlatformPack,
  *,
  skill_name: str,
  family: str,
  area: str,
) -> ScaffoldTemplateContext:
  return ScaffoldTemplateContext(
    skill_name=skill_name,
    family=family,
    platform=pack.slug,
    area=area,
    display_name=pack.display_name or pack.slug.replace("-", " ").title(),
  )


__all__ = [
  "APPROVED_CODE_REVIEW_AREAS",
  "CANONICAL_EXECUTION_BODY",
  "CANONICAL_SKILL_MD_BANLIST_PATTERNS",
  "CANONICAL_SKILL_MD_FRONTMATTER_ALLOWED_KEYS",
  "CEREMONY_SECTIONS",
  "ContractVersionMismatchError",
  "CEREMONY_FREE_FORM_H2S",
  "CONTENT_BODY_FILENAME",
  "InvalidCeremonySectionError",
  "InvalidDescriptorSectionError",
  "InvalidExecutionSectionError",
  "InvalidManifestSchemaError",
  "InvalidSkillMdShapeError",
  "MissingContentFileError",
  "MissingManifestError",
  "MissingRequiredSectionError",
  "MissingShellCeremonyFileError",
  "PlatformPack",
  "PyYAMLMissingError",
  "REQUIRED_CONTENT_SECTIONS",
  "REQUIRED_GOVERNED_SECTIONS",
  "REQUIRED_QUALITY_CHECK_SECTIONS",
  "RoutingSignals",
  "SHELL_CONTRACT_VERSION",
  "ShellContentContractError",
  "discover_platform_packs",
  "discover_platform_pack_manifests",
  "assert_content_md_sibling",
  "assert_execution_body_matches",
  "load_platform_manifest",
  "load_platform_pack",
  "load_quality_check_content",
  "parse_skill_frontmatter",
  "validate_platform_pack",
]
