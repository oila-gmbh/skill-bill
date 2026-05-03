"""New-skill scaffolder (SKILL-15).

Pure-Python scaffolder invoked by:

- the ``skill-bill new-skill`` CLI subcommand
- the ``new_skill_scaffold`` MCP tool
- the ``bill-create-skill`` skill (via subprocess)

The entry point is :func:`scaffold`. It takes a validated payload and returns
a :class:`ScaffoldResult` describing every filesystem mutation. The scaffolder
is atomic: every failure mode triggers a full rollback and raises a named
exception — callers never see a partially materialized skill.

Layout kinds supported today:

- ``horizontal`` — ``skills/<name>/SKILL.md``
- ``platform-override-piloted`` — ``platform-packs/<slug>/<family>/<name>/SKILL.md``
  plus a manifest edit in ``platform-packs/<slug>/platform.yaml``
- ``platform-pack`` — ``platform-packs/<slug>/`` pack root with a generated
  baseline code-review skill and quality-check skill for the platform
- ``code-review-area`` — same placement as piloted, but also registers the new
  area under ``declared_code_review_areas`` and ``declared_files.areas`` in the
  manifest
- ``add-on`` — ``platform-packs/<platform>/addons/<name>.md`` (flat; no sub-directory)

Pre-shell families (``feature-implement``, ``feature-verify``) are placed
under ``skills/<platform>/bill-<platform>-<capability>/`` and annotated with
an interim-location note.
"""

from __future__ import annotations

import importlib.util
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from skill_bill.constants import (
  PRE_SHELL_FAMILIES,
  SCAFFOLD_PAYLOAD_VERSION,
)
from skill_bill.scaffold_exceptions import (
  InvalidScaffoldPayloadError,
  MissingPlatformPackError,
  MissingSupportingFileTargetError,
  ScaffoldPayloadVersionMismatchError,
  ScaffoldRollbackError,
  ScaffoldValidatorError,
  SkillAlreadyExistsError,
  UnknownPreShellFamilyError,
  UnknownSkillKindError,
)
from skill_bill.scaffold_template import (
  DescriptorMetadata,
  ScaffoldTemplateContext,
  default_area_focus,
  infer_skill_description,
  render_codex_agent_toml_stub,
  render_content_body,
  render_default_section,
  render_descriptor_section,
  render_opencode_agent_md_stub,
  render_skill_frontmatter,
  render_subagent_spawn_runtime_notes,
)
from skill_bill.shell_content_contract import (
  APPROVED_CODE_REVIEW_AREAS,
  ShellContentContractError,
  load_platform_manifest,
  load_platform_pack,
  load_quality_check_content,
)


_REPO_ROOT = Path(__file__).resolve().parent.parent
if str(_REPO_ROOT) not in sys.path:
  sys.path.insert(0, str(_REPO_ROOT))


SKILL_KIND_HORIZONTAL = "horizontal"
SKILL_KIND_PLATFORM_OVERRIDE_PILOTED = "platform-override-piloted"
SKILL_KIND_PLATFORM_PACK = "platform-pack"
SKILL_KIND_CODE_REVIEW_AREA = "code-review-area"
SKILL_KIND_ADD_ON = "add-on"

SUPPORTED_SKILL_KINDS: frozenset[str] = frozenset(
  {
    SKILL_KIND_HORIZONTAL,
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    SKILL_KIND_PLATFORM_PACK,
    SKILL_KIND_CODE_REVIEW_AREA,
    SKILL_KIND_ADD_ON,
  }
)

SHELLED_FAMILIES: frozenset[str] = frozenset({"code-review", "quality-check"})

# Family registry. The scaffolder consults this table to decide where to place
# a new skill and how to phrase its migration note.
#
# - ``code-review`` is piloted on the shell+content contract (SKILL-14). New
#   code-review platform overrides live inside the owning platform pack at
#   ``platform-packs/<slug>/code-review/<name>/``.
# - ``quality-check`` is piloted on the shell+content contract (SKILL-16)
#   with the optional ``declared_quality_check_file`` manifest key. New
#   platform quality-check overrides live inside the owning pack at
#   ``platform-packs/<slug>/quality-check/<name>/`` and register a single
#   content file (no areas map).
# - Pre-shell families (feature-implement, feature-verify) are still placed
#   under ``skills/<platform>/bill-<platform>-<capability>/`` and carry an
#   interim-location note instructing authors to move them when those
#   families get piloted onto the shell.
FAMILY_REGISTRY: dict[str, dict[str, Any]] = {
  "code-review": {
    "layout_kind": "shelled",
    "base_path_template": "platform-packs/{platform}/code-review/{name}",
    "is_shelled": True,
    "manifest_key": None,  # registered via declared_files.areas
  },
  "quality-check": {
    "layout_kind": "shelled",
    "base_path_template": "platform-packs/{platform}/quality-check/{name}",
    "is_shelled": True,
    "manifest_key": "declared_quality_check_file",
  },
  "feature-implement": {
    "layout_kind": "pre-shell",
    "base_path_template": "skills/{platform}/{name}",
    "is_shelled": False,
    "manifest_key": None,
  },
  "feature-verify": {
    "layout_kind": "pre-shell",
    "base_path_template": "skills/{platform}/{name}",
    "is_shelled": False,
    "manifest_key": None,
  },
  "workflow": {
    "layout_kind": "horizontal",
    "base_path_template": "skills/{name}",
    "is_shelled": False,
    "manifest_key": None,
  },
  "advisor": {
    "layout_kind": "horizontal",
    "base_path_template": "skills/{name}",
    "is_shelled": False,
    "manifest_key": None,
  },
}

PLATFORM_PACK_PRESETS: dict[str, dict[str, Any]] = {
  "java": {
    "display_name": "Java",
    "routing_signals": {
      "strong": ["pom.xml", "build.gradle", "src/main/java"],
      "tie_breakers": [
        "Prefer Java when Maven metadata or Java source markers dominate generic JVM signals."
      ],
    },
  },
  "php": {
    "display_name": "PHP",
    "routing_signals": {
      "strong": ["composer.json", ".php", "phpunit.xml"],
      "tie_breakers": [
        "Prefer PHP when Composer metadata or .php source files dominate mixed backend signals."
      ],
    },
  },
}

PLATFORM_PACK_SKELETON_STARTER = "starter"
PLATFORM_PACK_SKELETON_FULL = "full"
PLATFORM_PACK_SKELETON_MODES: frozenset[str] = frozenset(
  {
    PLATFORM_PACK_SKELETON_STARTER,
    PLATFORM_PACK_SKELETON_FULL,
  }
)

GOVERNED_CONTENT_AUTHORING_NOTE = (
  "Author skill instructions only in sibling `content.md` files. "
  "Keep scaffold-managed `SKILL.md` wrappers and `shell-ceremony.md` unchanged "
  "unless you are intentionally changing the shared contract."
)

NON_GOVERNED_REQUIRED_SECTIONS: tuple[str, ...] = (
  "## Description",
  "## Specialist Scope",
  "## Inputs",
  "## Outputs Contract",
  "## Execution Mode Reporting",
  "## Telemetry Ceremony Hooks",
)


