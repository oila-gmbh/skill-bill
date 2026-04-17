"""New-skill scaffolder (SKILL-15).

Pure-Python scaffolder invoked by:

- the ``skill-bill new-skill`` CLI subcommand
- the ``new_skill_scaffold`` MCP tool
- the ``bill-new-skill-all-agents`` skill (via subprocess)

The entry point is :func:`scaffold`. It takes a validated payload and returns
a :class:`ScaffoldResult` describing every filesystem mutation. The scaffolder
is atomic: every failure mode triggers a full rollback and raises a named
exception — callers never see a partially materialized skill.

Layout kinds supported today:

- ``horizontal`` — ``skills/base/<name>/SKILL.md``
- ``platform-override-piloted`` — ``platform-packs/<slug>/<family>/<name>/SKILL.md``
  plus a manifest edit in ``platform-packs/<slug>/platform.yaml``
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
)


SKILL_KIND_HORIZONTAL = "horizontal"
SKILL_KIND_PLATFORM_OVERRIDE_PILOTED = "platform-override-piloted"
SKILL_KIND_CODE_REVIEW_AREA = "code-review-area"
SKILL_KIND_ADD_ON = "add-on"

SUPPORTED_SKILL_KINDS: frozenset[str] = frozenset(
  {
    SKILL_KIND_HORIZONTAL,
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    SKILL_KIND_CODE_REVIEW_AREA,
    SKILL_KIND_ADD_ON,
  }
)

SHELLED_FAMILIES: frozenset[str] = frozenset({"code-review"})

# Family registry. The scaffolder consults this table to decide where to place
# a new skill and how to phrase its migration note.
#
# - ``code-review`` is piloted on the shell+content contract (SKILL-14). New
#   code-review platform overrides live inside the owning platform pack at
#   ``platform-packs/<slug>/code-review/<name>/``.
# - Pre-shell families (quality-check, feature-implement, feature-verify) are
#   still placed under ``skills/<platform>/bill-<platform>-<capability>/`` and
#   carry an interim-location note instructing authors to move them when those
#   families get piloted onto the shell.
FAMILY_REGISTRY: dict[str, dict[str, Any]] = {
  "code-review": {
    "layout_kind": "shelled",
    "base_path_template": "platform-packs/{platform}/code-review/{name}",
    "is_shelled": True,
  },
  "quality-check": {
    "layout_kind": "pre-shell",
    "base_path_template": "skills/{platform}/{name}",
    "is_shelled": False,
  },
  "feature-implement": {
    "layout_kind": "pre-shell",
    "base_path_template": "skills/{platform}/{name}",
    "is_shelled": False,
  },
  "feature-verify": {
    "layout_kind": "pre-shell",
    "base_path_template": "skills/{platform}/{name}",
    "is_shelled": False,
  },
}


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
  name = _require_string(payload, "name")
  platform = _require_string(payload, "platform")
  family = _require_string(payload, "family")

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


def _plan_code_review_area(payload: dict, repo_root: Path) -> dict[str, Any]:
  name = _require_string(payload, "name")
  platform = _require_string(payload, "platform")
  area = _require_string(payload, "area")

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
  sections.extend(render_default_section(heading, context) for heading in REQUIRED_CONTENT_SECTIONS)
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
  """Append new area entries to ``platform.yaml`` while preserving order."""
  if plan["kind"] != SKILL_KIND_CODE_REVIEW_AREA:
    return []

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


def _stage_sidecar_symlinks(txn: _ScaffoldTransaction, plan: dict[str, Any], repo_root: Path) -> list[Path]:
  """Wire sibling supporting-file symlinks per ``RUNTIME_SUPPORTING_FILES``."""
  from scripts.skill_repo_contracts import RUNTIME_SUPPORTING_FILES, supporting_file_targets

  skill_name = plan["skill_name"]
  required = RUNTIME_SUPPORTING_FILES.get(skill_name)
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
    link_path = plan["skill_path"] / file_name
    if link_path.exists() or link_path.is_symlink():
      continue
    link_path.symlink_to(target)
    txn.created_symlinks.append(link_path)
    created.append(link_path)
  return created


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

  targets = install_skill(plan["skill_path"], agents, transaction=install_txn)
  txn.install_targets.extend(targets)
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
    result = ScaffoldResult(
      kind=plan["kind"],
      skill_name=plan["skill_name"],
      skill_path=plan["skill_path"],
      created_files=[plan["skill_file"]],
      manifest_edits=(
        [repo_root / "platform-packs" / plan["platform"] / "platform.yaml"]
        if plan["kind"] == SKILL_KIND_CODE_REVIEW_AREA
        else []
      ),
      symlinks=[],
      install_targets=[],
      notes=list(plan["notes"])
      + ["Dry run — no filesystem changes applied."],
    )
    return result

  txn = _ScaffoldTransaction()

  try:
    if plan["kind"] == SKILL_KIND_ADD_ON:
      body = _render_addon_body(plan, payload)
    else:
      body = _render_skill_body(plan, payload)

    _stage_file(txn, plan["skill_file"], body)

    manifest_edits = _apply_manifest_edits(txn, plan, repo_root)
    symlinks = _stage_sidecar_symlinks(txn, plan, repo_root)

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
    created_files=list(txn.created_paths),
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
  "SUPPORTED_SKILL_KINDS",
  "scaffold",
]
