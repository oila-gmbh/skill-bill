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


SHELL_CONTRACT_VERSION: str = "1.0"

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

REQUIRED_CONTENT_SECTIONS: tuple[str, ...] = (
  "## Description",
  "## Specialist Scope",
  "## Inputs",
  "## Outputs Contract",
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)

MANIFEST_FILENAME: str = "platform.yaml"

_SECTION_HEADING_PATTERN = re.compile(r"^##\s+[^\n]+$")
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
  addon_signals: tuple[str, ...]


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
  governs_addons: bool = False
  display_name: str | None = None
  notes: str | None = None

  @property
  def routed_skill_name(self) -> str:
    """Return the contract-preserving routed skill name for this pack."""

    return f"bill-{self.slug}-code-review"


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

  pack = _build_pack(slug=slug, pack_root=pack_root, manifest_path=manifest_path, raw=raw)
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
      f"'{pack.contract_version}' but the shell expects '{contract_version}'. "
      "Update the pack to the new schema or pin the shell to the pack's version."
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

  _assert_content_file_ok(pack, slot="baseline", file_path=baseline_path)

  for area in pack.declared_code_review_areas:
    area_path = declared_area_files[area]
    if not isinstance(area_path, Path):
      raise InvalidManifestSchemaError(
        f"Platform pack '{pack.slug}': declared_files.areas['{area}'] must resolve to a path."
      )
    _assert_content_file_ok(pack, slot=f"areas.{area}", file_path=area_path)


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
    addon_signals=_as_string_tuple(
      slug, routing_raw.get("addon_signals", []), "routing_signals.addon_signals"
    ),
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

  governs_addons_raw = raw.get("governs_addons", False)
  if not isinstance(governs_addons_raw, bool):
    raise InvalidManifestSchemaError(
      f"Platform pack '{slug}': 'governs_addons' must be a boolean."
    )

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

  return PlatformPack(
    slug=slug,
    pack_root=pack_root,
    contract_version=contract_version,
    routing_signals=routing_signals,
    declared_code_review_areas=tuple(declared_areas),
    declared_files=declared_files,
    governs_addons=governs_addons_raw,
    display_name=display_name_raw,
    notes=notes_raw,
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


def _assert_content_file_ok(pack: PlatformPack, *, slot: str, file_path: Path) -> None:
  if not file_path.is_file():
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': declared content file for slot '{slot}' "
      f"is missing at '{file_path}'."
    )

  text = file_path.read_text(encoding="utf-8")
  headings = _collect_top_level_h2_headings(text)
  for required in REQUIRED_CONTENT_SECTIONS:
    if required not in headings:
      raise MissingRequiredSectionError(
        f"Platform pack '{pack.slug}': content file '{file_path}' is missing "
        f"required section '{required}'."
      )


def _collect_top_level_h2_headings(text: str) -> set[str]:
  """Return the set of real H2 headings outside fenced code blocks.

  A naive regex scan would incorrectly match ``## Specialist Scope`` inside
  a fenced code block — that would let a pack author silently omit a real
  section while "documenting" it in a code example. This walker tracks
  fence state line-by-line and only collects headings while outside fences.
  """
  headings: set[str] = set()
  in_fence = False
  for line in text.splitlines():
    if _FENCE_PATTERN.match(line):
      in_fence = not in_fence
      continue
    if in_fence:
      continue
    if _SECTION_HEADING_PATTERN.match(line):
      headings.add(line.strip())
  return headings


__all__ = [
  "APPROVED_CODE_REVIEW_AREAS",
  "ContractVersionMismatchError",
  "InvalidManifestSchemaError",
  "MissingContentFileError",
  "MissingManifestError",
  "MissingRequiredSectionError",
  "PlatformPack",
  "PyYAMLMissingError",
  "REQUIRED_CONTENT_SECTIONS",
  "RoutingSignals",
  "SHELL_CONTRACT_VERSION",
  "ShellContentContractError",
  "discover_platform_packs",
  "load_platform_pack",
  "validate_platform_pack",
]