@dataclass
class ManifestEdit:
  """Records a single manifest mutation for rollback purposes.

  Attributes:
    manifest_path: absolute path to the edited ``platform.yaml``.
    original_bytes: exact byte snapshot captured before mutation. Used to
      restore the file verbatim on rollback so key order and comments are
      preserved.
    existed: whether the manifest existed before the scaffolder ran. New
      manifests are not supported today — the scaffolder refuses to create
      pack roots — but we still capture the flag so ``restore`` stays
      symmetric if we ever lift that restriction.
  """

  manifest_path: Path
  original_bytes: bytes
  existed: bool = True


@dataclass
class ScaffoldResult:
  """Summary of a successful scaffold.

  Every path in this result is absolute. Callers (the CLI, tests, reviewers)
  rely on this shape to produce a dry-run preview or to verify a scaffolded
  skill after the fact. The scaffolder intentionally returns plain data so
  consumers never need to touch the transaction machinery.
  """

  kind: str
  skill_name: str
  skill_path: Path
  created_files: list[Path] = field(default_factory=list)
  manifest_edits: list[Path] = field(default_factory=list)
  symlinks: list[Path] = field(default_factory=list)
  install_targets: list[Path] = field(default_factory=list)
  notes: list[str] = field(default_factory=list)


@dataclass
class _ScaffoldTransaction:
  """Bookkeeping for atomic rollback.

  The scaffolder records every filesystem mutation it makes so a single
  ``rollback`` call can undo the whole operation. We keep three parallel
  lists in reverse-chronological execution order so rollback unwinds
  symmetrically: install → symlinks → manifests → files → empty dirs.
  """

  created_paths: list[Path] = field(default_factory=list)
  created_dirs: list[Path] = field(default_factory=list)
  created_symlinks: list[Path] = field(default_factory=list)
  manifest_snapshots: list[ManifestEdit] = field(default_factory=list)
  install_targets: list[Path] = field(default_factory=list)


def _validate_payload_version(payload: dict) -> None:
  payload_version = payload.get("scaffold_payload_version")
  if payload_version is None:
    raise InvalidScaffoldPayloadError(
      "Scaffold payload is missing required field 'scaffold_payload_version'."
    )
  if payload_version != SCAFFOLD_PAYLOAD_VERSION:
    raise ScaffoldPayloadVersionMismatchError(
      f"Scaffold payload declares 'scaffold_payload_version' "
      f"'{payload_version}' but the scaffolder expects "
      f"'{SCAFFOLD_PAYLOAD_VERSION}'. Bump the caller and the scaffolder "
      "together when the contract changes."
    )


def _require_string(payload: dict, key: str) -> str:
  value = payload.get(key)
  if not isinstance(value, str) or not value:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field '{key}' must be a non-empty string."
    )
  return value


def _optional_string(payload: dict, key: str) -> str:
  value = payload.get(key, "")
  if value is None:
    return ""
  if not isinstance(value, str):
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field '{key}' must be a string when provided."
    )
  return value


def _optional_bool(payload: dict, key: str, default: bool = False) -> bool:
  value = payload.get(key, default)
  if isinstance(value, bool):
    return value
  if value is None:
    return default
  raise InvalidScaffoldPayloadError(
    f"Scaffold payload field '{key}' must be a boolean when provided."
  )


def _require_string_list(value: object, *, field_name: str) -> list[str]:
  if not isinstance(value, list):
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field '{field_name}' must be a list of strings."
    )
  result: list[str] = []
  for entry in value:
    if not isinstance(entry, str) or not entry:
      raise InvalidScaffoldPayloadError(
        f"Scaffold payload field '{field_name}' must contain only non-empty strings."
      )
    result.append(entry)
  return result


_SUBAGENT_NAME_PATTERN = re.compile(r"^[a-z][a-z0-9-]*$")


_ORCHESTRATOR_KINDS_FOR_SUBAGENTS: frozenset[str] = frozenset(
  {
    SKILL_KIND_HORIZONTAL,
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    SKILL_KIND_PLATFORM_PACK,
  }
)


def _optional_specialist_subagents(payload: dict, *, kind: str) -> tuple[list[str], bool]:
  raw_specialists = payload.get("subagent_specialists", [])
  raw_suppress = payload.get("no_subagents", False)

  if raw_specialists is None:
    raw_specialists = []
  if not isinstance(raw_specialists, list):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'subagent_specialists' must be a list of strings."
    )

  specialists: list[str] = []
  seen: set[str] = set()
  for entry in raw_specialists:
    if not isinstance(entry, str) or not entry:
      raise InvalidScaffoldPayloadError(
        "Scaffold payload field 'subagent_specialists' must contain only non-empty strings."
      )
    if not _SUBAGENT_NAME_PATTERN.match(entry):
      raise InvalidScaffoldPayloadError(
        f"Scaffold payload field 'subagent_specialists' contains invalid name '{entry}'; "
        "names must match '^[a-z][a-z0-9-]*$'."
      )
    if entry in seen:
      raise InvalidScaffoldPayloadError(
        f"Scaffold payload field 'subagent_specialists' contains duplicate name '{entry}'."
      )
    seen.add(entry)
    specialists.append(entry)

  if not isinstance(raw_suppress, bool):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'no_subagents' must be a boolean when provided."
    )

  if raw_suppress and specialists:
    raise InvalidScaffoldPayloadError(
      "Scaffold payload may not set 'no_subagents=true' together with a non-empty "
      "'subagent_specialists' list."
    )

  if specialists and kind not in _ORCHESTRATOR_KINDS_FOR_SUBAGENTS:
    raise InvalidScaffoldPayloadError(
      f"subagent_specialists is only valid for orchestrator kinds (horizontal, "
      f"platform-override-piloted, platform-pack); got {kind}"
    )

  return specialists, raw_suppress


def _optional_string_list_from_mapping(mapping: object, key: str) -> list[str]:
  if not isinstance(mapping, dict):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'routing_signals' must be a mapping when provided."
    )
  value = mapping.get(key, [])
  if value is None:
    return []
  return _require_string_list(value, field_name=f"routing_signals.{key}")


def _require_string_list_from_mapping(mapping: object, key: str) -> list[str]:
  if not isinstance(mapping, dict):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'routing_signals' must be a mapping."
    )
  return _require_string_list(mapping.get(key), field_name=f"routing_signals.{key}")


def _derive_display_name(platform: str) -> str:
  return " ".join(part.capitalize() for part in platform.split("-"))


def _optional_explicit_string_list_from_mapping(
  mapping: object,
  key: str,
) -> list[str] | None:
  if mapping is None:
    return None
  if not isinstance(mapping, dict):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'routing_signals' must be a mapping when provided."
    )
  if key not in mapping:
    return None
  return _require_string_list(mapping.get(key), field_name=f"routing_signals.{key}")


def platform_pack_preset(platform: str) -> dict[str, Any] | None:
  preset = PLATFORM_PACK_PRESETS.get(platform)
  if preset is None:
    return None
  routing_signals = preset["routing_signals"]
  return {
    "display_name": str(preset.get("display_name", "")),
    "routing_signals": {
      "strong": list(routing_signals["strong"]),
      "tie_breakers": list(routing_signals.get("tie_breakers", [])),
    },
  }


