"""New-skill scaffolder (SKILL-15).

Pure-Python scaffolder invoked by:

- the ``skill-bill new-skill`` CLI subcommand
- the ``new_skill_scaffold`` MCP tool
- the ``bill-skill-scaffold`` skill (via subprocess)

The entry point is :func:`scaffold`. It takes a validated payload and returns
a :class:`ScaffoldResult` describing every filesystem mutation. The scaffolder
is atomic: every failure mode triggers a full rollback and raises a named
exception — callers never see a partially materialized skill.

Layout kinds supported today:

- ``horizontal`` — ``skills/base/<name>/SKILL.md``
- ``platform-override-piloted`` — ``platform-packs/<slug>/<family>/<name>/SKILL.md``
  plus a manifest edit in ``platform-packs/<slug>/platform.yaml``
- ``platform-pack`` — ``platform-packs/<slug>/`` pack root with a generated
  baseline code-review skill and quality-check skill
- ``code-review-area`` — same placement as piloted, but also registers the new
  area under ``declared_code_review_areas`` and ``declared_files.areas`` in the
  manifest
- ``add-on`` — ``skills/<platform>/addons/<name>.md`` (flat; no sub-directory)

Pre-shell families (``quality-check``, ``feature-implement``, ``feature-verify``)
are placed under ``skills/<platform>/bill-<platform>-<capability>/`` and
annotated with an interim-location note.
"""

from __future__ import annotations

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
  ScaffoldTemplateContext,
  render_default_section,
  render_project_overrides,
)
from skill_bill.shell_content_contract import (
  APPROVED_CODE_REVIEW_AREAS,
  REQUIRED_CONTENT_SECTIONS,
  REQUIRED_QUALITY_CHECK_SECTIONS,
)


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
}

PLATFORM_PACK_PRESETS: dict[str, dict[str, Any]] = {
  "java": {
    "display_name": "Java",
    "routing_signals": {
      "strong": ["pom.xml", "build.gradle", "src/main/java"],
      "tie_breakers": [
        "Prefer Java when Maven metadata or Java source markers dominate generic JVM signals."
      ],
      "addon_signals": [],
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
      "addon_signals": list(routing_signals.get("addon_signals", [])),
    },
  }


def _resolve_platform_pack_defaults(payload: dict, platform: str) -> dict[str, Any]:
  preset = platform_pack_preset(platform)
  routing_signals = payload.get("routing_signals")

  strong_signals = _optional_explicit_string_list_from_mapping(routing_signals, "strong")
  tie_breakers = _optional_explicit_string_list_from_mapping(routing_signals, "tie_breakers")
  addon_signals = _optional_explicit_string_list_from_mapping(routing_signals, "addon_signals")

  if strong_signals is None and preset is not None:
    strong_signals = list(preset["routing_signals"]["strong"])
  if tie_breakers is None and preset is not None:
    tie_breakers = list(preset["routing_signals"]["tie_breakers"])
  if addon_signals is None and preset is not None:
    addon_signals = list(preset["routing_signals"]["addon_signals"])

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
      "addon_signals": addon_signals or [],
    },
    "preset_used": preset is not None and routing_signals is None,
  }


def _platform_pack_skeleton_mode(payload: dict) -> str:
  skeleton_mode = _optional_string(payload, "skeleton_mode") or PLATFORM_PACK_SKELETON_STARTER
  if skeleton_mode not in PLATFORM_PACK_SKELETON_MODES:
    raise InvalidScaffoldPayloadError(
      f"Scaffold payload field 'skeleton_mode' must be one of "
      f"{sorted(PLATFORM_PACK_SKELETON_MODES)} when provided."
    )
  return skeleton_mode


def _ordered_approved_code_review_areas() -> tuple[str, ...]:
  return tuple(sorted(APPROVED_CODE_REVIEW_AREAS))


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
  skill_path = repo_root / "skills" / "base" / name
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
  }


