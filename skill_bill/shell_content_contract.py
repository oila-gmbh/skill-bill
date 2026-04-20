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

from skill_bill.constants import (
  SHELL_CONTRACT_VERSION,
  TEMPLATE_VERSION,
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


# SHELL_CONTRACT_VERSION + TEMPLATE_VERSION are sourced from
# ``skill_bill.constants``. SKILL-21 relocated the authoritative definition
# there so the CLI, the migration script, and the template renderer share
# one source of truth. The re-export below keeps the historic import path
# working without introducing a silent fallback.
__CONTRACT_VERSION_EXPORT = SHELL_CONTRACT_VERSION
__TEMPLATE_VERSION_EXPORT = TEMPLATE_VERSION

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
  "## Execution",
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)

# Required H2 sections for per-platform quality-check content files. The
# bill-quality-check shell is horizontal and does not require the three
# code-review-specific sections (Specialist Scope, Inputs, Outputs Contract).
REQUIRED_QUALITY_CHECK_SECTIONS: tuple[str, ...] = (
  "## Description",
  "## Execution Steps",
  "## Fix Strategy",
  "## Execution",
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)

# Canonical ``## Execution`` body. SKILL-21 added this as a required H2 whose
# prose must be byte-identical across every governed skill. The scaffolder
# and the migration script both render this string verbatim; the loader
# asserts byte-match via :func:`assert_execution_body_matches`.
CANONICAL_EXECUTION_BODY: str = (
  "## Execution\n"
  "\n"
  "Follow the instructions in [content.md](content.md).\n"
)

# Ceremony H2 sections that belong to the governance shell, not the
# author-owned content body. The migration script and the scaffolder must
# never copy these sections into ``content.md`` — they live in SKILL.md
# exclusively. The tuple is deliberately explicit so reviewers can audit
# which headings are ceremony in one place.
#
# - ``## Project Overrides`` encodes the overrides-precedence rule.
# - ``## Execution`` links the shell to the sibling content.md.
# - ``## Execution Mode Reporting`` + ``## Telemetry Ceremony Hooks`` are
#   the scaffolder-owned ceremony sections emitted byte-identically across
#   a family.
CEREMONY_SECTIONS: tuple[str, ...] = (
  "## Project Overrides",
  "## Execution",
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)

# Free-form ceremony H2 sections that authors sometimes carry in v1.0
# SKILL.md files but that belong to the shell, not content.md. These
# headings are not part of the required-section set, so the loader does
# not demand them, but the migration script and the scaffolder must drop
# them on the floor rather than copy them into content.md. The hygiene
# test in :mod:`tests.test_content_md_hygiene` also walks every shipped
# content.md and fails if any of these headings reappears.
#
# Taxonomy: the shell is skill-bill's responsibility (output contracts,
# session/run IDs, severity/confidence scales, risk-register format,
# telemetry sidecar pointers, learnings resolution, execution-mode
# descriptions, scope-determination bullet lists, sidecar pointers).
# content.md is the author's responsibility (signals, rubrics, routing
# tables, project-specific rules). Anything on this list is shell
# ceremony that leaked into a content body and must be scrubbed.
CEREMONY_FREE_FORM_H2S: tuple[str, ...] = (
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
)

# Filename of the author-owned content body that must sit next to every
# governed SKILL.md under v1.1. The loader raises
# :class:`MissingContentBodyFileError` when the sibling is missing.
CONTENT_BODY_FILENAME: str = "content.md"

MANIFEST_FILENAME: str = "platform.yaml"

_SECTION_HEADING_PATTERN = re.compile(r"^##\s+[^\n]+$")
# Fenced code-block markers recognized when scanning for H2 sections.
# Covers both triple-backtick and triple-tilde fences (with or without a
# language tag). A fence line must have the marker as the first non-space
# characters on the line.
_FENCE_PATTERN = re.compile(r"^\s*(?:```|~~~)")
# SKILL.md YAML frontmatter delimiter. Used by the v1.1 loader to read
# ``shell_contract_version`` and ``template_version`` without pulling in a
# full YAML dependency for callers that only need the two fields.
_FRONTMATTER_PATTERN = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)


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
  """Raised when a pack's ``contract_version`` does not match the shell.

  The message always points at ``scripts/migrate_to_content_md.py`` so the
  operator has an actionable next step for v1.0 → v1.1 migration. The loader
  never silently falls back to a pre-v1.1 shape.
  """