def _resolve_platform_pack_defaults(payload: dict, platform: str) -> dict[str, Any]:
  preset = platform_pack_preset(platform)
  routing_signals = payload.get("routing_signals")

  strong_signals = _optional_explicit_string_list_from_mapping(routing_signals, "strong")
  tie_breakers = _optional_explicit_string_list_from_mapping(routing_signals, "tie_breakers")

  if strong_signals is None and preset is not None:
    strong_signals = list(preset["routing_signals"]["strong"])
  if tie_breakers is None and preset is not None:
    tie_breakers = list(preset["routing_signals"]["tie_breakers"])

  if not strong_signals:
    if preset is None:
      raise InvalidScaffoldPayloadError(
        "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal "
        f"when no built-in platform preset exists for '{platform}'."
      )
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal."
    )

  display_name = _optional_string(payload, "display_name")
  if not display_name and preset is not None:
    display_name = preset["display_name"]
  if not display_name:
    display_name = _derive_display_name(platform)

  return {
    "display_name": display_name,
    "routing_signals": {
      "strong": strong_signals,
      "tie_breakers": tie_breakers or [],
    },
    "preset_used": preset is not None and routing_signals is None,
  }


def _platform_pack_skeleton_mode(payload: dict) -> str:
  # New platform packs now default to the fully scaffolded review surface.
  skeleton_mode = _optional_string(payload, "skeleton_mode") or PLATFORM_PACK_SKELETON_FULL
  if skeleton_mode not in PLATFORM_PACK_SKELETON_MODES:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field 'skeleton_mode' must be one of "
      f"{sorted(PLATFORM_PACK_SKELETON_MODES)} when provided."
    )
  return skeleton_mode


def _ordered_approved_code_review_areas() -> tuple[str, ...]:
  return tuple(sorted(APPROVED_CODE_REVIEW_AREAS))


def _platform_pack_specialist_areas(payload: dict) -> list[str] | None:
  raw = payload.get("specialist_areas")
  if raw is None:
    return None
  if not isinstance(raw, list) or not all(isinstance(item, str) and item for item in raw):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'specialist_areas' must be a list of non-empty strings when provided."
    )

  approved = set(_ordered_approved_code_review_areas())
  requested = {item for item in raw}
  unknown = sorted(requested - approved)
  if unknown:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field 'specialist_areas' contains unknown areas {unknown}; "
      f"approved areas: {sorted(approved)}."
    )
  return [area for area in _ordered_approved_code_review_areas() if area in requested]


def _require_canonical_name(payload: dict, *, default_name: str) -> str:
  provided = _optional_string(payload, "name")
  if not provided:
    return default_name
  if provided != default_name:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field 'name' must be '{default_name}' for this scaffold kind."
    )
  return provided


def _detect_kind(payload: dict) -> str:
  kind = _require_string(payload, "kind")
  if kind not in SUPPORTED_SKILL_KINDS:
    raise UnknownSkillKindError(
      f"Scaffold payload declares unsupported kind '{kind}'. "
      f"Supported kinds: {sorted(SUPPORTED_SKILL_KINDS)}."
    )
  return kind


def _resolve_repo_root(payload: dict) -> Path:
  repo_root_raw = payload.get("repo_root")
  if repo_root_raw is None:
    return Path.cwd().resolve()
  if not isinstance(repo_root_raw, str) or not repo_root_raw:
    raise InvalidScaffoldPayloadError(
      "Scaffold payload field 'repo_root' must be a non-empty string when provided."
    )
  return Path(repo_root_raw).resolve()


def _plan_horizontal(payload: dict, repo_root: Path) -> dict[str, Any]:
  name = _require_string(payload, "name")
  skill_path = repo_root / "skills" / name
  specialists, suppressed = _optional_specialist_subagents(
    payload, kind=SKILL_KIND_HORIZONTAL
  )
  return {
    "kind": SKILL_KIND_HORIZONTAL,
    "skill_name": name,
    "skill_path": skill_path,
    "skill_file": skill_path / "SKILL.md",
    "family": "horizontal",
    "platform": "",
    "area": "",
    "is_shelled": False,
    "notes": [],
    "subagent_specialists": specialists,
    "subagents_suppressed": suppressed,
  }


def _plan_platform_override_piloted(payload: dict, repo_root: Path) -> dict[str, Any]:
  platform = _require_string(payload, "platform")
  family = _require_string(payload, "family")
  name = _require_canonical_name(payload, default_name=f"bill-{platform}-{family}")
  specialists, suppressed = _optional_specialist_subagents(
    payload, kind=SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
  )

  if family not in FAMILY_REGISTRY:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload declares unknown family '{family}'. "
      f"Supported families: {sorted(FAMILY_REGISTRY)}."
    )

  family_entry = FAMILY_REGISTRY[family]
  is_shelled = bool(family_entry["is_shelled"])

  notes: list[str] = []
  if not is_shelled:
    if family not in PRE_SHELL_FAMILIES:
      raise UnknownPreShellFamilyError(
        f"Scaffold payload declares pre-shell family '{family}' that is not "
        f"in the registered set {sorted(PRE_SHELL_FAMILIES)}."
      )
    skill_path = repo_root / "skills" / platform / name
    notes.append(
      f"Pre-shell family '{family}' placed at '{skill_path.relative_to(repo_root)}'; "
      "will move when the family is piloted onto the shell+content contract."
    )
  else:
    pack_root = repo_root / "platform-packs" / platform
    manifest_path = pack_root / "platform.yaml"
    if not manifest_path.is_file():
      raise MissingPlatformPackError(
        f"Platform pack '{platform}' does not exist at '{pack_root}'. "
        "Create a conforming platform.yaml before adding a skill into it."
      )
    pack = load_platform_pack(pack_root)
    skill_path = pack_root / family / name
    notes.append(GOVERNED_CONTENT_AUTHORING_NOTE)

  return {
    "kind": SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    "skill_name": name,
    "skill_path": skill_path,
    "skill_file": skill_path / "SKILL.md",
    "family": family,
    "platform": platform,
    "area": "",
    "is_shelled": is_shelled,
    "display_name": pack.display_name if is_shelled else _derive_display_name(platform),
    "descriptor_metadata": {"area_focus": ""},
    "notes": notes,
    "content_file": skill_path / "content.md" if is_shelled else None,
    "subagent_specialists": specialists,
    "subagents_suppressed": suppressed,
  }