def _plan_platform_override_piloted(payload: dict, repo_root: Path) -> dict[str, Any]:
  platform = _require_string(payload, "platform")
  family = _require_string(payload, "family")
  name = _require_canonical_name(payload, default_name=f"bill-{platform}-{family}")

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
    if not (pack_root / "platform.yaml").is_file():
      raise MissingPlatformPackError(
        f"Platform pack '{platform}' does not exist at '{pack_root}'. "
        "Create a conforming platform.yaml before adding a skill into it."
      )
    skill_path = pack_root / family / name

  return {
    "kind": SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    "skill_name": name,
    "skill_path": skill_path,
    "skill_file": skill_path / "SKILL.md",
    "family": family,
    "platform": platform,
    "area": "",
    "is_shelled": is_shelled,
    "notes": notes,
  }


def _plan_platform_pack(payload: dict, repo_root: Path) -> dict[str, Any]:
  platform = _require_string(payload, "platform")
  defaults = _resolve_platform_pack_defaults(payload, platform)
  skeleton_mode = _platform_pack_skeleton_mode(payload)
  strong_signals = defaults["routing_signals"]["strong"]
  tie_breakers = defaults["routing_signals"]["tie_breakers"]
  addon_signals = defaults["routing_signals"]["addon_signals"]
  display_name = defaults["display_name"]
  description = _optional_string(payload, "description")
  governs_addons = _optional_bool(payload, "governs_addons", default=False)

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
    list(_ordered_approved_code_review_areas())
    if skeleton_mode == PLATFORM_PACK_SKELETON_FULL
    else []
  )
  specialist_skill_names = {
    area: f"bill-{platform}-code-review-{area}"
    for area in specialist_areas
  }
  specialist_skill_paths = {
    area: pack_root / "code-review" / specialist_skill_names[area]
    for area in specialist_areas
  }

  notes: list[str] = [
    "Quality-check scaffolded by default.",
    "Follow-on code-review-area scaffolds can extend the pack without manual manifest edits.",
  ]
  if defaults["preset_used"]:
    notes.insert(
      0,
      f"Applied built-in platform preset for '{platform}'. Override 'routing_signals' only when the defaults need adjustment.",
    )
  if skeleton_mode == PLATFORM_PACK_SKELETON_FULL:
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
      "addon_signals": addon_signals,
    },
    "governs_addons": governs_addons,
    "manifest_path": manifest_path,
    "baseline_skill_name": baseline_name,
    "baseline_skill_path": baseline_skill_path,
    "baseline_skill_file": baseline_skill_path / "SKILL.md",
    "quality_check_skill_name": quality_check_name,
    "quality_check_skill_path": quality_check_skill_path,
    "quality_check_skill_file": quality_check_skill_path / "SKILL.md",
    "specialist_areas": specialist_areas,
    "specialist_skill_names": specialist_skill_names,
    "specialist_skill_paths": specialist_skill_paths,
    "install_paths": [
      baseline_skill_path,
      quality_check_skill_path,
      *specialist_skill_paths.values(),
    ],
    "created_files": [
      manifest_path,
      baseline_skill_path / "SKILL.md",
      quality_check_skill_path / "SKILL.md",
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
  if not (pack_root / "platform.yaml").is_file():
    raise MissingPlatformPackError(
      f"Platform pack '{platform}' does not exist at '{pack_root}'. "
      "Create a conforming platform.yaml before adding a code-review area to it."
    )

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
    "notes": [],
  }


def _plan_add_on(payload: dict, repo_root: Path) -> dict[str, Any]:
  name = _require_string(payload, "name")
  platform = _require_string(payload, "platform")

  addons_root = repo_root / "skills" / platform / "addons"
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
  }