class MissingContentFileError(ShellContentContractError):
  """Raised when a declared content file path does not exist on disk."""


class MissingContentBodyFileError(ShellContentContractError):
  """Raised when a governed skill directory is missing ``content.md``.

  SKILL-21 split the governance shell from the author-owned skill body into
  two sibling files. ``SKILL.md`` carries the contract; ``content.md``
  carries the prompt the agent executes. The loader raises this error when
  the sibling is absent; callers must run ``scripts/migrate_to_content_md.py``
  or scaffold the skill through the v1.1 scaffolder.
  """


class MissingRequiredSectionError(ShellContentContractError):
  """Raised when a declared content file is missing a required H2 section."""


class InvalidExecutionSectionError(ShellContentContractError):
  """Raised when the ``## Execution`` H2 body is malformed.

  The v1.1 contract requires every SKILL.md to contain a byte-identical
  ``## Execution`` section whose body links to the sibling ``content.md``.
  A missing link, edited body, or extra prose raises this error so silent
  drift cannot hide broken sibling references.
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
  declared_quality_check_file: Path | None = None

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

  _assert_contract_version_first(slug=slug, raw=raw)

  pack = _build_pack(slug=slug, pack_root=pack_root, manifest_path=manifest_path, raw=raw)
  validate_platform_pack(pack, contract_version=SHELL_CONTRACT_VERSION)
  return pack


def _assert_contract_version_first(*, slug: str, raw: Any) -> None:
  """Enforce failure precedence: contract-version mismatch wins over schema.

  The loader reads ``contract_version`` from the raw YAML dict before any
  other schema validation so v1.0 packs always surface the migration-script
  hint via :class:`ContractVersionMismatchError`, even when the manifest has
  unrelated schema errors. Silent fallback is not allowed.
  """
  if not isinstance(raw, dict):
    return
  contract_version_raw = raw.get("contract_version")
  if contract_version_raw is None:
    return
  contract_version = str(contract_version_raw)
  if contract_version != SHELL_CONTRACT_VERSION:
    raise ContractVersionMismatchError(
      f"Platform pack '{slug}': declares contract_version "
      f"'{contract_version}' but the shell expects '{SHELL_CONTRACT_VERSION}'. "
      "Run `.venv/bin/python3 scripts/migrate_to_content_md.py` to migrate "
      "v1.0 packs to the v1.1 shell+content split, or pin the shell to the "
      "pack's version."
    )


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
      "Run `.venv/bin/python3 scripts/migrate_to_content_md.py` to migrate "
      "v1.0 packs to the v1.1 shell+content split, or pin the shell to the "
      "pack's version."
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
    governs_addons=governs_addons_raw,
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


def _assert_content_file_ok(pack: PlatformPack, *, slot: str, file_path: Path) -> None:
  # Failure precedence (SKILL-21): contract-version → manifest-schema →
  # content-file → content-section → execution-link → content-sibling.
  # The order is load-bearing: the validator script and the runtime loader
  # report the same first error for any given pack.
  if not file_path.is_file():
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': declared content file for slot '{slot}' "
      f"is missing at '{file_path}'."
    )

  if file_path.name == "SKILL.md":
    assert_skill_frontmatter_versions(
      file_path,
      contract_version=pack.contract_version,
      context_label=f"Platform pack '{pack.slug}' slot '{slot}'",
    )

  text = file_path.read_text(encoding="utf-8")
  headings = _collect_top_level_h2_headings(text)
  for required in REQUIRED_CONTENT_SECTIONS:
    if required == "## Execution":
      continue
    if required not in headings:
      raise MissingRequiredSectionError(
        f"Platform pack '{pack.slug}': content file '{file_path}' is missing "
        f"required section '{required}'."
      )

  # v1.1: the Execution body and content.md sibling are only part of the
  # SKILL.md-based layout. Legacy fixtures and some packs still use bare
  # ``<area>.md`` content files; those files carry their own H2 set and
  # remain single-file skills. The content.md split applies to SKILL.md
  # surfaces only.
  if file_path.name == "SKILL.md":
    assert_execution_body_matches(
      file_path,
      context_label=f"Platform pack '{pack.slug}' slot '{slot}'",
    )
    assert_content_md_sibling(
      file_path,
      context_label=f"Platform pack '{pack.slug}' slot '{slot}'",
    )


def load_quality_check_content(pack: PlatformPack) -> Path:
  """Return the resolved path to a pack's declared quality-check content file.

  Loud-fail rules:

  - Raises :class:`MissingContentFileError` when ``declared_quality_check_file``
    is ``None`` (callers must gate the call) or the referenced file does not
    exist on disk.
  - Raises :class:`MissingRequiredSectionError` when the content file is
    missing one of the :data:`REQUIRED_QUALITY_CHECK_SECTIONS` H2 sections.

  The function never silently falls back to another pack. The
  ``bill-quality-check`` shell implements the explicit ``kmp`` → ``kotlin``
  routing fallback by selecting a
  different pack before calling this loader.
  """
  if pack.declared_quality_check_file is None:
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': declared_quality_check_file not set "
      "(call is only valid after checking pack.declared_quality_check_file is not None)."
    )

  file_path = pack.declared_quality_check_file
  if not file_path.is_file():
    raise MissingContentFileError(
      f"Platform pack '{pack.slug}': declared quality-check content file "
      f"is missing at '{file_path}'."
    )

  if file_path.name == "SKILL.md":
    assert_skill_frontmatter_versions(
      file_path,
      contract_version=pack.contract_version,
      context_label=f"Platform pack '{pack.slug}' quality-check",
    )

  text = file_path.read_text(encoding="utf-8")
  headings = _collect_top_level_h2_headings(text)
  for required in REQUIRED_QUALITY_CHECK_SECTIONS:
    if required == "## Execution":
      continue
    if required not in headings:
      raise MissingRequiredSectionError(
        f"Platform pack '{pack.slug}': quality-check content file '{file_path}' "
        f"is missing required section '{required}'."
      )

  if file_path.name == "SKILL.md":
    assert_execution_body_matches(
      file_path,
      context_label=f"Platform pack '{pack.slug}' quality-check",
    )
    assert_content_md_sibling(
      file_path,
      context_label=f"Platform pack '{pack.slug}' quality-check",
    )
  return file_path


def extract_h2_section_body(text: str, heading: str) -> str | None:
  """Return the body of ``heading`` (e.g. ``## Execution``) including the heading line.

  Returns ``None`` when the heading is not present outside fenced code blocks.
  The body runs from the heading line up to (but not including) the next H2
  or end-of-file. This is used by :func:`assert_execution_body_matches` to
  enforce byte-identical Execution bodies across every governed skill.
  """
  lines = text.splitlines(keepends=True)
  in_fence = False
  start_index: int | None = None
  for index, line in enumerate(lines):
    stripped = line.rstrip("\n")
    if _FENCE_PATTERN.match(stripped):
      in_fence = not in_fence
      continue
    if in_fence:
      continue
    if _SECTION_HEADING_PATTERN.match(stripped):
      if stripped.strip() == heading and start_index is None:
        start_index = index
        continue
      if start_index is not None:
        return "".join(lines[start_index:index]).rstrip("\n") + "\n"
  if start_index is None:
    return None
  return "".join(lines[start_index:]).rstrip("\n") + "\n"


def assert_execution_body_matches(skill_file: Path, *, context_label: str) -> None:
  """Loud-fail when ``skill_file`` does not carry the canonical Execution body.

  The v1.1 contract requires every governed SKILL.md to contain
  :data:`CANONICAL_EXECUTION_BODY` verbatim. Missing heading raises
  :class:`MissingRequiredSectionError`; present-but-edited body raises
  :class:`InvalidExecutionSectionError`. The validator and the loader both
  call this helper so the failure mode is identical across entry points.
  """
  text = skill_file.read_text(encoding="utf-8")
  headings = _collect_top_level_h2_headings(text)
  if "## Execution" not in headings:
    raise MissingRequiredSectionError(
      f"{context_label}: SKILL.md '{skill_file}' is missing required section "
      "'## Execution'."
    )
  body = extract_h2_section_body(text, "## Execution")
  if body is None or body != CANONICAL_EXECUTION_BODY:
    raise InvalidExecutionSectionError(
      f"{context_label}: SKILL.md '{skill_file}' has a '## Execution' section "
      "whose body is not the canonical byte-identical form. Run "
      "`skill-bill upgrade` to regenerate, or restore the canonical body: "
      f"{CANONICAL_EXECUTION_BODY!r}."
    )


def assert_content_md_sibling(skill_file: Path, *, context_label: str) -> Path:
  """Loud-fail when ``skill_file``'s directory is missing ``content.md``.

  Returns the resolved content.md path on success. Callers that need the
  path to forward to other validators (e.g. template-version drift) should
  capture the return value; callers that only need the loud-fail effect can
  discard it.
  """
  content_path = skill_file.parent / CONTENT_BODY_FILENAME
  if not content_path.is_file():
    raise MissingContentBodyFileError(
      f"{context_label}: sibling '{CONTENT_BODY_FILENAME}' is missing next to "
      f"SKILL.md at '{skill_file}'. Run "
      "`.venv/bin/python3 scripts/migrate_to_content_md.py` to create the "
      "sibling, or scaffold the skill through the v1.1 scaffolder."
    )
  return content_path


def parse_skill_frontmatter(skill_file: Path) -> dict[str, str]:
  """Return the SKILL.md YAML frontmatter as a flat string mapping.

  We intentionally avoid a full YAML parse here — the frontmatter in
  generated shells is always flat ``key: value`` pairs, and the loader
  needs to stay importable without PyYAML for callers that only touch
  frontmatter. The returned mapping is empty when no frontmatter block is
  present.
  """
  text = skill_file.read_text(encoding="utf-8")
  match = _FRONTMATTER_PATTERN.match(text)
  if not match:
    return {}
  values: dict[str, str] = {}
  for line in match.group(1).splitlines():
    if ":" not in line:
      continue
    key, value = line.split(":", 1)
    values[key.strip()] = value.strip()
  return values


def assert_skill_frontmatter_versions(
  skill_file: Path,
  *,
  contract_version: str,
  context_label: str,
) -> None:
  """Loud-fail when SKILL.md's ``shell_contract_version`` does not match.

  v1.1 skills carry ``shell_contract_version`` + ``template_version`` in
  their frontmatter. Missing ``shell_contract_version`` is treated as a v1.0
  artifact and raises :class:`ContractVersionMismatchError` with the
  migration-script hint. Template-version drift is NOT raised here —
  callers must use :func:`detect_template_drift` for the upgrade-actionable
  path.
  """
  frontmatter = parse_skill_frontmatter(skill_file)
  declared_contract_version = frontmatter.get("shell_contract_version", "")
  if declared_contract_version != contract_version:
    raise ContractVersionMismatchError(
      f"{context_label}: SKILL.md '{skill_file}' declares "
      f"shell_contract_version '{declared_contract_version or '(missing)'}' "
      f"but the shell expects '{contract_version}'. "
      "Run `.venv/bin/python3 scripts/migrate_to_content_md.py` to migrate "
      "v1.0 skills to the v1.1 shell+content split."
    )


def detect_template_drift(skill_file: Path, *, current_template_version: str) -> bool:
  """Return True when ``skill_file``'s ``template_version`` is not current.

  Template drift is NOT a runtime failure. It is an upgrade-actionable
  state: ``skill-bill doctor`` reports it as a warning, ``skill-bill upgrade``
  regenerates the affected SKILL.md. A skill with no
  ``template_version`` frontmatter key is treated as drifting so older
  shells surface the upgrade prompt.
  """
  frontmatter = parse_skill_frontmatter(skill_file)
  declared = frontmatter.get("template_version", "")
  return declared != current_template_version


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
  "CANONICAL_EXECUTION_BODY",
  "CEREMONY_FREE_FORM_H2S",
  "CEREMONY_SECTIONS",
  "CONTENT_BODY_FILENAME",
  "ContractVersionMismatchError",
  "InvalidExecutionSectionError",
  "InvalidManifestSchemaError",
  "MissingContentBodyFileError",
  "MissingContentFileError",
  "MissingManifestError",
  "MissingRequiredSectionError",
  "PlatformPack",
  "PyYAMLMissingError",
  "REQUIRED_CONTENT_SECTIONS",
  "REQUIRED_QUALITY_CHECK_SECTIONS",
  "RoutingSignals",
  "SHELL_CONTRACT_VERSION",
  "ShellContentContractError",
  "TEMPLATE_VERSION",
  "assert_content_md_sibling",
  "assert_execution_body_matches",
  "assert_skill_frontmatter_versions",
  "detect_template_drift",
  "discover_platform_packs",
  "extract_h2_section_body",
  "load_platform_pack",
  "load_quality_check_content",
  "parse_skill_frontmatter",
  "validate_platform_pack",
]