def _plan_platform_pack(payload: dict, repo_root: Path) -> dict[str, Any]:
  platform = _require_string(payload, "platform")
  specialists, suppressed = _optional_specialist_subagents(
    payload, kind=SKILL_KIND_PLATFORM_PACK
  )
  defaults = _resolve_platform_pack_defaults(payload, platform)
  skeleton_mode = _platform_pack_skeleton_mode(payload)
  selected_specialist_areas = _platform_pack_specialist_areas(payload)
  if selected_specialist_areas is not None and "skeleton_mode" in payload:
    raise InvalidScaffoldPayloadError(
      "Scaffold payload may not provide both 'skeleton_mode' and 'specialist_areas'; choose one specialist selection mode."
    )
  strong_signals = defaults["routing_signals"]["strong"]
  tie_breakers = defaults["routing_signals"]["tie_breakers"]
  display_name = defaults["display_name"]
  description = _optional_string(payload, "description")

  baseline_name = _require_canonical_name(
    payload,
    default_name=f"bill-{platform}-code-review",
  )
  quality_check_name = f"bill-{platform}-quality-check"
  pack_root = repo_root / "platform-packs" / platform
  if pack_root.exists():
    raise SkillAlreadyExistsError(
      f"Platform pack target '{pack_root}' already exists. Remove it or pick a new platform slug before retrying."
    )

  baseline_skill_path = pack_root / "code-review" / baseline_name
  quality_check_skill_path = pack_root / "quality-check" / quality_check_name
  manifest_path = pack_root / "platform.yaml"
  specialist_areas = (
    selected_specialist_areas
    if selected_specialist_areas is not None
    else (
      list(_ordered_approved_code_review_areas())
      if skeleton_mode == PLATFORM_PACK_SKELETON_FULL
      else []
    )
  )
  specialist_skill_names = {
    area: f"bill-{platform}-code-review-{area}"
    for area in specialist_areas
  }
  specialist_skill_paths = {
    area: pack_root / "code-review" / specialist_skill_names[area]
    for area in specialist_areas
  }
  specialist_area_metadata = {
    area: default_area_focus(area)
    for area in specialist_areas
  }
  notes: list[str] = [
    "Quality-check scaffolded by default.",
    "Follow-on code-review-area scaffolds can extend the pack without manual manifest edits.",
    GOVERNED_CONTENT_AUTHORING_NOTE,
  ]
  if defaults["preset_used"]:
    notes.insert(
      0,
      f"Applied built-in platform preset for '{platform}'. Override 'routing_signals' only when the defaults need adjustment.",
    )
  if selected_specialist_areas is not None:
    notes.insert(
      1 if defaults["preset_used"] else 0,
      f"Custom skeleton scaffolded with {len(specialist_areas)} approved code-review area stubs.",
    )
  elif skeleton_mode == PLATFORM_PACK_SKELETON_FULL:
    notes.insert(
      1 if defaults["preset_used"] else 0,
      f"Full skeleton scaffolded with {len(specialist_areas)} approved code-review area stubs.",
    )

  return {
    "kind": SKILL_KIND_PLATFORM_PACK,
    "skill_name": baseline_name,
    "skill_path": pack_root,
    "skill_file": baseline_skill_path / "SKILL.md",
    "family": "code-review",
    "platform": platform,
    "area": "",
    "is_shelled": True,
    "notes": notes,
    "display_name": display_name,
    "description": description,
    "skeleton_mode": skeleton_mode,
    "routing_signals": {
      "strong": strong_signals,
      "tie_breakers": tie_breakers,
    },
    "manifest_path": manifest_path,
    "baseline_skill_name": baseline_name,
    "baseline_skill_path": baseline_skill_path,
    "baseline_skill_file": baseline_skill_path / "SKILL.md",
    "quality_check_skill_name": quality_check_name,
    "quality_check_skill_path": quality_check_skill_path,
    "quality_check_skill_file": quality_check_skill_path / "SKILL.md",
    "specialist_areas": specialist_areas,
    "specialist_area_metadata": specialist_area_metadata,
    "specialist_skill_names": specialist_skill_names,
    "specialist_skill_paths": specialist_skill_paths,
    "subagent_specialists": specialists,
    "subagents_suppressed": suppressed,
    "install_paths": [
      baseline_skill_path,
      quality_check_skill_path,
      *specialist_skill_paths.values(),
    ],
    "created_files": [
      manifest_path,
      baseline_skill_path / "SKILL.md",
      baseline_skill_path / "content.md",
      quality_check_skill_path / "SKILL.md",
      quality_check_skill_path / "content.md",
      *(path / "content.md" for path in specialist_skill_paths.values()),
      *(path / "SKILL.md" for path in specialist_skill_paths.values()),
    ],
  }


def _plan_code_review_area(payload: dict, repo_root: Path) -> dict[str, Any]:
  platform = _require_string(payload, "platform")
  area = _require_string(payload, "area")
  name = _require_canonical_name(
    payload,
    default_name=f"bill-{platform}-code-review-{area}",
  )

  if area not in APPROVED_CODE_REVIEW_AREAS:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload declares code-review area '{area}' that is not "
      f"in the approved set {sorted(APPROVED_CODE_REVIEW_AREAS)}."
    )

  pack_root = repo_root / "platform-packs" / platform
  manifest_path = pack_root / "platform.yaml"
  if not manifest_path.is_file():
    raise MissingPlatformPackError(
      f"Platform pack '{platform}' does not exist at '{pack_root}'. "
      "Create a conforming platform.yaml before adding a code-review area to it."
    )
  pack = load_platform_pack(pack_root)

  skill_path = pack_root / "code-review" / name
  return {
    "kind": SKILL_KIND_CODE_REVIEW_AREA,
    "skill_name": name,
    "skill_path": skill_path,
    "skill_file": skill_path / "SKILL.md",
    "family": "code-review",
    "platform": platform,
    "area": area,
    "is_shelled": True,
    "display_name": pack.display_name or _derive_display_name(platform),
    "descriptor_metadata": {"area_focus": default_area_focus(area)},
    "notes": [GOVERNED_CONTENT_AUTHORING_NOTE],
    "content_file": skill_path / "content.md",
    "subagent_specialists": [],
    "subagents_suppressed": False,
  }


def _plan_add_on(payload: dict, repo_root: Path) -> dict[str, Any]:
  name = _require_string(payload, "name")
  platform = _require_string(payload, "platform")

  pack_root = repo_root / "platform-packs" / platform
  if not (pack_root / "platform.yaml").is_file():
    raise MissingPlatformPackError(
      f"Platform pack '{platform}' does not exist at '{pack_root}'. "
      "Create a conforming platform.yaml before adding a governed add-on into it."
    )
  addons_root = pack_root / "addons"
  skill_file = addons_root / f"{name}.md"
  return {
    "kind": SKILL_KIND_ADD_ON,
    "skill_name": name,
    "skill_path": addons_root,
    "skill_file": skill_file,
    "family": "add-on",
    "platform": platform,
    "area": "",
    "is_shelled": False,
    "notes": [],
    "subagent_specialists": [],
    "subagents_suppressed": False,
  }


def infer_plan_from_skill_file(skill_file: Path, frontmatter: dict[str, str]) -> dict[str, Any]:
  """Infer the minimal scaffolder plan needed to re-render a governed wrapper."""
  skill_file = skill_file.resolve()
  skill_name = frontmatter.get("name") or skill_file.parent.name
  parts = skill_file.parts
  if "platform-packs" not in parts:
    raise InvalidScaffoldPayloadError(
      f"Cannot infer governed skill plan from '{skill_file}': expected a platform-pack skill."
    )

  root_index = parts.index("platform-packs")
  try:
    platform = parts[root_index + 1]
    family = parts[root_index + 2]
  except IndexError as error:
    raise InvalidScaffoldPayloadError(
      f"Cannot infer governed skill plan from '{skill_file}': expected "
      "'platform-packs/<platform>/<family>/<skill>/SKILL.md'."
    ) from error

  if family not in {"code-review", "quality-check"}:
    raise InvalidScaffoldPayloadError(
      f"Cannot infer governed skill plan from '{skill_file}': unsupported family '{family}'."
    )

  area = ""
  descriptor_metadata = {"area_focus": ""}
  code_review_prefix = f"bill-{platform}-code-review-"
  if family == "code-review" and skill_name.startswith(code_review_prefix):
    area = skill_name.removeprefix(code_review_prefix)
  try:
    pack = load_platform_manifest(skill_file.parents[2])
    display_name = pack.display_name or _derive_display_name(platform)
    if area:
      descriptor_metadata["area_focus"] = pack.area_metadata.get(area, default_area_focus(area))
  except Exception:
    display_name = _derive_display_name(platform)
    if area:
      descriptor_metadata["area_focus"] = default_area_focus(area)

  return {
    "kind": SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    "skill_name": skill_name,
    "skill_path": skill_file.parent,
    "skill_file": skill_file,
    "family": family,
    "platform": platform,
    "area": area,
    "is_shelled": True,
    "display_name": display_name,
    "descriptor_metadata": descriptor_metadata,
    "notes": [],
    "content_file": skill_file.with_name("content.md"),
  }