_PLANNERS: dict[str, Any] = {
  SKILL_KIND_HORIZONTAL: _plan_horizontal,
  SKILL_KIND_PLATFORM_OVERRIDE_PILOTED: _plan_platform_override_piloted,
  SKILL_KIND_PLATFORM_PACK: _plan_platform_pack,
  SKILL_KIND_CODE_REVIEW_AREA: _plan_code_review_area,
  SKILL_KIND_ADD_ON: _plan_add_on,
}


def _render_skill_body(plan: dict[str, Any], payload: dict) -> str:
  context = ScaffoldTemplateContext(
    skill_name=plan["skill_name"],
    family=plan["family"],
    platform=plan["platform"],
    area=plan["area"],
  )

  description = _optional_string(payload, "description") or (
    f"TODO: describe {plan['skill_name']}."
  )

  front_matter = (
    "---\n"
    f"name: {plan['skill_name']}\n"
    f"description: {description}\n"
    "---\n"
  )

  sections: list[str] = []
  # Skills that land under ``skills/`` (horizontal + pre-shell platform
  # overrides) are validated by ``validate_skill_file``, which requires the
  # ``## Project Overrides`` heading and a reference to
  # ``.agents/skill-overrides.md``. Platform-pack skills go through the
  # lighter ``validate_platform_pack_skill_file`` and intentionally skip it
  # to keep platform-pack skills lean.
  if not plan["is_shelled"] and plan["kind"] != SKILL_KIND_ADD_ON:
    sections.append(render_project_overrides(context))
  required_sections = (
    REQUIRED_QUALITY_CHECK_SECTIONS
    if plan["family"] == "quality-check"
    else REQUIRED_CONTENT_SECTIONS
  )
  sections.extend(render_default_section(heading, context) for heading in required_sections)
  body = "\n".join(sections)
  return f"{front_matter}\n{body}"


def _render_addon_body(plan: dict[str, Any], payload: dict) -> str:
  description = _optional_string(payload, "description") or (
    f"TODO: describe {plan['skill_name']}."
  )
  return (
    f"# {plan['skill_name']}\n"
    "\n"
    f"{description}\n"
    "\n"
    "TODO: author the add-on body.\n"
  )