_PLANNERS: dict[str, Any] = {
  SKILL_KIND_HORIZONTAL: _plan_horizontal,
  SKILL_KIND_PLATFORM_OVERRIDE_PILOTED: _plan_platform_override_piloted,
  SKILL_KIND_PLATFORM_PACK: _plan_platform_pack,
  SKILL_KIND_CODE_REVIEW_AREA: _plan_code_review_area,
  SKILL_KIND_ADD_ON: _plan_add_on,
}


def _render_skill_body(plan: dict[str, Any], payload: dict) -> str:
  """Render the canonical three-section SKILL.md body for any family.

  Every scaffolded SKILL.md emits ``## Descriptor`` + ``## Execution`` +
  ``## Ceremony`` and nothing else. Family-specific authored prose lives in
  the sibling ``content.md``.
  """
  platform = plan["platform"]
  display_name = plan.get("display_name") or (
    _derive_display_name(platform) if platform else ""
  )
  context = ScaffoldTemplateContext(
    skill_name=plan["skill_name"],
    family=plan["family"],
    platform=platform,
    area=plan["area"],
    display_name=display_name,
  )

  description = _optional_string(payload, "description") or infer_skill_description(context)
  front_matter = render_skill_frontmatter(plan["skill_name"], description)

  descriptor_section = render_descriptor_section(
    context,
    metadata=DescriptorMetadata(
      area_focus=plan.get("descriptor_metadata", {}).get("area_focus", "")
    ),
  )
  sections = [
    descriptor_section,
    render_default_section("## Execution", context),
    render_default_section("## Ceremony", context),
  ]
  return f"{front_matter}\n" + "\n".join(sections)


def _render_governed_content_body(plan: dict[str, Any], payload: dict) -> str:
  """Render the authored `content.md` body for governed platform-pack skills."""
  platform = plan["platform"]
  display_name = plan.get("display_name") or (
    _derive_display_name(platform) if platform else ""
  )
  context = ScaffoldTemplateContext(
    skill_name=plan["skill_name"],
    family=plan["family"],
    platform=platform,
    area=plan["area"],
    display_name=display_name,
  )
  description = _optional_string(payload, "description") or infer_skill_description(context)
  content_body = _optional_string(payload, "content_body") or None
  return render_content_body(
    context,
    description=description,
    content_body=content_body,
  )


def _render_addon_body(plan: dict[str, Any], payload: dict) -> str:
  explicit_body = _optional_string(payload, "body")
  if explicit_body:
    return explicit_body if explicit_body.endswith("\n") else f"{explicit_body}\n"

  platform = plan["platform"]
  display_name = plan.get("display_name") or (
    _derive_display_name(platform) if platform else ""
  )
  context = ScaffoldTemplateContext(
    skill_name=plan["skill_name"],
    family=plan["family"],
    platform=platform,
    area=plan["area"],
    display_name=display_name,
  )
  description = _optional_string(payload, "description") or infer_skill_description(context)
  return (
    f"# {plan['skill_name']}\n"
    "\n"
    f"{description}\n"
    "\n"
    "TODO: author the add-on body.\n"
  )


def _stage_file(txn: _ScaffoldTransaction, path: Path, content: str) -> None:
  if path.exists():
    raise SkillAlreadyExistsError(
      f"Skill target '{path}' already exists. Remove it or pick a new name "
      "before retrying."
    )

  parents_to_create: list[Path] = []
  cursor = path.parent
  while not cursor.exists():
    parents_to_create.append(cursor)
    cursor = cursor.parent
  for parent in reversed(parents_to_create):
    parent.mkdir()
    txn.created_dirs.append(parent)

  path.write_text(content, encoding="utf-8")
  txn.created_paths.append(path)


def _append_runtime_notes_to_content_md(content_path: Path, runtime_notes: str) -> None:
  if not runtime_notes:
    return
  existing = content_path.read_text(encoding="utf-8")
  separator = "" if existing.endswith("\n\n") else ("\n" if existing.endswith("\n") else "\n\n")
  content_path.write_text(existing + separator + runtime_notes + "\n", encoding="utf-8")


def _stage_subagent_stubs(
  txn: _ScaffoldTransaction,
  *,
  orchestrator_skill_path: Path,
  orchestrator_name: str,
  specialists: list[str],
) -> list[Path]:
  staged: list[Path] = []
  if not specialists:
    return staged
  codex_dir = orchestrator_skill_path / "codex-agents"
  opencode_dir = orchestrator_skill_path / "opencode-agents"
  for specialist in specialists:
    codex_path = codex_dir / f"{specialist}.toml"
    _stage_file(
      txn,
      codex_path,
      render_codex_agent_toml_stub(specialist, orchestrator_name),
    )
    staged.append(codex_path)
    opencode_path = opencode_dir / f"{specialist}.md"
    _stage_file(
      txn,
      opencode_path,
      render_opencode_agent_md_stub(specialist, orchestrator_name),
    )
    staged.append(opencode_path)
  return staged


def _snapshot_manifest(txn: _ScaffoldTransaction, manifest_path: Path) -> None:
  original_bytes = manifest_path.read_bytes()
  txn.manifest_snapshots.append(
    ManifestEdit(manifest_path=manifest_path, original_bytes=original_bytes, existed=True)
  )


def _apply_manifest_edits(txn: _ScaffoldTransaction, plan: dict[str, Any], repo_root: Path) -> list[Path]:
  """Append platform-pack manifest entries for shelled kinds.

  - ``code-review-area`` appends a new area to both
    ``declared_code_review_areas`` and ``declared_files.areas``.
  - ``platform-override-piloted`` for a shelled ``quality-check`` override
    registers ``declared_quality_check_file`` on the pack manifest.
  """
  if plan["kind"] == SKILL_KIND_CODE_REVIEW_AREA:
    # Import lazily inside the function to avoid a hard dep on scaffold_manifest
    # for callers that only use add-on/horizontal kinds.
    from skill_bill.scaffold_manifest import append_code_review_area

    manifest_path = repo_root / "platform-packs" / plan["platform"] / "platform.yaml"
    _snapshot_manifest(txn, manifest_path)

    declared_area_path = plan["skill_file"].relative_to(repo_root / "platform-packs" / plan["platform"]).as_posix()
    append_code_review_area(
      manifest_path=manifest_path,
      area=plan["area"],
      relative_content_path=declared_area_path,
      area_focus=plan.get("descriptor_metadata", {}).get("area_focus", ""),
    )
    return [manifest_path]

  if (
    plan["kind"] == SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
    and plan["is_shelled"]
    and plan["family"] == "quality-check"
  ):
    from skill_bill.scaffold_manifest import set_declared_quality_check_file

    manifest_path = repo_root / "platform-packs" / plan["platform"] / "platform.yaml"
    _snapshot_manifest(txn, manifest_path)

    declared_path = plan["skill_file"].relative_to(
      repo_root / "platform-packs" / plan["platform"]
    ).as_posix()
    set_declared_quality_check_file(
      manifest_path=manifest_path,
      relative_content_path=declared_path,
    )
    return [manifest_path]

  return []


def _create_platform_pack(
  txn: _ScaffoldTransaction,
  plan: dict[str, Any],
  repo_root: Path,
) -> tuple[list[Path], list[Path]]:
  """Materialize a new platform pack root with baseline review and quality-check skills."""
  from scripts.skill_repo_contracts import required_supporting_files_for_skill
  from skill_bill.scaffold_manifest import render_platform_pack_manifest

  pack_root = plan["skill_path"]
  manifest_path = plan["manifest_path"]
  baseline_skill_path = plan["baseline_skill_path"]
  quality_check_skill_path = plan["quality_check_skill_path"]
  baseline_name = plan["baseline_skill_name"]
  quality_check_name = plan["quality_check_skill_name"]
  baseline_description = plan["description"] or (
    f"Use when reviewing changes in {plan['display_name']} codebases."
  )
  quality_check_description = (
    f"Use when validating {plan['display_name']} changes with the shared quality-check contract."
  )

  manifest_content = render_platform_pack_manifest(
    platform=plan["platform"],
    display_name=plan["display_name"],
    strong_signals=plan["routing_signals"]["strong"],
    tie_breakers=plan["routing_signals"]["tie_breakers"],
    declared_code_review_areas=list(plan["specialist_areas"]),
    baseline_content_path=baseline_skill_path.relative_to(pack_root).joinpath("SKILL.md").as_posix(),
    declared_area_files={
      area: path.relative_to(pack_root).joinpath("SKILL.md").as_posix()
      for area, path in plan["specialist_skill_paths"].items()
    },
    declared_quality_check_file=quality_check_skill_path.relative_to(pack_root).joinpath("SKILL.md").as_posix(),
    area_metadata=plan["specialist_area_metadata"],
  )
  _stage_file(txn, manifest_path, manifest_content)
  baseline_plan = {
    "kind": SKILL_KIND_PLATFORM_PACK,
    "skill_name": baseline_name,
    "skill_path": baseline_skill_path,
    "skill_file": baseline_skill_path / "SKILL.md",
    "family": "code-review",
    "platform": plan["platform"],
    "display_name": plan["display_name"],
    "area": "",
    "is_shelled": True,
    "descriptor_metadata": {"area_focus": ""},
    "notes": [],
    "content_file": baseline_skill_path / "content.md",
  }
  quality_check_plan = {
    "kind": SKILL_KIND_PLATFORM_PACK,
    "skill_name": quality_check_name,
    "skill_path": quality_check_skill_path,
    "skill_file": quality_check_skill_path / "SKILL.md",
    "family": "quality-check",
    "platform": plan["platform"],
    "display_name": plan["display_name"],
    "area": "",
    "is_shelled": True,
    "descriptor_metadata": {"area_focus": ""},
    "notes": [],
    "content_file": quality_check_skill_path / "content.md",
  }
  specialist_plans = [
    {
      "kind": SKILL_KIND_PLATFORM_PACK,
      "skill_name": plan["specialist_skill_names"][area],
      "skill_path": plan["specialist_skill_paths"][area],
      "skill_file": plan["specialist_skill_paths"][area] / "SKILL.md",
      "family": "code-review",
      "platform": plan["platform"],
      "display_name": plan["display_name"],
      "area": area,
      "is_shelled": True,
      "descriptor_metadata": {"area_focus": plan["specialist_area_metadata"][area]},
      "notes": [],
      "content_file": plan["specialist_skill_paths"][area] / "content.md",
    }
    for area in plan["specialist_areas"]
  ]
  _stage_file(
    txn,
    baseline_plan["skill_file"],
    _render_skill_body(baseline_plan, {"description": baseline_description}),
  )
  _stage_file(
    txn,
    baseline_plan["content_file"],
    _render_governed_content_body(baseline_plan, {"description": baseline_description}),
  )
  created_symlinks: list[Path] = []
  created_symlinks.extend(_stage_sidecar_symlinks_for_skill(
    txn,
    skill_name=baseline_name,
    skill_path=baseline_skill_path,
    repo_root=repo_root,
  ))

  _stage_file(
    txn,
    quality_check_plan["skill_file"],
    _render_skill_body(quality_check_plan, {"description": quality_check_description}),
  )
  _stage_file(
    txn,
    quality_check_plan["content_file"],
    _render_governed_content_body(quality_check_plan, {"description": quality_check_description}),
  )
  created_symlinks.extend(_stage_sidecar_symlinks_for_skill(
    txn,
    skill_name=quality_check_name,
    skill_path=quality_check_skill_path,
    repo_root=repo_root,
  ))

  for specialist_plan in specialist_plans:
    _stage_file(
      txn,
      specialist_plan["skill_file"],
      _render_skill_body(
        specialist_plan,
        {
          "description": (
            f"Use when reviewing {plan['display_name']} changes for "
            f"{specialist_plan['area']} risks."
          )
        },
      ),
    )
    _stage_file(
      txn,
      specialist_plan["content_file"],
      _render_governed_content_body(
        specialist_plan,
        {
          "description": (
            f"Use when reviewing {plan['display_name']} changes for "
            f"{specialist_plan['area']} risks."
          )
        },
      ),
    )
    created_symlinks.extend(_stage_sidecar_symlinks_for_skill(
      txn,
      skill_name=specialist_plan["skill_name"],
      skill_path=specialist_plan["skill_path"],
      repo_root=repo_root,
    ))

  return (
    [
      manifest_path,
      baseline_plan["skill_file"],
      baseline_plan["content_file"],
      quality_check_plan["skill_file"],
      quality_check_plan["content_file"],
      *(specialist_plan["skill_file"] for specialist_plan in specialist_plans),
      *(specialist_plan["content_file"] for specialist_plan in specialist_plans),
    ],
    created_symlinks,
  )


def _stage_sidecar_symlinks_for_skill(
  txn: _ScaffoldTransaction,
  *,
  skill_name: str,
  skill_path: Path,
  repo_root: Path,
) -> list[Path]:
  """Wire sibling supporting-file symlinks for a single scaffolded skill."""
  from scripts.skill_repo_contracts import (
    required_supporting_files_for_skill,
    supporting_file_targets,
  )

  required = required_supporting_files_for_skill(skill_name)
  if not required:
    return []

  targets_map = supporting_file_targets(repo_root)
  created: list[Path] = []
  for file_name in required:
    target = targets_map.get(file_name)
    if target is None:
      raise MissingSupportingFileTargetError(
        f"Runtime supporting file '{file_name}' is not registered in "
        "SUPPORTING_FILE_TARGETS; add it or remove the reference from "
        "RUNTIME_SUPPORTING_FILES."
      )
    link_path = skill_path / file_name
    if link_path.exists() or link_path.is_symlink():
      continue
    link_path.symlink_to(target)
    txn.created_symlinks.append(link_path)
    created.append(link_path)
  return created


def _preview_sidecar_symlinks_for_skill(
  *,
  skill_name: str,
  skill_path: Path,
  repo_root: Path,
) -> list[Path]:
  """Preview the symlink targets a scaffolded skill would receive."""
  from scripts.skill_repo_contracts import (
    required_supporting_files_for_skill,
    supporting_file_targets,
  )

  required = required_supporting_files_for_skill(skill_name)
  if not required:
    return []

  targets_map = supporting_file_targets(repo_root)
  preview: list[Path] = []
  for file_name in required:
    target = targets_map.get(file_name)
    if target is None:
      raise MissingSupportingFileTargetError(
        f"Runtime supporting file '{file_name}' is not registered in "
        "SUPPORTING_FILE_TARGETS; add it or remove the reference from "
        "RUNTIME_SUPPORTING_FILES."
      )
    preview.append(skill_path / file_name)
  return preview