def _append_supporting_file_links(body: str, file_names: list[str]) -> str:
  if not file_names:
    return body

  lines = [body.rstrip(), "", "## Additional Resources", ""]
  lines.extend(f"- [{file_name}]({file_name})" for file_name in file_names)
  return "\n".join(lines) + "\n"


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
  """Materialize a new platform pack root with baseline and quality-check."""
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
    addon_signals=plan["routing_signals"]["addon_signals"],
    declared_code_review_areas=list(plan["specialist_areas"]),
    baseline_content_path=baseline_skill_path.relative_to(pack_root).joinpath("SKILL.md").as_posix(),
    declared_area_files={
      area: path.relative_to(pack_root).joinpath("SKILL.md").as_posix()
      for area, path in plan["specialist_skill_paths"].items()
    },
    declared_quality_check_file=quality_check_skill_path.relative_to(pack_root).joinpath("SKILL.md").as_posix(),
    governs_addons=plan["governs_addons"],
  )
  _stage_file(txn, manifest_path, manifest_content)
  baseline_supporting_files = list(required_supporting_files_for_skill(baseline_name))
  quality_check_supporting_files = list(required_supporting_files_for_skill(quality_check_name))

  baseline_plan = {
    "kind": SKILL_KIND_PLATFORM_PACK,
    "skill_name": baseline_name,
    "skill_path": baseline_skill_path,
    "skill_file": baseline_skill_path / "SKILL.md",
    "family": "code-review",
    "platform": plan["platform"],
    "area": "",
    "is_shelled": True,
    "notes": [],
  }
  quality_check_plan = {
    "kind": SKILL_KIND_PLATFORM_PACK,
    "skill_name": quality_check_name,
    "skill_path": quality_check_skill_path,
    "skill_file": quality_check_skill_path / "SKILL.md",
    "family": "quality-check",
    "platform": plan["platform"],
    "area": "",
    "is_shelled": True,
    "notes": [],
  }
  specialist_plans = [
    {
      "kind": SKILL_KIND_PLATFORM_PACK,
      "skill_name": plan["specialist_skill_names"][area],
      "skill_path": plan["specialist_skill_paths"][area],
      "skill_file": plan["specialist_skill_paths"][area] / "SKILL.md",
      "family": "code-review",
      "platform": plan["platform"],
      "area": area,
      "is_shelled": True,
      "notes": [],
    }
    for area in plan["specialist_areas"]
  ]

  _stage_file(
    txn,
    baseline_plan["skill_file"],
    _append_supporting_file_links(
      _render_skill_body(baseline_plan, {"description": baseline_description}),
      baseline_supporting_files,
    ),
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
    _append_supporting_file_links(
      _render_skill_body(quality_check_plan, {"description": quality_check_description}),
      quality_check_supporting_files,
    ),
  )
  created_symlinks.extend(_stage_sidecar_symlinks_for_skill(
    txn,
    skill_name=quality_check_name,
    skill_path=quality_check_skill_path,
    repo_root=repo_root,
  ))

  for specialist_plan in specialist_plans:
    specialist_supporting_files = list(
      required_supporting_files_for_skill(specialist_plan["skill_name"])
    )
    _stage_file(
      txn,
      specialist_plan["skill_file"],
      _append_supporting_file_links(
        _render_skill_body(
          specialist_plan,
          {
            "description": (
              f"Use when reviewing {plan['display_name']} changes for "
              f"{specialist_plan['area']} risks."
            )
          },
        ),
        specialist_supporting_files,
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
      quality_check_plan["skill_file"],
      *(specialist_plan["skill_file"] for specialist_plan in specialist_plans),
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


def _run_validator(repo_root: Path) -> None:
  """Invoke ``scripts/validate_agent_configs.py`` and raise on failure."""
  script_path = repo_root / "scripts" / "validate_agent_configs.py"
  if not script_path.is_file():
    return

  result = subprocess.run(
    [sys.executable, str(script_path)],
    cwd=str(repo_root),
    capture_output=True,
    text=True,
  )
  if result.returncode != 0:
    raise ScaffoldValidatorError(
      f"Validator failed after scaffolding (exit {result.returncode}):\n"
      f"{result.stderr or result.stdout}"
    )


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

  planner = _PLANNERS[kind]
  plan = planner(payload, repo_root)

  if dry_run:
    manifest_edits_preview: list[Path] = []
    created_files_preview = list(plan.get("created_files", [plan["skill_file"]]))
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

  try:
    if plan["kind"] == SKILL_KIND_PLATFORM_PACK:
      created_files = list(plan["created_files"])
      manifest_edits = []
      _created_files, symlinks = _create_platform_pack(txn, plan, repo_root)
      created_files = _created_files
      _run_validator(repo_root)
      install_targets, install_notes = _perform_install(txn, plan)
    else:
      if plan["kind"] == SKILL_KIND_ADD_ON:
        body = _render_addon_body(plan, payload)
      else:
        body = _render_skill_body(plan, payload)

      _stage_file(txn, plan["skill_file"], body)

      manifest_edits = _apply_manifest_edits(txn, plan, repo_root)
      symlinks = _stage_sidecar_symlinks(txn, plan, repo_root)
      created_files = list(txn.created_paths)

      _run_validator(repo_root)
      install_targets, install_notes = _perform_install(txn, plan)
  except ScaffoldValidatorError:
    _rollback(txn)
    raise
  except Exception:
    _rollback(txn)
    raise

  return ScaffoldResult(
    kind=plan["kind"],
    skill_name=plan["skill_name"],
    skill_path=plan["skill_path"],
    created_files=created_files,
    manifest_edits=manifest_edits,
    symlinks=symlinks,
    install_targets=install_targets,
    notes=list(plan["notes"]) + install_notes,
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
  "scaffold",
]