def _stage_sidecar_symlinks(txn: _ScaffoldTransaction, plan: dict[str, Any], repo_root: Path) -> list[Path]:
  """Wire sibling supporting-file symlinks for single-skill scaffold kinds."""
  return _stage_sidecar_symlinks_for_skill(
    txn,
    skill_name=plan["skill_name"],
    skill_path=plan["skill_path"],
    repo_root=repo_root,
  )


def _load_validator_module(repo_root: Path):
  """Load the retired Python validator when present for compatibility."""
  script_path = repo_root / "scripts" / ("validate_agent_configs" + ".py")
  if not script_path.is_file():
    return None

  scripts_dir = str(script_path.parent)
  repo_root_str = str(repo_root)
  sys.path.insert(0, scripts_dir)
  sys.path.insert(0, repo_root_str)
  try:
    spec = importlib.util.spec_from_file_location(
      "_skill_bill_scaffold_validator",
      script_path,
    )
    if spec is None or spec.loader is None:
      raise ScaffoldValidatorError(
        f"Validator failed after scaffolding: could not load '{script_path}'."
      )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
  finally:
    for entry in (repo_root_str, scripts_dir):
      if entry in sys.path:
        sys.path.remove(entry)


def _append_selected_skill_issues(
  *,
  repo_root: Path,
  validator: Any,
  skill_names: list[str],
  issues: list[str],
) -> None:
  """Validate only the governed skills touched by the current scaffold."""
  discovery_issues: list[str] = []
  skill_files = validator.discover_skill_files(repo_root, discovery_issues)
  platform_pack_skill_files = validator.discover_platform_pack_skill_files(repo_root)

  for skill_name in skill_names:
    if skill_name in skill_files:
      validator.validate_skill_file(skill_name, skill_files[skill_name], issues)
      continue
    if skill_name in platform_pack_skill_files:
      validator.validate_platform_pack_skill_file(
        skill_name,
        platform_pack_skill_files[skill_name],
        issues,
      )
      continue
    issues.append(f"Unknown skill '{skill_name}'.")


def _run_validator(repo_root: Path, plan: dict[str, Any]) -> None:
  """Validate only the artifacts touched by the current scaffold transaction."""
  validator = _load_validator_module(repo_root)
  if validator is None:
    _run_repo_validator(repo_root)
    return

  issues: list[str] = []
  touched_skill_names: list[str] = []
  touched_pack_roots: list[Path] = []

  if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
    touched_skill_names.append(plan["baseline_skill_name"])
    touched_skill_names.append(plan["quality_check_skill_name"])
    touched_skill_names.extend(plan["specialist_skill_names"].values())
    touched_pack_roots.append(repo_root / "platform-packs" / plan["platform"])
  elif plan["kind"] == SKILL_KIND_ADD_ON:
    validator.validate_addon_file(plan["skill_file"], repo_root, issues)
    touched_pack_roots.append(repo_root / "platform-packs" / plan["platform"])
  else:
    touched_skill_names.append(plan["skill_name"])
    if plan["is_shelled"]:
      touched_pack_roots.append(repo_root / "platform-packs" / plan["platform"])

  if touched_skill_names:
    _append_selected_skill_issues(
      repo_root=repo_root,
      validator=validator,
      skill_names=touched_skill_names,
      issues=issues,
    )

  for pack_root in touched_pack_roots:
    try:
      pack = load_platform_pack(pack_root)
      if pack.declared_quality_check_file is not None:
        load_quality_check_content(pack)
    except ShellContentContractError as error:
      issues.append(f"{pack_root.relative_to(repo_root)}: {error}")

  if issues:
    rendered_issues = "\n".join(f"- {issue}" for issue in issues)
    raise ScaffoldValidatorError(
      "Validator failed after scaffolding (exit 1):\n"
      "Agent-config validation failed:\n"
      f"{rendered_issues}"
    )


def _run_repo_validator(repo_root: Path) -> None:
  script_path = repo_root / "scripts" / "validate_agent_configs"
  if not script_path.is_file():
    return
  result = subprocess.run(
    [str(script_path)],
    cwd=str(repo_root),
    capture_output=True,
    text=True,
    check=False,
  )
  if result.returncode != 0:
    output = result.stdout.strip() or result.stderr.strip()
    raise ScaffoldValidatorError(f"Validator failed after scaffolding (exit {result.returncode}):\n{output}")


def _perform_install(txn: _ScaffoldTransaction, plan: dict[str, Any]) -> tuple[list[Path], list[str]]:
  """Install the newly scaffolded skill into detected local agents."""
  notes: list[str] = []

  # Add-ons live inside their owning platform package as supporting files
  # and never route through auto-install. Short-circuit before probing for
  # agents so the "no agents detected" note — which is irrelevant for
  # add-ons — cannot appear.
  if plan["kind"] == SKILL_KIND_ADD_ON:
    notes.append(
      "Add-on shipped as a supporting asset of its owning platform package; "
      "auto-install does not apply."
    )
    return ([], notes)

  from skill_bill.install import InstallTransaction, detect_agents, install_skill

  install_txn = InstallTransaction()
  agents = detect_agents()

  if not agents:
    notes.append(
      "No local AI agents detected; skipping auto-install. Run `./install.sh` "
      "to set up agent paths when an agent becomes available."
    )
    return [], notes

  install_paths = plan.get("install_paths") or [plan["skill_path"]]
  targets: list[Path] = []
  for install_path in install_paths:
    targets.extend(install_skill(install_path, agents, transaction=install_txn))
  txn.install_targets.extend(targets)
  if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
    notes.append(
      "Auto-installed the generated platform-pack skills into detected local agents."
    )
  return targets, notes


def _rollback(txn: _ScaffoldTransaction) -> None:
  errors: list[str] = []

  from skill_bill.install import uninstall_targets

  try:
    uninstall_targets(txn.install_targets)
  except OSError as error:
    errors.append(f"install rollback: {error}")

  for link in reversed(txn.created_symlinks):
    try:
      if link.is_symlink() or link.exists():
        link.unlink()
    except OSError as error:
      errors.append(f"symlink {link}: {error}")

  for snapshot in reversed(txn.manifest_snapshots):
    try:
      snapshot.manifest_path.write_bytes(snapshot.original_bytes)
    except OSError as error:
      errors.append(f"manifest {snapshot.manifest_path}: {error}")

  for path in reversed(txn.created_paths):
    try:
      if path.is_file() or path.is_symlink():
        path.unlink()
    except OSError as error:
      errors.append(f"file {path}: {error}")

  for directory in reversed(txn.created_dirs):
    try:
      if directory.is_dir() and not any(directory.iterdir()):
        directory.rmdir()
    except OSError as error:
      errors.append(f"dir {directory}: {error}")

  if errors:
    raise ScaffoldRollbackError(
      "Rollback encountered errors while reverting scaffold: " + "; ".join(errors)
    )


def scaffold(payload: dict, *, dry_run: bool = False) -> ScaffoldResult:
  """Scaffold a new skill from a validated payload.

  Args:
    payload: JSON-compatible mapping conforming to
      ``orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md``.
    dry_run: when true, plan the operation and return the result without
      touching the filesystem.

  Raises:
    InvalidScaffoldPayloadError: schema violations.
    ScaffoldPayloadVersionMismatchError: wrong ``scaffold_payload_version``.
    UnknownSkillKindError: unsupported ``kind``.
    UnknownPreShellFamilyError: pre-shell family not registered.
    MissingPlatformPackError: referenced pack does not exist.
    SkillAlreadyExistsError: target path already occupied.
    ScaffoldValidatorError: validator failed post-scaffold (rolled back).
    ScaffoldRollbackError: rollback itself failed.

  Returns:
    A :class:`ScaffoldResult` describing the scaffolded (or planned) skill.
  """
  if not isinstance(payload, dict):
    raise InvalidScaffoldPayloadError(
      "Scaffold payload must be a JSON object mapping string keys to values."
    )

  _validate_payload_version(payload)
  kind = _detect_kind(payload)
  repo_root = _resolve_repo_root(payload)

  if kind not in _ORCHESTRATOR_KINDS_FOR_SUBAGENTS:
    raw_specialists = payload.get("subagent_specialists")
    if raw_specialists:
      raise InvalidScaffoldPayloadError(
        "subagent_specialists is only valid for orchestrator kinds (horizontal, "
        f"platform-override-piloted, platform-pack); got {kind}"
      )

  planner = _PLANNERS[kind]
  plan = planner(payload, repo_root)

  if dry_run:
    manifest_edits_preview: list[Path] = []
    created_files_preview = list(
      plan.get(
        "created_files",
        [
          plan["skill_file"],
          *( [plan["content_file"]] if plan.get("content_file") is not None else [] ),
        ],
      )
    )
    if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
      symlinks_preview = []
      symlinks_preview.extend(
        _preview_sidecar_symlinks_for_skill(
          skill_name=plan["baseline_skill_name"],
          skill_path=plan["baseline_skill_path"],
          repo_root=repo_root,
        )
      )
      symlinks_preview.extend(
        _preview_sidecar_symlinks_for_skill(
          skill_name=plan["quality_check_skill_name"],
          skill_path=plan["quality_check_skill_path"],
          repo_root=repo_root,
        )
      )
      for area in plan["specialist_areas"]:
        symlinks_preview.extend(
          _preview_sidecar_symlinks_for_skill(
            skill_name=plan["specialist_skill_names"][area],
            skill_path=plan["specialist_skill_paths"][area],
            repo_root=repo_root,
          )
        )
    elif plan["kind"] == SKILL_KIND_CODE_REVIEW_AREA:
      manifest_edits_preview.append(
        repo_root / "platform-packs" / plan["platform"] / "platform.yaml"
      )
      symlinks_preview = []
    elif (
      plan["kind"] == SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
      and plan["is_shelled"]
      and plan["family"] == "quality-check"
    ):
      manifest_edits_preview.append(
        repo_root / "platform-packs" / plan["platform"] / "platform.yaml"
      )
      symlinks_preview = []
    else:
      symlinks_preview = []
    specialists_preview = list(plan.get("subagent_specialists") or [])
    suppressed_preview = bool(plan.get("subagents_suppressed"))
    if specialists_preview and not suppressed_preview:
      if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
        stub_dir = plan["baseline_skill_path"]
      else:
        stub_dir = plan["skill_path"]
      for specialist in specialists_preview:
        created_files_preview.append(stub_dir / "codex-agents" / f"{specialist}.toml")
        created_files_preview.append(stub_dir / "opencode-agents" / f"{specialist}.md")
    result = ScaffoldResult(
      kind=plan["kind"],
      skill_name=plan["skill_name"],
      skill_path=plan["skill_path"],
      created_files=created_files_preview,
      manifest_edits=manifest_edits_preview,
      symlinks=symlinks_preview,
      install_targets=[],
      notes=list(plan["notes"])
      + ["Dry run — no filesystem changes applied."],
    )
    return result

  txn = _ScaffoldTransaction()

  specialists = list(plan.get("subagent_specialists") or [])
  suppressed = bool(plan.get("subagents_suppressed"))
  emit_subagents = bool(specialists) and not suppressed

  try:
    if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
      created_files = list(plan["created_files"])
      manifest_edits = []
      _created_files, symlinks = _create_platform_pack(txn, plan, repo_root)
      created_files = _created_files
      if emit_subagents:
        baseline_content_path = plan["baseline_skill_path"] / "content.md"
        runtime_notes = render_subagent_spawn_runtime_notes(
          plan["baseline_skill_name"], specialists
        )
        _append_runtime_notes_to_content_md(baseline_content_path, runtime_notes)
        stub_paths = _stage_subagent_stubs(
          txn,
          orchestrator_skill_path=plan["baseline_skill_path"],
          orchestrator_name=plan["baseline_skill_name"],
          specialists=specialists,
        )
        created_files.extend(stub_paths)
      _run_validator(repo_root, plan)
      install_targets, install_notes = _perform_install(txn, plan)
    else:
      if plan["kind"] == SKILL_KIND_ADD_ON:
        body = _render_addon_body(plan, payload)
      else:
        body = _render_skill_body(plan, payload)

      _stage_file(txn, plan["skill_file"], body)
      if plan["kind"] != SKILL_KIND_ADD_ON:
        content_path = plan.get("content_file") or plan["skill_file"].with_name("content.md")
        content_body = _render_governed_content_body(plan, payload)
        if emit_subagents:
          runtime_notes = render_subagent_spawn_runtime_notes(plan["skill_name"], specialists)
          if not content_body.endswith("\n"):
            content_body += "\n"
          content_body = content_body + "\n" + runtime_notes + "\n"
        _stage_file(txn, content_path, content_body)

      manifest_edits = _apply_manifest_edits(txn, plan, repo_root)
      symlinks = _stage_sidecar_symlinks(txn, plan, repo_root)

      if emit_subagents:
        _stage_subagent_stubs(
          txn,
          orchestrator_skill_path=plan["skill_path"],
          orchestrator_name=plan["skill_name"],
          specialists=specialists,
        )

      created_files = list(txn.created_paths)

      _run_validator(repo_root, plan)
      install_targets, install_notes = _perform_install(txn, plan)
  except ScaffoldValidatorError:
    _rollback(txn)
    raise
  except Exception:
    _rollback(txn)
    raise

  notes = list(plan["notes"]) + install_notes
  if emit_subagents:
    if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
      stub_dir = plan["baseline_skill_path"]
    else:
      stub_dir = plan["skill_path"]
    notes.append(
      f"Subagent stubs emitted: {len(specialists)}. "
      f"Fill in the TODO placeholders in {stub_dir}/codex-agents/ and "
      f"{stub_dir}/opencode-agents/ before shipping."
    )

  return ScaffoldResult(
    kind=plan["kind"],
    skill_name=plan["skill_name"],
    skill_path=plan["skill_path"],
    created_files=created_files,
    manifest_edits=manifest_edits,
    symlinks=symlinks,
    install_targets=install_targets,
    notes=notes,
  )


__all__ = [
  "FAMILY_REGISTRY",
  "ScaffoldResult",
  "SKILL_KIND_ADD_ON",
  "SKILL_KIND_CODE_REVIEW_AREA",
  "SKILL_KIND_HORIZONTAL",
  "SKILL_KIND_PLATFORM_OVERRIDE_PILOTED",
  "SKILL_KIND_PLATFORM_PACK",
  "SUPPORTED_SKILL_KINDS",
  "_render_skill_body",
  "infer_plan_from_skill_file",
  "scaffold",
]
