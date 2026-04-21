from __future__ import annotations

import argparse
from dataclasses import dataclass
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

from skill_bill import __version__
from skill_bill.config import (
  load_telemetry_settings,
  set_telemetry_enabled,
  set_telemetry_level,
  telemetry_is_enabled,
)
from skill_bill.constants import (
  DB_ENVIRONMENT_KEY,
  DEFAULT_DB_PATH,
  FINDING_OUTCOME_TYPES,
  LEARNING_SCOPE_PRECEDENCE,
  LEARNING_SCOPES,
  LEARNING_STATUSES,
  TELEMETRY_LEVELS,
)
from skill_bill.db import open_db, resolve_db_path
from skill_bill.feature_implement import (
  build_workflow_payload,
  build_workflow_resume_payload,
  build_workflow_summary_payload,
  continue_workflow,
  fetch_latest_workflow,
  list_workflows,
)
from skill_bill.feature_verify import (
  build_workflow_payload as build_feature_verify_workflow_payload,
  build_workflow_resume_payload as build_feature_verify_workflow_resume_payload,
  build_workflow_summary_payload as build_feature_verify_workflow_summary_payload,
  continue_workflow as continue_feature_verify_workflow,
  fetch_latest_workflow as fetch_latest_feature_verify_workflow,
  list_workflows as list_feature_verify_workflows,
)
from skill_bill.learnings import (
  add_learning,
  delete_learning,
  edit_learning,
  get_learning,
  learning_payload,
  learning_summary_payload,
  list_learnings,
  resolve_learnings,
  save_session_learnings,
  scope_counts,
  set_learning_status,
)
from skill_bill.output import (
  emit,
  print_learnings,
  print_numbered_findings,
  print_resolved_learnings,
  print_triage_result,
  summarize_applied_learnings,
)
from skill_bill.review import (
  fetch_numbered_findings,
  parse_review,
  read_input,
  save_imported_review,
)
from skill_bill.stats import stats_payload
from skill_bill.sync import (
  sync_result_payload,
  sync_telemetry,
  telemetry_status_payload,
  telemetry_sync_target,
)
from skill_bill.triage import (
  parse_triage_decisions,
  record_feedback,
)


@dataclass(frozen=True)
class GovernedSkillTarget:
  skill_name: str
  package: str
  platform: str
  family: str
  area: str
  skill_file: Path
  content_file: Path


@dataclass(frozen=True)
class ScaffoldCommandOutcome:
  exit_code: int
  result: object | None
  payload: dict[str, object] | None


def import_review_command(args: argparse.Namespace) -> int:
  text, source_path = read_input(args.input)
  review = parse_review(text)
  with open_db(args.db) as (connection, db_path):
    save_imported_review(connection, review, source_path=source_path)
    if len(review.findings) == 0:
      from skill_bill.stats import update_review_finished_telemetry_state
      update_review_finished_telemetry_state(
        connection,
        review_run_id=review.review_run_id,
      )
  emit(
    {
      "db_path": str(db_path),
      "review_run_id": review.review_run_id,
      "review_session_id": review.review_session_id,
      "finding_count": len(review.findings),
      "routed_skill": review.routed_skill,
      "detected_scope": review.detected_scope,
      "detected_stack": review.detected_stack,
      "execution_mode": review.execution_mode,
    },
    args.format,
  )
  return 0


def record_feedback_command(args: argparse.Namespace) -> int:
  with open_db(args.db) as (connection, db_path):
    record_feedback(
      connection,
      review_run_id=args.run_id,
      finding_ids=args.finding,
      event_type=args.event,
      note=args.note,
    )
  emit(
    {
      "db_path": str(db_path),
      "review_run_id": args.run_id,
      "outcome_type": args.event,
      "recorded_findings": len(args.finding),
    },
    args.format,
  )
  return 0


def triage_command(args: argparse.Namespace) -> int:
  with open_db(args.db) as (connection, db_path):
    numbered_findings = fetch_numbered_findings(connection, args.run_id)
    if args.list or not args.decision:
      if args.format == "json":
        emit(
          {
            "db_path": str(db_path),
            "review_run_id": args.run_id,
            "findings": numbered_findings,
          },
          args.format,
        )
      else:
        print_numbered_findings(args.run_id, numbered_findings)
      return 0

    decisions = parse_triage_decisions(args.decision, numbered_findings)
    for decision in decisions:
      record_feedback(
        connection,
        review_run_id=args.run_id,
        finding_ids=[decision.finding_id],
        event_type=decision.outcome_type,
        note=decision.note,
      )
  if args.format == "json":
    emit(
      {
        "db_path": str(db_path),
        "review_run_id": args.run_id,
        "recorded": [
          {
            "number": decision.number,
            "finding_id": decision.finding_id,
            "outcome_type": decision.outcome_type,
            "note": decision.note,
          }
          for decision in decisions
        ],
      },
      args.format,
    )
  else:
    print_triage_result(args.run_id, decisions)
  return 0


def stats_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    payload = stats_payload(connection, args.run_id)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_add_command(args: argparse.Namespace) -> int:
  with open_db(args.db) as (connection, db_path):
    learning_id = add_learning(
      connection,
      scope=args.scope,
      scope_key=args.scope_key,
      title=args.title,
      rule_text=args.rule,
      rationale=args.reason,
      source_review_run_id=args.from_run,
      source_finding_id=args.from_finding,
    )
    payload = learning_payload(get_learning(connection, learning_id))
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_list_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    payload_entries = [learning_payload(row) for row in list_learnings(connection, status=args.status)]
  if args.format == "json":
    emit({"db_path": str(db_path), "learnings": payload_entries}, args.format)
  else:
    print_learnings(payload_entries)
  return 0


def learnings_show_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    payload = learning_payload(get_learning(connection, args.id))
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_resolve_command(args: argparse.Namespace) -> int:
  with open_db(args.db) as (connection, db_path):
    with connection:
      repo_scope_key, skill_name, rows = resolve_learnings(
        connection,
        repo_scope_key=args.repo,
        skill_name=args.skill,
      )
      payload_entries = [learning_payload(row) for row in rows]
      if args.review_session_id:
        learnings_cache = {
          "skill_name": skill_name,
          "applied_learning_count": len(payload_entries),
          "applied_learning_references": [entry["reference"] for entry in payload_entries],
          "applied_learnings": summarize_applied_learnings(payload_entries),
          "scope_counts": scope_counts(payload_entries),
          "learnings": [learning_summary_payload(entry) for entry in payload_entries],
        }
        save_session_learnings(
          connection,
          review_session_id=args.review_session_id,
          learnings_json=json.dumps(learnings_cache, sort_keys=True),
        )
  payload = {
    "db_path": str(db_path),
    "repo_scope_key": repo_scope_key,
    "skill_name": skill_name,
    "scope_precedence": list(LEARNING_SCOPE_PRECEDENCE),
    "applied_learnings": summarize_applied_learnings(payload_entries),
    "learnings": payload_entries,
  }
  if args.review_session_id:
    payload["review_session_id"] = args.review_session_id
  if args.format == "json":
    emit(payload, args.format)
  else:
    print_resolved_learnings(
      repo_scope_key=repo_scope_key,
      skill_name=skill_name,
      entries=payload_entries,
    )
  return 0


def learnings_edit_command(args: argparse.Namespace) -> int:
  if all(
    value is None
    for value in (args.scope, args.scope_key, args.title, args.rule, args.reason)
  ):
    raise ValueError("Learning edit requires at least one field to update.")

  with open_db(args.db) as (connection, db_path):
    payload = learning_payload(
      edit_learning(
        connection,
        learning_id=args.id,
        scope=args.scope,
        scope_key=args.scope_key,
        title=args.title,
        rule_text=args.rule,
        rationale=args.reason,
      )
    )
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_status_command(args: argparse.Namespace) -> int:
  with open_db(args.db) as (connection, db_path):
    payload = learning_payload(
      set_learning_status(connection, learning_id=args.id, status=args.status_value)
    )
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_delete_command(args: argparse.Namespace) -> int:
  with open_db(args.db) as (connection, db_path):
    delete_learning(connection, args.id)
  emit(
    {
      "db_path": str(db_path),
      "deleted_learning_id": args.id,
    },
    args.format,
  )
  return 0


def telemetry_status_command(args: argparse.Namespace) -> int:
  payload = telemetry_status_payload(resolve_db_path(args.db))
  emit(payload, args.format)
  return 0


def telemetry_sync_command(args: argparse.Namespace) -> int:
  result = sync_telemetry(resolve_db_path(args.db))
  emit(sync_result_payload(result), args.format)
  return 1 if result.status == "failed" else 0


def telemetry_toggle_command(args: argparse.Namespace) -> int:
  level = getattr(args, "level_value", None)
  if level is None:
    level = "anonymous" if args.enabled_value else "off"
  settings, cleared_events = set_telemetry_level(
    level,
    db_path=resolve_db_path(args.db),
  )
  payload = {
    "config_path": str(settings.config_path),
    "telemetry_enabled": settings.enabled,
    "telemetry_level": settings.level,
    "sync_target": telemetry_sync_target(settings),
    "remote_configured": bool(settings.proxy_url),
    "proxy_configured": bool(settings.custom_proxy_url),
    "proxy_url": settings.proxy_url,
    "custom_proxy_url": settings.custom_proxy_url,
    "install_id": settings.install_id,
    "cleared_events": cleared_events,
  }
  emit(payload, args.format)
  return 0


def telemetry_set_level_command(args: argparse.Namespace) -> int:
  settings, cleared_events = set_telemetry_level(
    args.level,
    db_path=resolve_db_path(args.db),
  )
  payload = {
    "config_path": str(settings.config_path),
    "telemetry_enabled": settings.enabled,
    "telemetry_level": settings.level,
    "sync_target": telemetry_sync_target(settings),
    "remote_configured": bool(settings.proxy_url),
    "proxy_configured": bool(settings.custom_proxy_url),
    "proxy_url": settings.proxy_url,
    "custom_proxy_url": settings.custom_proxy_url,
    "install_id": settings.install_id,
    "cleared_events": cleared_events,
  }
  emit(payload, args.format)
  return 0


def version_command(args: argparse.Namespace) -> int:
  emit({"version": __version__}, args.format)
  return 0


def _execute_scaffold_command(payload: dict, args: argparse.Namespace) -> ScaffoldCommandOutcome:
  from skill_bill import scaffold as _scaffold
  from skill_bill import scaffold_domain as _scaffold_domain
  from skill_bill.config import load_telemetry_settings
  from skill_bill.scaffold_exceptions import (
    InvalidScaffoldPayloadError,
    MissingPlatformPackError,
    MissingSupportingFileTargetError,
    ScaffoldError,
    ScaffoldPayloadVersionMismatchError,
    ScaffoldRollbackError,
    ScaffoldValidatorError,
    SkillAlreadyExistsError,
    UnknownPreShellFamilyError,
    UnknownSkillKindError,
  )

  # Stable exit codes per concrete ScaffoldError subclass so callers (CI,
  # install.sh, MCP wrappers) can branch on typed failure modes instead of
  # scraping stderr. Unknown ScaffoldError subclasses fall back to 1.
  exit_code_map: dict[type, int] = {
    ScaffoldPayloadVersionMismatchError: 3,
    InvalidScaffoldPayloadError: 2,
    UnknownSkillKindError: 2,
    UnknownPreShellFamilyError: 2,
    MissingPlatformPackError: 2,
    SkillAlreadyExistsError: 4,
    ScaffoldValidatorError: 5,
    MissingSupportingFileTargetError: 5,
    ScaffoldRollbackError: 6,
  }

  session_id = _scaffold_domain.generate_new_skill_session_id()
  canonical_skill_name = _scaffold_identity(payload)

  try:
    level = load_telemetry_settings().level
  except ValueError:
    level = "anonymous"

  started_payload = _scaffold_domain.build_started_payload_from_fields(
    session_id=session_id,
    kind=str(payload.get("kind", "")),
    skill_name=canonical_skill_name,
    platform=str(payload.get("platform", "")),
    family=str(payload.get("family", "")),
    area=str(payload.get("area", "")),
    level=level,
  )

  try:
    result = _scaffold.scaffold(payload, dry_run=args.dry_run)
  except ScaffoldError as error:
    # Print the scaffolder's message to stderr and return a typed exit code.
    # We intentionally do NOT wrap this in ValueError — that would collapse
    # every failure mode into exit code 1 and throw away the typed contract
    # the exit_code_map exposes to callers.
    print(str(error), file=sys.stderr)
    return ScaffoldCommandOutcome(
      exit_code=exit_code_map.get(type(error), 1),
      result=None,
      payload=None,
    )
  # Non-ScaffoldError exceptions (real bugs) propagate so the user gets a
  # traceback instead of a silently-collapsed "user error".

  finished_payload = _scaffold_domain.build_finished_payload_from_fields(
    session_id=session_id,
    kind=result.kind,
    skill_name=result.skill_name,
    platform=str(payload.get("platform", "")),
    family=str(payload.get("family", "")),
    area=str(payload.get("area", "")),
    result="dry-run" if args.dry_run else "success",
    duration_seconds=0,
    level=level,
  )

  return ScaffoldCommandOutcome(
    exit_code=0,
    result=result,
    payload={
      "session_id": session_id,
      "kind": result.kind,
      "skill_name": result.skill_name,
      "skill_path": str(result.skill_path),
      "created_files": [str(path) for path in result.created_files],
      "manifest_edits": [str(path) for path in result.manifest_edits],
      "symlinks": [str(path) for path in result.symlinks],
      "install_targets": [str(path) for path in result.install_targets],
      "notes": result.notes,
      "dry_run": args.dry_run,
      "started_payload": started_payload,
      "finished_payload": finished_payload,
    },
  )


def _run_scaffold_command(payload: dict, args: argparse.Namespace) -> int:
  outcome = _execute_scaffold_command(payload, args)
  if outcome.payload is not None:
    emit(
      outcome.payload,
      args.format,
    )
  return outcome.exit_code


def new_skill_command(args: argparse.Namespace) -> int:
  if args.payload and args.interactive:
    raise ValueError("--payload and --interactive are mutually exclusive.")

  if args.payload:
    payload_path = args.payload
    if payload_path == "-":
      payload_text = sys.stdin.read()
    else:
      with open(payload_path, encoding="utf-8") as stream:
        payload_text = stream.read()
    try:
      payload = json.loads(payload_text)
    except json.JSONDecodeError as error:
      raise ValueError(f"Invalid JSON payload: {error}") from error
  elif args.interactive:
    payload = _prompt_new_skill_interactively()
  else:
    raise ValueError("Either --payload or --interactive is required.")

  return _run_scaffold_command(payload, args)


def _prompt_nonempty(prompt: str) -> str:
  while True:
    value = input(prompt).strip()
    if value:
      return value
    print("A value is required.")


def _prompt_yes_no(prompt: str, *, default: bool = False) -> bool:
  suffix = " [Y/n]: " if default else " [y/N]: "
  while True:
    raw = input(f"{prompt}{suffix}").strip().lower()
    if not raw:
      return default
    if raw in {"y", "yes", "1", "true"}:
      return True
    if raw in {"n", "no", "0", "false"}:
      return False
    print("Enter yes or no.")


def _prompt_choice(prompt: str, choices: dict[str, str]) -> str:
  while True:
    raw = _prompt_nonempty(prompt).strip().lower()
    if raw in choices:
      return choices[raw]
    print(f"Choose one of: {', '.join(choices)}")


def _prompt_choice_with_default(
  prompt: str,
  choices: dict[str, str],
  *,
  default: str,
) -> str:
  if default not in choices:
    raise ValueError(f"Unknown default choice '{default}'.")
  while True:
    raw = input(prompt).strip().lower()
    if not raw:
      return choices[default]
    if raw in choices:
      return choices[raw]
    print(f"Choose one of: {', '.join(choices)}")


def _prompt_code_review_area_subset() -> list[str]:
  from skill_bill.scaffold import APPROVED_CODE_REVIEW_AREAS

  ordered_areas = sorted(APPROVED_CODE_REVIEW_AREAS)
  print("Available approved areas:")
  for index, area in enumerate(ordered_areas, start=1):
    print(f"{index}. {area}")

  index_map = {str(index): area for index, area in enumerate(ordered_areas, start=1)}
  area_map = {area: area for area in ordered_areas}
  while True:
    raw = _prompt_nonempty(
      "Choose areas (comma-separated names or numbers): "
    ).strip().lower()
    resolved: set[str] = set()
    invalid: list[str] = []
    for item in (part.strip() for part in raw.split(",")):
      if not item:
        continue
      if item in {"all", "*"}:
        return ordered_areas
      if item in {"none", "0"}:
        return []
      area = index_map.get(item) or area_map.get(item)
      if area is None:
        invalid.append(item)
        continue
      resolved.add(area)
    if invalid:
      print(f"Unknown areas: {', '.join(invalid)}")
      continue
    if not resolved:
      print("Choose at least one area, or enter 'none'.")
      continue
    return [area for area in ordered_areas if area in resolved]


def _platform_pack_exists(platform: str, *, repo_root: Path | None = None) -> bool:
  root = repo_root or Path.cwd()
  return (root / "platform-packs" / platform / "platform.yaml").exists()


def _discover_platform_pack_slugs(repo_root: Path) -> set[str]:
  from skill_bill.shell_content_contract import discover_platform_pack_manifests

  return {
    pack.slug
    for pack in discover_platform_pack_manifests(repo_root / "platform-packs")
  }


def _discover_governed_skill_targets(repo_root: Path) -> dict[str, GovernedSkillTarget]:
  from skill_bill.shell_content_contract import discover_platform_pack_manifests

  discovered: dict[str, GovernedSkillTarget] = {}
  for pack in discover_platform_pack_manifests(repo_root / "platform-packs"):
    baseline_path = pack.declared_files.get("baseline")
    if isinstance(baseline_path, Path):
      discovered[baseline_path.parent.name] = GovernedSkillTarget(
        skill_name=baseline_path.parent.name,
        package=pack.slug,
        platform=pack.slug,
        family="code-review",
        area="",
        skill_file=baseline_path,
        content_file=baseline_path.with_name("content.md"),
      )
    area_paths = pack.declared_files.get("areas", {})
    if isinstance(area_paths, dict):
      for area, skill_file in area_paths.items():
        discovered[skill_file.parent.name] = GovernedSkillTarget(
          skill_name=skill_file.parent.name,
          package=pack.slug,
          platform=pack.slug,
          family="code-review",
          area=area,
          skill_file=skill_file,
          content_file=skill_file.with_name("content.md"),
        )
    if pack.declared_quality_check_file is not None:
      quality_check_skill = pack.declared_quality_check_file
      discovered[quality_check_skill.parent.name] = GovernedSkillTarget(
        skill_name=quality_check_skill.parent.name,
        package=pack.slug,
        platform=pack.slug,
        family="quality-check",
        area="",
        skill_file=quality_check_skill,
        content_file=quality_check_skill.with_name("content.md"),
      )
  return discovered


def _infer_skill_family(skill_name: str) -> str:
  slug = skill_name.removeprefix("bill-")
  if "-code-review-" in skill_name or slug.endswith("code-review"):
    return "code-review"
  if slug.endswith("quality-check"):
    return "quality-check"
  if slug.endswith("feature-implement"):
    return "feature-implement"
  if slug.endswith("feature-verify"):
    return "feature-verify"
  return slug


def _infer_skill_area(skill_name: str, family: str) -> str:
  if family != "code-review" or "-code-review-" not in skill_name:
    return ""
  return skill_name.split("-code-review-", 1)[1]


def _discover_content_managed_skill_targets(repo_root: Path) -> dict[str, GovernedSkillTarget]:
  discovered = _discover_governed_skill_targets(repo_root)
  skills_root = repo_root / "skills"
  if not skills_root.is_dir():
    return discovered

  for skill_file in sorted(skills_root.rglob("SKILL.md")):
    content_file = skill_file.with_name("content.md")
    if not content_file.is_file():
      continue
    skill_name = skill_file.parent.name
    if skill_name in discovered:
      continue
    relative = skill_file.relative_to(repo_root)
    package = "base"
    platform = ""
    if len(relative.parts) >= 3 and relative.parts[0] == "skills":
      if not relative.parts[1].startswith("bill-"):
        package = relative.parts[1]
        platform = relative.parts[1]
    family = _infer_skill_family(skill_name)
    discovered[skill_name] = GovernedSkillTarget(
      skill_name=skill_name,
      package=package,
      platform=platform,
      family=family,
      area=_infer_skill_area(skill_name, family),
      skill_file=skill_file,
      content_file=content_file,
    )
  return discovered


def _content_visible_lines(text: str) -> list[str]:
  return [line.strip() for line in text.splitlines() if line.strip()]


def _has_unresolved_placeholder(text: str) -> bool:
  import re

  return re.search(r"(?m)^\s*(?:[-*]\s*)?(?:TODO|FIXME)\b", text) is not None


def _parse_content_sections(text: str) -> tuple[str, list[tuple[str, str]]]:
  prefix_lines: list[str] = []
  sections: list[tuple[str, str]] = []
  current_heading: str | None = None
  current_lines: list[str] = []
  in_fence = False

  for line in text.splitlines(keepends=True):
    stripped = line.rstrip("\n")
    if stripped.lstrip().startswith(("```", "~~~")):
      in_fence = not in_fence
      if current_heading is None:
        prefix_lines.append(line)
      else:
        current_lines.append(line)
      continue
    if not in_fence and stripped.startswith("## "):
      if current_heading is None:
        current_heading = stripped
        current_lines = []
      else:
        sections.append((current_heading, "".join(current_lines)))
        current_heading = stripped
        current_lines = []
      continue
    if current_heading is None:
      prefix_lines.append(line)
    else:
      current_lines.append(line)

  if current_heading is not None:
    sections.append((current_heading, "".join(current_lines)))
  return ("".join(prefix_lines), sections)


def _render_content_sections(prefix: str, sections: list[tuple[str, str]]) -> str:
  prefix_text = prefix.rstrip()
  blocks: list[str] = []
  if prefix_text:
    blocks.append(prefix_text)
  for heading, body in sections:
    section_body = body.rstrip()
    if section_body:
      blocks.append(f"{heading}\n\n{section_body}")
    else:
      blocks.append(f"{heading}\n")
  return "\n\n".join(blocks).rstrip() + "\n"


def _section_completion_status(body: str) -> str:
  if not body.strip():
    return "empty"
  if _has_unresolved_placeholder(body):
    return "draft"
  return "complete"


def _content_completion_status(text: str) -> str:
  visible_lines = _content_visible_lines(text)
  if len(visible_lines) <= 1:
    return "draft"
  if _has_unresolved_placeholder(text):
    return "draft"
  _prefix, sections = _parse_content_sections(text)
  if sections and any(_section_completion_status(body) != "complete" for _heading, body in sections):
    return "draft"
  return "complete"


def _normalize_section_heading(section_name: str) -> str:
  heading = section_name.strip()
  if not heading:
    raise ValueError("Section name must be non-empty.")
  if heading.startswith("## "):
    return heading
  return f"## {heading}"


def _section_heading_label(section_name: str) -> str:
  return _normalize_section_heading(section_name).removeprefix("## ").strip()


def _preview_text(text: str, *, limit: int = 200) -> str:
  collapsed = " ".join(line.strip() for line in text.splitlines() if line.strip())
  if len(collapsed) <= limit:
    return collapsed
  return collapsed[: limit - 1].rstrip() + "…"


def _section_payloads(text: str) -> list[dict[str, object]]:
  _prefix, sections = _parse_content_sections(text)
  payload: list[dict[str, object]] = []
  for heading, body in sections:
    payload.append(
      {
        "heading": heading.removeprefix("## ").strip(),
        "status": _section_completion_status(body),
        "line_count": len([line for line in body.splitlines() if line.strip()]),
        "preview": _preview_text(body),
      }
    )
  return payload


def _replace_section_body(text: str, section_name: str, new_body: str) -> str:
  prefix, sections = _parse_content_sections(text)
  normalized = _normalize_section_heading(section_name)
  replacement = new_body.rstrip()
  updated_sections: list[tuple[str, str]] = []
  matched = False
  for heading, body in sections:
    if heading == normalized:
      updated_sections.append((heading, replacement))
      matched = True
      continue
    updated_sections.append((heading, body))
  if not matched:
    available = ", ".join(heading.removeprefix("## ").strip() for heading, _body in sections)
    raise ValueError(
      f"Unknown content section '{_section_heading_label(section_name)}'. "
      f"Available sections: {available}."
    )
  return _render_content_sections(prefix, updated_sections)


def _full_content_title(target: GovernedSkillTarget) -> str:
  existing = target.content_file.read_text(encoding="utf-8")
  for line in existing.splitlines():
    stripped = line.strip()
    if stripped.startswith("# "):
      return stripped
    if stripped:
      break
  return "# Content"


def _coerce_full_content_text(target: GovernedSkillTarget, body_text: str) -> str:
  stripped = body_text.strip()
  if not stripped:
    raise ValueError("Filled content must be non-empty.")
  if stripped.startswith("# "):
    rendered = stripped
  else:
    rendered = f"{_full_content_title(target)}\n\n{stripped}"
  return rendered.rstrip() + "\n"


def _resolve_content_managed_target(repo_root: Path, skill_name: str) -> GovernedSkillTarget:
  editable_targets = _discover_content_managed_skill_targets(repo_root)
  target = editable_targets.get(skill_name)
  if target is None:
    raise ValueError(
      f"Skill '{skill_name}' is not a content-managed skill with a sibling content.md file."
    )
  return target


def _build_recommended_commands(
  repo_root: Path,
  target: GovernedSkillTarget,
  *,
  completion_status: str,
  generation_drift: bool,
  issues: list[str] | None = None,
) -> list[str]:
  commands: list[str] = [
    f"skill-bill show {target.skill_name} --repo-root {shlex.quote(str(repo_root))}",
  ]
  if completion_status != "complete":
    commands.append(
      f"skill-bill edit {target.skill_name} --repo-root {shlex.quote(str(repo_root))}"
    )
  if generation_drift:
    commands.append(
      f"skill-bill render --repo-root {shlex.quote(str(repo_root))} --skill-name {target.skill_name}"
    )
  if issues:
    commands.append(
      f"skill-bill validate --repo-root {shlex.quote(str(repo_root))} --skill-name {target.skill_name}"
    )

  deduped: list[str] = []
  seen: set[str] = set()
  for command in commands:
    if command in seen:
      continue
    deduped.append(command)
    seen.add(command)
  return deduped


def _skill_status_payload(
  repo_root: Path,
  target: GovernedSkillTarget,
  *,
  content_mode: str = "preview",
  issues: list[str] | None = None,
) -> dict[str, object]:
  from skill_bill.upgrade import render_upgrade_targets

  content_text = target.content_file.read_text(encoding="utf-8")
  rendered_targets = render_upgrade_targets(
    repo_root,
    skill_names=[target.skill_name],
  )
  generation_drift = (
    rendered_targets.get(target.skill_file) is not None
    and target.skill_file.read_text(encoding="utf-8")
    != rendered_targets[target.skill_file]
  )
  completion_status = _content_completion_status(content_text)
  payload: dict[str, object] = {
    "skill_name": target.skill_name,
    "package": target.package,
    "platform": target.platform,
    "family": target.family,
    "area": target.area,
    "skill_file": str(target.skill_file),
    "content_file": str(target.content_file),
    "completion_status": completion_status,
    "generation_drift": generation_drift,
    "section_count": len(_parse_content_sections(content_text)[1]),
    "sections": _section_payloads(content_text),
    "recommended_commands": _build_recommended_commands(
      repo_root,
      target,
      completion_status=completion_status,
      generation_drift=generation_drift,
      issues=issues or [],
    ),
  }
  if content_mode == "preview":
    payload["content_preview"] = _preview_text(content_text, limit=400)
  elif content_mode == "full":
    payload["content"] = content_text
  if issues is not None:
    payload["issues"] = issues
  return payload


def _render_multiline_section_input(prompt: str, *, terminator: str = ".done") -> str:
  print(prompt)
  lines: list[str] = []
  while True:
    line = input()
    if line == terminator:
      break
    lines.append(line)
  return "\n".join(lines).rstrip()


def _prompt_edit_action() -> str:
  return _prompt_choice(
    "Action [r=replace, a=append, c=clear, s=skip, d=done]: ",
    {
      "r": "replace",
      "a": "append",
      "c": "clear",
      "s": "skip",
      "d": "done",
    },
  )


def _prompt_single_section_edit(heading: str, body: str) -> tuple[str, dict[str, str]]:
  status = _section_completion_status(body)
  print(f"{heading} [{status}]")
  print(body.rstrip() or "(empty)")
  action = _prompt_edit_action()
  if action in {"done", "skip"}:
    updated = body
  elif action == "clear":
    updated = ""
  else:
    prompt = (
      f"Enter text for {heading}. Finish with '.done'."
      if action == "replace"
      else f"Append text for {heading}. Finish with '.done'."
    )
    entered = _render_multiline_section_input(prompt)
    if action == "replace":
      updated = entered
    else:
      existing = body.rstrip()
      updated = entered if not existing else f"{existing}\n\n{entered}".rstrip()
  return updated, {
    "heading": heading.removeprefix("## ").strip(),
    "status": _section_completion_status(updated),
  }


def _edit_content_guided(content_file: Path) -> tuple[str, list[dict[str, str]]]:
  text = content_file.read_text(encoding="utf-8")
  prefix, sections = _parse_content_sections(text)
  if not sections:
    replacement = _render_multiline_section_input(
      "content.md has no editable H2 sections. Enter the full replacement body and finish with '.done'."
    )
    rendered = replacement if replacement.endswith("\n") else f"{replacement}\n"
    return rendered, []

  updated_sections: list[tuple[str, str]] = []
  for index, (heading, body) in enumerate(sections):
    status = _section_completion_status(body)
    print(f"{heading} [{status}]")
    print(body.rstrip() or "(empty)")
    action = _prompt_edit_action()
    if action == "done":
      updated_sections.extend(sections[index:])
      break
    if action == "skip":
      updated_sections.append((heading, body))
      continue
    if action == "clear":
      updated_sections.append((heading, ""))
      continue
    prompt = (
      f"Enter text for {heading}. Finish with '.done'."
      if action == "replace"
      else f"Append text for {heading}. Finish with '.done'."
    )
    entered = _render_multiline_section_input(prompt)
    if action == "replace":
      updated_sections.append((heading, entered))
    else:
      existing = body.rstrip()
      combined = entered if not existing else f"{existing}\n\n{entered}".rstrip()
      updated_sections.append((heading, combined))

  rendered = _render_content_sections(prefix, updated_sections)
  section_payload = [
    {
      "heading": heading.removeprefix("## ").strip(),
      "status": _section_completion_status(body),
    }
    for heading, body in updated_sections
  ]
  return rendered, section_payload


def _edit_single_section_guided(content_file: Path, section_name: str) -> tuple[str, list[dict[str, str]]]:
  text = content_file.read_text(encoding="utf-8")
  prefix, sections = _parse_content_sections(text)
  normalized = _normalize_section_heading(section_name)
  updated_sections: list[tuple[str, str]] = []
  matched = False
  payload: list[dict[str, str]] = []
  for heading, body in sections:
    if heading != normalized:
      updated_sections.append((heading, body))
      continue
    matched = True
    updated_body, section_payload = _prompt_single_section_edit(heading, body)
    updated_sections.append((heading, updated_body))
    payload.append(section_payload)
  if not matched:
    available = ", ".join(heading.removeprefix("## ").strip() for heading, _body in sections)
    raise ValueError(
      f"Unknown content section '{_section_heading_label(section_name)}'. "
      f"Available sections: {available}."
    )
  return _render_content_sections(prefix, updated_sections), payload


def _load_validator_module(repo_root: Path):
  script_path = repo_root / "scripts" / "validate_agent_configs.py"
  if not script_path.is_file():
    raise ValueError(f"Validator script is missing at '{script_path}'.")
  scripts_dir = str(script_path.parent)
  repo_root_str = str(repo_root)
  sys.path.insert(0, scripts_dir)
  sys.path.insert(0, repo_root_str)
  try:
    spec = importlib.util.spec_from_file_location(
      "_skill_bill_runtime_validator",
      script_path,
    )
    if spec is None or spec.loader is None:
      raise ValueError(f"Could not load validator module from '{script_path}'.")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
  finally:
    for entry in (repo_root_str, scripts_dir):
      if entry in sys.path:
        sys.path.remove(entry)


def _validate_selected_skills(repo_root: Path, skill_names: list[str]) -> list[str]:
  from skill_bill.shell_content_contract import ShellContentContractError, load_platform_pack

  validator = _load_validator_module(repo_root)
  issues: list[str] = []
  skill_files = validator.discover_skill_files(repo_root, issues)
  platform_pack_skill_files = validator.discover_platform_pack_skill_files(repo_root)

  for skill_name in skill_names:
    if skill_name in skill_files:
      validator.validate_skill_file(skill_name, skill_files[skill_name], issues)
      continue
    if skill_name in platform_pack_skill_files:
      skill_file = platform_pack_skill_files[skill_name]
      validator.validate_platform_pack_skill_file(skill_name, skill_file, issues)
      pack_root = repo_root / "platform-packs" / skill_file.relative_to(repo_root / "platform-packs").parts[0]
      try:
        load_platform_pack(pack_root)
      except ShellContentContractError as error:
        issues.append(f"{pack_root.relative_to(repo_root)}: {error}")
      continue
    issues.append(f"Unknown skill '{skill_name}'.")
  return issues


def _normalize_addon_body(text: str) -> str:
  if not text.strip():
    raise ValueError("Add-on body must be non-empty.")
  return text if text.endswith("\n") else f"{text}\n"


def _read_text_input(path: str) -> str:
  if path == "-":
    return sys.stdin.read()
  return Path(path).read_text(encoding="utf-8")


def _mutate_content_managed_skill(
  repo_root: Path,
  target: GovernedSkillTarget,
  replacement_text: str,
) -> object:
  from skill_bill.upgrade import upgrade_skill_wrappers

  content_before = target.content_file.read_bytes()
  wrapper_before = target.skill_file.read_bytes()
  try:
    target.content_file.write_text(replacement_text, encoding="utf-8")
    return upgrade_skill_wrappers(
      repo_root,
      skill_names=[target.skill_name],
      validate=True,
    )
  except Exception:
    target.content_file.write_bytes(content_before)
    target.skill_file.write_bytes(wrapper_before)
    raise


def _prompt_multiline(prompt: str, *, terminator: str = "END") -> str:
  while True:
    print(prompt)
    lines: list[str] = []
    while True:
      line = input()
      if line == terminator:
        break
      lines.append(line)
    if any(line.strip() for line in lines):
      return "\n".join(lines)
    print("Add-on body must include at least one non-empty line.")


def _prompt_optional_multiline(prompt: str, *, terminator: str = "END") -> str:
  print(prompt)
  lines: list[str] = []
  while True:
    line = input()
    if line == terminator:
      break
    lines.append(line)
  return "\n".join(lines).strip()


def _build_addon_markdown(name: str, description: str, body: str) -> str:
  parts = [f"# {name}", ""]
  if description:
    parts.extend([description, ""])
  parts.append(body.rstrip("\n"))
  return _normalize_addon_body("\n".join(parts))


def _prompt_new_skill_interactively(*, repo_root: Path | None = None) -> dict:
  """Collect the interactive payload without an LLM.

  The CLI should still feel usable when no agent is involved, so it asks in
  plain language first and maps the answers onto the internal scaffold kind.
  """
  from skill_bill.scaffold import platform_pack_preset

  payload: dict = {
    "scaffold_payload_version": "1.0",
  }
  platform = _prompt_nonempty("Platform name: ").strip()
  if platform.lower() == "cross-stack":
    payload["kind"] = "horizontal"
    payload["name"] = _prompt_nonempty("Skill name (bill-...): ")
    description = input("What should it do? ").strip()
    if description:
      payload["description"] = description
    return payload

  payload["platform"] = platform
  if _platform_pack_exists(platform, repo_root=repo_root):
    print("What should this platform get?")
    print("1. Code-Review Specialist")
    print("2. Platform Override")
    selection = _prompt_choice(
      "Choose 1-2: ",
      {
        "1": "code-review-area",
        "2": "platform-override-piloted",
      },
    )
  else:
    selection = "platform-pack-full"

  if selection.startswith("platform-pack"):
    payload["kind"] = "platform-pack"
    payload["platform"] = platform
    print("Code-review specialist stubs:")
    print("1. None")
    print("2. Custom subset")
    print("3. All")
    specialist_mode = _prompt_choice_with_default(
      "Choose 1-3 [default 3]: ",
      {
        "1": "none",
        "2": "custom",
        "3": "all",
      },
      default="3",
    )
    if specialist_mode == "none":
      payload["skeleton_mode"] = "starter"
    elif specialist_mode == "custom":
      payload["specialist_areas"] = _prompt_code_review_area_subset()
    display_name = input("Display name (blank to derive from slug): ").strip()
    if display_name:
      payload["display_name"] = display_name
    description = input(
      "Code-review description (blank for default text): "
    ).strip()
    if description:
      payload["description"] = description
    preset = platform_pack_preset(platform)
    if preset is None:
      strong_signals = _prompt_nonempty(
        "Strong routing signals (comma-separated): "
      )
      payload["routing_signals"] = {
        "strong": [item.strip() for item in strong_signals.split(",") if item.strip()],
        "tie_breakers": [],
      }
      tie_breakers = input("Tie-breakers (comma-separated, optional): ").strip()
      if tie_breakers:
        payload["routing_signals"]["tie_breakers"] = [
          item.strip() for item in tie_breakers.split(",") if item.strip()
        ]
    else:
      print(
        f"Using built-in routing preset for '{platform}' "
        f"({', '.join(preset['routing_signals']['strong'])})."
      )
    return payload

  if selection == "platform-override-piloted":
    payload["kind"] = "platform-override-piloted"
    family = _prompt_nonempty(
      "Family (code-review / quality-check / feature-implement / feature-verify): "
    )
    payload["family"] = family
    name = input("Skill name (blank to derive canonical name): ").strip()
    if name:
      payload["name"] = name
    description = input("One-line description (optional): ").strip()
    if description:
      payload["description"] = description
    if family in {"code-review", "quality-check"}:
      content_body = _prompt_optional_multiline(
        "Initial content.md body (optional). Finish with a line containing only END."
      )
      if content_body:
        payload["content_body"] = content_body
    return payload

  if selection == "code-review-area":
    payload["kind"] = "code-review-area"
    payload["area"] = _prompt_nonempty(
      "Area (architecture / performance / platform-correctness / security / testing / api-contracts / persistence / reliability / ui / ux-accessibility): "
    )
    name = input("Skill name (blank to derive canonical name): ").strip()
    if name:
      payload["name"] = name
    description = input("One-line description (optional): ").strip()
    if description:
      payload["description"] = description
    content_body = _prompt_optional_multiline(
      "Initial content.md body (optional). Finish with a line containing only END."
    )
    if content_body:
      payload["content_body"] = content_body
    return payload

  return payload


def _prompt_new_addon_interactively(*, repo_root: Path | None = None) -> dict:
  payload: dict = {
    "scaffold_payload_version": "1.0",
    "kind": "add-on",
  }
  platform = _prompt_nonempty("Platform name: ").strip()
  if not _platform_pack_exists(platform, repo_root=repo_root):
    raise ValueError(
      f"Platform pack '{platform}' does not exist. Create the pack before adding governed add-ons."
    )
  name = _prompt_nonempty("Add-on slug (no bill- prefix): ").strip()
  description = input("One-line description (optional): ").strip()
  body = _prompt_multiline(
    "Enter add-on markdown content. Finish with a line containing only END."
  )
  payload["platform"] = platform
  payload["name"] = name
  payload["body"] = _build_addon_markdown(name, description, body)
  return payload


def new_addon_command(args: argparse.Namespace) -> int:
  if args.interactive:
    if any(value is not None for value in (args.platform, args.name, args.body, args.body_file)):
      raise ValueError("--interactive cannot be combined with --platform, --name, --body, or --body-file.")
    payload = _prompt_new_addon_interactively()
    return _run_scaffold_command(payload, args)

  if not args.platform or not args.name:
    raise ValueError("--platform and --name are required unless --interactive is used.")

  if bool(args.body) == bool(args.body_file):
    raise ValueError("Provide exactly one of --body or --body-file unless --interactive is used.")

  body_source = args.body if args.body is not None else _read_text_input(args.body_file)
  payload = {
    "scaffold_payload_version": "1.0",
    "kind": "add-on",
    "platform": args.platform,
    "name": args.name,
    "body": _normalize_addon_body(body_source),
  }
  return _run_scaffold_command(payload, args)


def _scaffold_identity(payload: dict) -> str:
  kind = payload.get("kind", "")
  platform = str(payload.get("platform", ""))
  name = str(payload.get("name", ""))
  area = str(payload.get("area", ""))
  family = str(payload.get("family", ""))

  if name:
    return name
  if kind == "platform-pack" and platform:
    return f"bill-{platform}-code-review"
  if kind == "code-review-area" and platform and area:
    return f"bill-{platform}-code-review-{area}"
  if kind == "platform-override-piloted" and platform and family:
    return f"bill-{platform}-{family}"
  return ""


def _payload_creates_content_managed_skill(payload: dict) -> bool:
  kind = str(payload.get("kind", ""))
  if kind == "code-review-area":
    return True
  if kind == "platform-override-piloted":
    return str(payload.get("family", "")) in {"code-review", "quality-check"}
  return False


def install_agent_path_command(args: argparse.Namespace) -> int:
  """Print the canonical install directory for a given agent.

  Exposed so ``install.sh`` can shell out to Python for the agent-path
  table rather than redefining it inline. Exits non-zero when the agent
  name is unknown so the shell caller can fall back cleanly.
  """
  from skill_bill.install import SUPPORTED_AGENTS, agent_paths
  if args.agent not in SUPPORTED_AGENTS:
    raise ValueError(f"Unknown agent '{args.agent}'. Supported: {list(SUPPORTED_AGENTS)}.")
  path = agent_paths()[args.agent]
  print(str(path))
  return 0


def install_detect_agents_command(args: argparse.Namespace) -> int:
  """List detected agents (one ``name\\tpath`` per line) for install.sh."""
  from skill_bill.install import detect_agents
  for target in detect_agents():
    print(f"{target.name}\t{target.path}")
  _ = args
  return 0


def install_link_skill_command(args: argparse.Namespace) -> int:
  """Symlink a skill DIRECTORY into an agent's install directory.

  Files (e.g. add-on markdown) are shipped with their owning package and
  must not be linked here — they are resolved via sibling-file lookup at
  runtime, not by per-file symlinks into each agent's install root. Passing
  a file path as ``--source`` will raise :class:`FileNotFoundError` from
  :func:`skill_bill.install.install_skill`.

  Mirrors the behavior of ``install.sh::install_skill`` so the shell
  installer can shell out to Python instead of maintaining its own
  ``ln -s`` plumbing.
  """
  from skill_bill.install import AgentTarget, install_skill
  from pathlib import Path as _Path

  source = _Path(args.source).resolve()
  target_dir = _Path(args.target_dir).resolve()
  target_dir.mkdir(parents=True, exist_ok=True)
  install_skill(source, [AgentTarget(name=args.agent or "manual", path=target_dir)])
  return 0


def doctor_command(args: argparse.Namespace) -> int:
  if getattr(args, "subject", "") == "skill":
    if not args.skill_name:
      raise ValueError("doctor skill requires a skill name.")
    return doctor_skill_command(args)

  db_path = resolve_db_path(args.db)
  try:
    settings = load_telemetry_settings()
    telemetry_enabled = settings.enabled
    telemetry_level = settings.level
  except ValueError:
    telemetry_enabled = False
    telemetry_level = "off"
  payload: dict[str, object] = {
    "version": __version__,
    "db_path": str(db_path),
    "db_exists": db_path.exists(),
    "telemetry_enabled": telemetry_enabled,
    "telemetry_level": telemetry_level,
  }
  emit(payload, args.format)
  return 0


def workflow_show_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    workflow_id = _resolve_requested_workflow_id(connection, args.workflow_id, args.latest)
    if workflow_id is None:
      emit(
        {
          "status": "error",
          "error": "No feature-implement workflows found.",
          "db_path": str(db_path),
        },
        args.format,
      )
      return 1
    payload = build_workflow_payload(connection, workflow_id)
  if not payload:
    emit(
      {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
        "db_path": str(db_path),
      },
      args.format,
    )
    return 1
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def workflow_resume_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    workflow_id = _resolve_requested_workflow_id(connection, args.workflow_id, args.latest)
    if workflow_id is None:
      emit(
        {
          "status": "error",
          "error": "No feature-implement workflows found.",
          "db_path": str(db_path),
        },
        args.format,
      )
      return 1
    payload = build_workflow_resume_payload(connection, workflow_id)
  if not payload:
    emit(
      {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
        "db_path": str(db_path),
      },
      args.format,
    )
    return 1
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def workflow_continue_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    workflow_id = _resolve_requested_workflow_id(connection, args.workflow_id, args.latest)
    if workflow_id is None:
      emit(
        {
          "status": "error",
          "error": "No feature-implement workflows found.",
          "db_path": str(db_path),
        },
        args.format,
      )
      return 1
    payload = continue_workflow(connection, workflow_id)
  if not payload:
    emit(
      {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
        "db_path": str(db_path),
      },
      args.format,
    )
    return 1
  payload["db_path"] = str(db_path)
  if payload["continue_status"] == "blocked":
    payload["status"] = "error"
    missing_artifacts = payload.get("missing_artifacts", [])
    assert isinstance(missing_artifacts, list)
    payload["error"] = (
      "Cannot continue workflow until the missing artifacts are restored: "
      + ", ".join(str(value) for value in missing_artifacts)
    )
    emit(payload, args.format)
    return 1
  payload["status"] = "ok"
  emit(payload, args.format)
  return 0


def workflow_list_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    rows = list_workflows(connection, limit=args.limit)
  payload = {
    "status": "ok",
    "db_path": str(db_path),
    "workflow_count": len(rows),
    "workflows": [build_workflow_summary_payload(row) for row in rows],
  }
  emit(payload, args.format)
  return 0


def _resolve_requested_workflow_id(
  connection,
  workflow_id: str | None,
  latest: bool,
) -> str | None:
  if workflow_id:
    return workflow_id
  if not latest:
    raise ValueError("Provide a workflow_id or pass --latest.")
  row = fetch_latest_workflow(connection)
  if row is None:
    return None
  return str(row["workflow_id"])


def verify_workflow_show_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    workflow_id = _resolve_requested_feature_verify_workflow_id(
      connection,
      args.workflow_id,
      args.latest,
    )
    if workflow_id is None:
      emit(
        {
          "status": "error",
          "error": "No feature-verify workflows found.",
          "db_path": str(db_path),
        },
        args.format,
      )
      return 1
    payload = build_feature_verify_workflow_payload(connection, workflow_id)
  if not payload:
    emit(
      {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
        "db_path": str(db_path),
      },
      args.format,
    )
    return 1
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def verify_workflow_resume_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    workflow_id = _resolve_requested_feature_verify_workflow_id(
      connection,
      args.workflow_id,
      args.latest,
    )
    if workflow_id is None:
      emit(
        {
          "status": "error",
          "error": "No feature-verify workflows found.",
          "db_path": str(db_path),
        },
        args.format,
      )
      return 1
    payload = build_feature_verify_workflow_resume_payload(connection, workflow_id)
  if not payload:
    emit(
      {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
        "db_path": str(db_path),
      },
      args.format,
    )
    return 1
  payload["status"] = "ok"
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def verify_workflow_continue_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    workflow_id = _resolve_requested_feature_verify_workflow_id(
      connection,
      args.workflow_id,
      args.latest,
    )
    if workflow_id is None:
      emit(
        {
          "status": "error",
          "error": "No feature-verify workflows found.",
          "db_path": str(db_path),
        },
        args.format,
      )
      return 1
    payload = continue_feature_verify_workflow(connection, workflow_id)
  if not payload:
    emit(
      {
        "status": "error",
        "workflow_id": workflow_id,
        "error": f"Unknown workflow_id '{workflow_id}'.",
        "db_path": str(db_path),
      },
      args.format,
    )
    return 1
  payload["db_path"] = str(db_path)
  if payload["continue_status"] == "blocked":
    payload["status"] = "error"
    missing_artifacts = payload.get("missing_artifacts", [])
    assert isinstance(missing_artifacts, list)
    payload["error"] = (
      "Cannot continue workflow until the missing artifacts are restored: "
      + ", ".join(str(value) for value in missing_artifacts)
    )
    emit(payload, args.format)
    return 1
  payload["status"] = "ok"
  emit(payload, args.format)
  return 0


def verify_workflow_list_command(args: argparse.Namespace) -> int:
  with open_db(args.db, sync=False) as (connection, db_path):
    rows = list_feature_verify_workflows(connection, limit=args.limit)
  payload = {
    "status": "ok",
    "db_path": str(db_path),
    "workflow_count": len(rows),
    "workflows": [build_feature_verify_workflow_summary_payload(row) for row in rows],
  }
  emit(payload, args.format)
  return 0


def _resolve_requested_feature_verify_workflow_id(
  connection,
  workflow_id: str | None,
  latest: bool,
) -> str | None:
  if workflow_id:
    return workflow_id
  if not latest:
    raise ValueError("Provide a workflow_id or pass --latest.")
  row = fetch_latest_feature_verify_workflow(connection)
  if row is None:
    return None
  return str(row["workflow_id"])


def list_command(args: argparse.Namespace) -> int:
  repo_root = Path(args.repo_root).resolve()
  targets = _discover_content_managed_skill_targets(repo_root)
  if args.skill_name:
    selected_names = set(args.skill_name)
    missing = sorted(selected_names - set(targets))
    if missing:
      raise ValueError(
        f"Unknown content-managed skill(s): {', '.join(missing)}."
      )
    targets = {
      skill_name: target
      for skill_name, target in targets.items()
      if skill_name in selected_names
    }

  payload_skills: list[dict[str, object]] = []
  for skill_name, target in sorted(targets.items()):
    del skill_name
    payload_skills.append(_skill_status_payload(repo_root, target, content_mode="none"))

  emit(
    {
      "repo_root": str(repo_root),
      "skill_count": len(payload_skills),
      "skills": payload_skills,
    },
    args.format,
  )
  return 0


def show_command(args: argparse.Namespace) -> int:
  repo_root = Path(args.repo_root).resolve()
  target = _resolve_content_managed_target(repo_root, args.skill_name)
  emit(_skill_status_payload(repo_root, target, content_mode=args.content), args.format)
  return 0


def explain_command(args: argparse.Namespace) -> int:
  payload: dict[str, object] = {
    "explanation": (
      "Governed skills split author-owned behavior into content.md and generated runtime "
      "wiring into SKILL.md. Use CLI commands to keep that boundary intact."
    ),
    "editable_surface": ["content.md"],
    "generated_surface": ["SKILL.md"],
    "governed_sidecars": ["shell-ceremony.md"],
    "normal_workflow": [
      "skill-bill new --interactive",
      "skill-bill edit <skill-name>",
      "skill-bill validate --skill-name <skill-name>",
      "skill-bill render --skill-name <skill-name>",
    ],
    "notes": [
      "Author behavior changes in content.md.",
      "Regenerate wrappers with render instead of hand-editing SKILL.md.",
      "Use show or doctor skill to inspect completion, drift, and next commands.",
    ],
  }
  if args.skill_name:
    repo_root = Path(args.repo_root).resolve()
    target = _resolve_content_managed_target(repo_root, args.skill_name)
    payload["skill"] = {
      "skill_name": target.skill_name,
      "skill_file": str(target.skill_file),
      "content_file": str(target.content_file),
      "recommended_commands": _build_recommended_commands(
        repo_root,
        target,
        completion_status=_content_completion_status(
          target.content_file.read_text(encoding="utf-8")
        ),
        generation_drift=_skill_status_payload(repo_root, target, content_mode="none")[
          "generation_drift"
        ],
      ),
    }
  emit(payload, args.format)
  return 0


def validate_command(args: argparse.Namespace) -> int:
  repo_root = Path(args.repo_root).resolve()
  if args.skill_name:
    issues = _validate_selected_skills(repo_root, args.skill_name)
    suggested_commands: list[str] = []
    for skill_name in args.skill_name:
      try:
        target = _resolve_content_managed_target(repo_root, skill_name)
      except ValueError:
        continue
      suggested_commands.extend(
        _build_recommended_commands(
          repo_root,
          target,
          completion_status=_content_completion_status(
            target.content_file.read_text(encoding="utf-8")
          ),
          generation_drift=_skill_status_payload(repo_root, target, content_mode="none")[
            "generation_drift"
          ],
          issues=issues,
        )
      )
    deduped_commands: list[str] = []
    for command in suggested_commands:
      if command not in deduped_commands:
        deduped_commands.append(command)
    payload = {
      "repo_root": str(repo_root),
      "mode": "selected",
      "skill_names": args.skill_name,
      "status": "pass" if not issues else "fail",
      "issues": issues,
      "suggested_commands": deduped_commands,
    }
    emit(payload, args.format)
    return 0 if not issues else 1

  script_path = repo_root / "scripts" / "validate_agent_configs.py"
  if not script_path.is_file():
    raise ValueError(f"Validator script is missing at '{script_path}'.")
  result = subprocess.run(
    [sys.executable, str(script_path), str(repo_root)],
    capture_output=True,
    text=True,
  )
  emit(
    {
      "repo_root": str(repo_root),
      "mode": "repo",
      "status": "pass" if result.returncode == 0 else "fail",
      "exit_code": result.returncode,
      "stdout": result.stdout,
      "stderr": result.stderr,
    },
    args.format,
  )
  return result.returncode


def doctor_skill_command(args: argparse.Namespace) -> int:
  repo_root = Path(args.repo_root).resolve()
  target = _resolve_content_managed_target(repo_root, args.skill_name)
  issues = _validate_selected_skills(repo_root, [target.skill_name])
  payload = _skill_status_payload(
    repo_root,
    target,
    content_mode=args.content,
    issues=issues,
  )
  if issues:
    status = "fail"
    diagnosis = "Validation found contract or content issues that should be fixed before use."
  elif payload["completion_status"] != "complete" or payload["generation_drift"]:
    status = "warn"
    diagnosis = "The skill is usable but still needs authoring cleanup or wrapper regeneration."
  else:
    status = "pass"
    diagnosis = "The skill is complete, wrapper-aligned, and passes isolated validation."
  payload["status"] = status
  payload["diagnosis"] = diagnosis
  emit(payload, args.format)
  return 0 if status == "pass" else 1


def upgrade_command(args: argparse.Namespace) -> int:
  from skill_bill.upgrade import upgrade_skill_wrappers

  result = upgrade_skill_wrappers(
    args.repo_root,
    skill_names=args.skill_name,
    validate=not args.skip_validate,
  )
  emit(
    {
      "repo_root": str(result.repo_root),
      "regenerated_count": len(result.regenerated_files),
      "regenerated_files": [str(path) for path in result.regenerated_files],
      "content_md_touched": False,
      "shell_ceremony_touched": False,
      "validator_ran": not args.skip_validate,
    },
    args.format,
  )
  return 0


def _resolve_editor_command() -> list[str]:
  editor = os.environ.get("VISUAL") or os.environ.get("EDITOR") or ""
  if not editor.strip():
    return []
  return shlex.split(editor)


def _edit_content_via_editor(content_file: Path) -> bool:
  command = _resolve_editor_command()
  if not command:
    return False
  subprocess.run(
    [*command, str(content_file)],
    check=True,
  )
  return True


def _fill_content_text(
  target: GovernedSkillTarget,
  *,
  body: str,
  section_name: str | None = None,
) -> str:
  if section_name:
    current = target.content_file.read_text(encoding="utf-8")
    return _replace_section_body(current, section_name, body)
  return _coerce_full_content_text(target, body)


def fill_command(args: argparse.Namespace) -> int:
  repo_root = Path(args.repo_root).resolve()
  target = _resolve_content_managed_target(repo_root, args.skill_name)
  body = args.body if args.body is not None else _read_text_input(args.body_file)
  replacement = _fill_content_text(
    target,
    body=body,
    section_name=args.section,
  )
  upgrade_result = _mutate_content_managed_skill(repo_root, target, replacement)
  emit(
    {
      **_skill_status_payload(repo_root, target, content_mode="none"),
      "updated_section": (
        _section_heading_label(args.section) if args.section else None
      ),
      "wrapper_regenerated": str(target.skill_file) in {
        str(path) for path in upgrade_result.regenerated_files
      },
      "validator_ran": True,
    },
    args.format,
  )
  return 0


def edit_command(args: argparse.Namespace) -> int:
  repo_root = Path(args.repo_root).resolve()
  target = _resolve_content_managed_target(repo_root, args.skill_name)
  used_editor = False
  guided_sections: list[dict[str, str]] = []

  if args.editor and args.section:
    raise ValueError("--editor cannot be combined with --section.")

  if args.body_file:
    body_text = _read_text_input(args.body_file)
    replacement = _fill_content_text(
      target,
      body=body_text,
      section_name=args.section,
    )
  elif args.editor:
    used_editor = _edit_content_via_editor(target.content_file)
    if not used_editor:
      raise ValueError("No $VISUAL or $EDITOR is configured.")
    replacement = target.content_file.read_text(encoding="utf-8")
  elif args.section:
    replacement, guided_sections = _edit_single_section_guided(
      target.content_file,
      args.section,
    )
  else:
    replacement, guided_sections = _edit_content_guided(target.content_file)

  upgrade_result = _mutate_content_managed_skill(repo_root, target, replacement)

  emit(
    {
      "skill_name": target.skill_name,
      "package": target.package,
      "platform": target.platform,
      "family": target.family,
      "area": target.area,
      "skill_file": str(target.skill_file),
      "content_file": str(target.content_file),
      "used_editor": used_editor,
      "guided_sections": guided_sections,
      "updated_section": (
        _section_heading_label(args.section) if args.section else None
      ),
      "completion_status": _content_completion_status(
        target.content_file.read_text(encoding="utf-8")
      ),
      "wrapper_regenerated": str(target.skill_file) in {
        str(path) for path in upgrade_result.regenerated_files
      },
      "validator_ran": True,
    },
    args.format,
  )
  return 0


def create_and_fill_command(args: argparse.Namespace) -> int:
  if args.payload and args.interactive:
    raise ValueError("--payload and --interactive are mutually exclusive.")
  if args.body is not None and args.body_file is not None:
    raise ValueError("--body and --body-file are mutually exclusive.")

  if args.payload:
    payload_path = args.payload
    if payload_path == "-":
      payload_text = sys.stdin.read()
    else:
      with open(payload_path, encoding="utf-8") as stream:
        payload_text = stream.read()
    try:
      payload = json.loads(payload_text)
    except json.JSONDecodeError as error:
      raise ValueError(f"Invalid JSON payload: {error}") from error
  elif args.interactive:
    payload = _prompt_new_skill_interactively()
  else:
    raise ValueError("Either --payload or --interactive is required.")

  if not _payload_creates_content_managed_skill(payload):
    raise ValueError(
      "create-and-fill is only available for one content-managed skill at a time "
      "(code-review areas and shelled platform overrides). "
      "Use `skill-bill new` for horizontal skills, pre-shell overrides, or platform packs."
    )

  outcome = _execute_scaffold_command(payload, args)
  if outcome.exit_code != 0:
    return outcome.exit_code
  if args.dry_run:
    emit(outcome.payload or {}, args.format)
    return 0
  if outcome.result is None:
    raise RuntimeError("Scaffolder reported success without a result payload.")

  repo_root = Path.cwd().resolve()
  target = _resolve_content_managed_target(repo_root, outcome.result.skill_name)
  guided_sections: list[dict[str, str]] = []
  used_editor = False
  if args.body is not None or args.body_file:
    replacement = _fill_content_text(
      target,
      body=args.body if args.body is not None else _read_text_input(args.body_file),
    )
  elif args.editor:
    used_editor = _edit_content_via_editor(target.content_file)
    if not used_editor:
      raise ValueError("No $VISUAL or $EDITOR is configured.")
    replacement = target.content_file.read_text(encoding="utf-8")
  else:
    replacement, guided_sections = _edit_content_guided(target.content_file)

  upgrade_result = _mutate_content_managed_skill(repo_root, target, replacement)
  emit(
    {
      "scaffold": outcome.payload,
      "authoring": {
        **_skill_status_payload(repo_root, target, content_mode="none"),
        "used_editor": used_editor,
        "guided_sections": guided_sections,
        "wrapper_regenerated": str(target.skill_file) in {
          str(path) for path in upgrade_result.regenerated_files
        },
        "validator_ran": True,
      },
    },
    args.format,
  )
  return 0


def build_parser() -> argparse.ArgumentParser:
  parser = argparse.ArgumentParser(
    description="Import Skill Bill review output, triage numbered findings, and manage local review learnings."
  )
  parser.add_argument(
    "--db",
    help=f"Optional SQLite path. Defaults to ${DB_ENVIRONMENT_KEY} or {DEFAULT_DB_PATH}.",
  )
  subparsers = parser.add_subparsers(dest="command", required=True)

  import_parser = subparsers.add_parser(
    "import-review",
    help="Import a review output file or stdin into the local SQLite store.",
  )
  import_parser.add_argument("input", nargs="?", default="-", help="Path to review text, or '-' for stdin.")
  import_parser.add_argument("--format", choices=("text", "json"), default="text")
  import_parser.set_defaults(handler=import_review_command)

  feedback_parser = subparsers.add_parser(
    "record-feedback",
    help="Record explicit feedback events for one or more findings in an imported review run.",
  )
  feedback_parser.add_argument("--run-id", required=True, help="Imported review run id.")
  feedback_parser.add_argument(
    "--event",
    choices=FINDING_OUTCOME_TYPES,
    required=True,
    help="Canonical finding outcome to record.",
  )
  feedback_parser.add_argument(
    "--finding",
    action="append",
    required=True,
    help="Finding id to update. Repeat the flag to record multiple findings.",
  )
  feedback_parser.add_argument("--note", default="", help="Optional note for the recorded feedback event.")
  feedback_parser.add_argument("--format", choices=("text", "json"), default="text")
  feedback_parser.set_defaults(handler=record_feedback_command)

  triage_parser = subparsers.add_parser(
    "triage",
    help="Show numbered findings for a review run and record triage decisions by number.",
  )
  triage_parser.add_argument("--run-id", required=True, help="Imported review run id.")
  triage_parser.add_argument(
    "--decision",
    action="append",
    help="Triage entry like '1 fix', '2 skip - intentional', or '3 accept - good catch'.",
  )
  triage_parser.add_argument("--list", action="store_true", help="Show the numbered findings without recording decisions.")
  triage_parser.add_argument("--format", choices=("text", "json"), default="text")
  triage_parser.set_defaults(handler=triage_command)

  stats_parser = subparsers.add_parser(
    "stats",
    help="Show aggregate or per-run review acceptance metrics from the local SQLite store.",
  )
  stats_parser.add_argument("--run-id", help="Optional review run id to scope stats to one review.")
  stats_parser.add_argument("--format", choices=("text", "json"), default="text")
  stats_parser.set_defaults(handler=stats_command)

  learnings_parser = subparsers.add_parser(
    "learnings",
    help="Manage local review learnings derived from rejected review findings.",
  )
  learnings_subparsers = learnings_parser.add_subparsers(dest="learnings_command", required=True)

  learnings_add_parser = learnings_subparsers.add_parser(
    "add",
    help="Create a learning from a rejected review finding.",
  )
  learnings_add_parser.add_argument("--scope", choices=LEARNING_SCOPES, default="global")
  learnings_add_parser.add_argument("--scope-key", default="")
  learnings_add_parser.add_argument("--title", required=True)
  learnings_add_parser.add_argument("--rule", required=True)
  learnings_add_parser.add_argument("--reason", default="", help="Rationale; auto-populated from the rejection note when omitted.")
  learnings_add_parser.add_argument("--from-run", required=True, help="Source review run id.")
  learnings_add_parser.add_argument("--from-finding", required=True, help="Source finding id.")
  learnings_add_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_add_parser.set_defaults(handler=learnings_add_command)

  learnings_list_parser = learnings_subparsers.add_parser("list", help="List local learning entries.")
  learnings_list_parser.add_argument("--status", choices=("all",) + LEARNING_STATUSES, default="all")
  learnings_list_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_list_parser.set_defaults(handler=learnings_list_command)

  learnings_show_parser = learnings_subparsers.add_parser("show", help="Show a single learning entry.")
  learnings_show_parser.add_argument("--id", type=int, required=True)
  learnings_show_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_show_parser.set_defaults(handler=learnings_show_command)

  learnings_resolve_parser = learnings_subparsers.add_parser(
    "resolve",
    help="Resolve active learnings for a review context using global, repo, and skill scope.",
  )
  learnings_resolve_parser.add_argument("--repo", help="Optional repo scope key to match repo-scoped learnings.")
  learnings_resolve_parser.add_argument("--skill", help="Optional review skill name to match skill-scoped learnings.")
  learnings_resolve_parser.add_argument(
    "--review-session-id",
    help="Review session id for cross-event grouping. Required when telemetry is enabled.",
  )
  learnings_resolve_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_resolve_parser.set_defaults(handler=learnings_resolve_command)

  learnings_edit_parser = learnings_subparsers.add_parser("edit", help="Edit a local learning entry.")
  learnings_edit_parser.add_argument("--id", type=int, required=True)
  learnings_edit_parser.add_argument("--scope", choices=LEARNING_SCOPES)
  learnings_edit_parser.add_argument("--scope-key")
  learnings_edit_parser.add_argument("--title")
  learnings_edit_parser.add_argument("--rule")
  learnings_edit_parser.add_argument("--reason")
  learnings_edit_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_edit_parser.set_defaults(handler=learnings_edit_command)

  learnings_disable_parser = learnings_subparsers.add_parser("disable", help="Disable a learning entry.")
  learnings_disable_parser.add_argument("--id", type=int, required=True)
  learnings_disable_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_disable_parser.set_defaults(handler=learnings_status_command, status_value="disabled")

  learnings_enable_parser = learnings_subparsers.add_parser("enable", help="Enable a disabled learning entry.")
  learnings_enable_parser.add_argument("--id", type=int, required=True)
  learnings_enable_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_enable_parser.set_defaults(handler=learnings_status_command, status_value="active")

  learnings_delete_parser = learnings_subparsers.add_parser("delete", help="Delete a learning entry.")
  learnings_delete_parser.add_argument("--id", type=int, required=True)
  learnings_delete_parser.add_argument("--format", choices=("text", "json"), default="text")
  learnings_delete_parser.set_defaults(handler=learnings_delete_command)

  telemetry_parser = subparsers.add_parser(
    "telemetry",
    help="Inspect, control, and manually sync remote telemetry.",
  )
  telemetry_subparsers = telemetry_parser.add_subparsers(dest="telemetry_command", required=True)

  telemetry_status_parser = telemetry_subparsers.add_parser("status", help="Show local telemetry configuration and sync status.")
  telemetry_status_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_status_parser.set_defaults(handler=telemetry_status_command)

  telemetry_sync_parser = telemetry_subparsers.add_parser("sync", help="Flush pending telemetry events to the active proxy target.")
  telemetry_sync_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_sync_parser.set_defaults(handler=telemetry_sync_command)

  telemetry_enable_parser = telemetry_subparsers.add_parser("enable", help="Enable remote telemetry sync.")
  telemetry_enable_parser.add_argument(
    "--level",
    choices=("anonymous", "full"),
    default="anonymous",
    dest="level_value",
    help="Telemetry detail level. Defaults to anonymous.",
  )
  telemetry_enable_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_enable_parser.set_defaults(handler=telemetry_toggle_command, enabled_value=True)

  telemetry_disable_parser = telemetry_subparsers.add_parser("disable", help="Disable remote telemetry sync.")
  telemetry_disable_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_disable_parser.set_defaults(handler=telemetry_toggle_command, enabled_value=False)

  telemetry_set_level_parser = telemetry_subparsers.add_parser(
    "set-level",
    help="Set the telemetry detail level directly.",
  )
  telemetry_set_level_parser.add_argument(
    "level",
    choices=TELEMETRY_LEVELS,
    help="Telemetry level: off, anonymous, or full.",
  )
  telemetry_set_level_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_set_level_parser.set_defaults(handler=telemetry_set_level_command)

  version_parser = subparsers.add_parser("version", help="Show the installed skill-bill version.")
  version_parser.add_argument("--format", choices=("text", "json"), default="text")
  version_parser.set_defaults(handler=version_command)

  doctor_parser = subparsers.add_parser("doctor", help="Check skill-bill installation health.")
  doctor_parser.add_argument(
    "subject",
    nargs="?",
    choices=("skill",),
    help="Optional diagnostic subject. Use `skill` for one governed skill.",
  )
  doctor_parser.add_argument(
    "skill_name",
    nargs="?",
    help="Governed skill name when diagnosing one skill.",
  )
  doctor_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to inspect when using `doctor skill`.",
  )
  doctor_parser.add_argument(
    "--content",
    choices=("none", "preview", "full"),
    default="preview",
    help="How much content.md text to include when using `doctor skill`.",
  )
  doctor_parser.add_argument("--format", choices=("text", "json"), default="text")
  doctor_parser.set_defaults(handler=doctor_command)

  workflow_parser = subparsers.add_parser(
    "workflow",
    help="Inspect or resume durable workflow-state pilots.",
  )
  workflow_subparsers = workflow_parser.add_subparsers(dest="workflow_command", required=True)

  workflow_list_parser = workflow_subparsers.add_parser(
    "list",
    help="List recent persisted workflow runs.",
  )
  workflow_list_parser.add_argument(
    "--limit",
    type=int,
    default=20,
    help="Maximum number of workflows to return. Defaults to 20.",
  )
  workflow_list_parser.add_argument("--format", choices=("text", "json"), default="text")
  workflow_list_parser.set_defaults(handler=workflow_list_command)

  workflow_show_parser = workflow_subparsers.add_parser(
    "show",
    help="Show raw persisted state for a workflow run.",
  )
  workflow_show_parser.add_argument("workflow_id", nargs="?", help="Workflow id to inspect.")
  workflow_show_parser.add_argument(
    "--latest",
    action="store_true",
    help="Resolve the most recently updated workflow automatically.",
  )
  workflow_show_parser.add_argument("--format", choices=("text", "json"), default="text")
  workflow_show_parser.set_defaults(handler=workflow_show_command)

  workflow_resume_parser = workflow_subparsers.add_parser(
    "resume",
    help="Summarize how to resume or recover a workflow run.",
  )
  workflow_resume_parser.add_argument("workflow_id", nargs="?", help="Workflow id to resume or recover.")
  workflow_resume_parser.add_argument(
    "--latest",
    action="store_true",
    help="Resolve the most recently updated workflow automatically.",
  )
  workflow_resume_parser.add_argument("--format", choices=("text", "json"), default="text")
  workflow_resume_parser.set_defaults(handler=workflow_resume_command)

  workflow_continue_parser = workflow_subparsers.add_parser(
    "continue",
    help="Activate a resumable workflow and emit a recovered continuation brief.",
  )
  workflow_continue_parser.add_argument("workflow_id", nargs="?", help="Workflow id to continue.")
  workflow_continue_parser.add_argument(
    "--latest",
    action="store_true",
    help="Resolve the most recently updated workflow automatically.",
  )
  workflow_continue_parser.add_argument("--format", choices=("text", "json"), default="text")
  workflow_continue_parser.set_defaults(handler=workflow_continue_command)

  verify_workflow_parser = subparsers.add_parser(
    "verify-workflow",
    help="Inspect or resume durable bill-feature-verify workflow-state runs.",
  )
  verify_workflow_subparsers = verify_workflow_parser.add_subparsers(
    dest="verify_workflow_command",
    required=True,
  )

  verify_workflow_list_parser = verify_workflow_subparsers.add_parser(
    "list",
    help="List recent persisted feature-verify workflow runs.",
  )
  verify_workflow_list_parser.add_argument(
    "--limit",
    type=int,
    default=20,
    help="Maximum number of workflows to return. Defaults to 20.",
  )
  verify_workflow_list_parser.add_argument("--format", choices=("text", "json"), default="text")
  verify_workflow_list_parser.set_defaults(handler=verify_workflow_list_command)

  verify_workflow_show_parser = verify_workflow_subparsers.add_parser(
    "show",
    help="Show raw persisted state for a feature-verify workflow run.",
  )
  verify_workflow_show_parser.add_argument("workflow_id", nargs="?", help="Workflow id to inspect.")
  verify_workflow_show_parser.add_argument(
    "--latest",
    action="store_true",
    help="Resolve the most recently updated feature-verify workflow automatically.",
  )
  verify_workflow_show_parser.add_argument("--format", choices=("text", "json"), default="text")
  verify_workflow_show_parser.set_defaults(handler=verify_workflow_show_command)

  verify_workflow_resume_parser = verify_workflow_subparsers.add_parser(
    "resume",
    help="Summarize how to resume or recover a feature-verify workflow run.",
  )
  verify_workflow_resume_parser.add_argument(
    "workflow_id",
    nargs="?",
    help="Workflow id to resume or recover.",
  )
  verify_workflow_resume_parser.add_argument(
    "--latest",
    action="store_true",
    help="Resolve the most recently updated feature-verify workflow automatically.",
  )
  verify_workflow_resume_parser.add_argument("--format", choices=("text", "json"), default="text")
  verify_workflow_resume_parser.set_defaults(handler=verify_workflow_resume_command)

  verify_workflow_continue_parser = verify_workflow_subparsers.add_parser(
    "continue",
    help="Activate a resumable feature-verify workflow and emit a recovered continuation brief.",
  )
  verify_workflow_continue_parser.add_argument(
    "workflow_id",
    nargs="?",
    help="Workflow id to continue.",
  )
  verify_workflow_continue_parser.add_argument(
    "--latest",
    action="store_true",
    help="Resolve the most recently updated feature-verify workflow automatically.",
  )
  verify_workflow_continue_parser.add_argument("--format", choices=("text", "json"), default="text")
  verify_workflow_continue_parser.set_defaults(handler=verify_workflow_continue_command)

  list_parser = subparsers.add_parser(
    "list",
    help="List content-managed skills and their authoring status.",
  )
  list_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to inspect. Defaults to the current working directory.",
  )
  list_parser.add_argument(
    "--skill-name",
    action="append",
    help="Optional content-managed skill name to include. Repeat to target multiple skills.",
  )
  list_parser.add_argument("--format", choices=("text", "json"), default="text")
  list_parser.set_defaults(handler=list_command)

  show_parser = subparsers.add_parser(
    "show",
    help="Show one content-managed skill with section status, drift, and recommended next commands.",
  )
  show_parser.add_argument("skill_name", help="Governed skill name to inspect.")
  show_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to inspect. Defaults to the current working directory.",
  )
  show_parser.add_argument(
    "--content",
    choices=("none", "preview", "full"),
    default="preview",
    help="How much content.md text to include.",
  )
  show_parser.add_argument("--format", choices=("text", "json"), default="text")
  show_parser.set_defaults(handler=show_command)

  explain_parser = subparsers.add_parser(
    "explain",
    help="Explain the governed authoring boundary and the CLI workflow for content-managed skills.",
  )
  explain_parser.add_argument(
    "skill_name",
    nargs="?",
    help="Optional governed skill name to explain with concrete paths.",
  )
  explain_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to inspect when explaining one skill.",
  )
  explain_parser.add_argument("--format", choices=("text", "json"), default="text")
  explain_parser.set_defaults(handler=explain_command)

  validate_parser = subparsers.add_parser(
    "validate",
    help="Run the repo validator, or validate specific skills only.",
  )
  validate_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to validate. Defaults to the current working directory.",
  )
  validate_parser.add_argument(
    "--skill-name",
    action="append",
    help="Optional skill name to validate in isolation. Repeat to target multiple skills.",
  )
  validate_parser.add_argument("--format", choices=("text", "json"), default="text")
  validate_parser.set_defaults(handler=validate_command)

  upgrade_parser = subparsers.add_parser(
    "upgrade",
    help="Regenerate scaffold-managed SKILL.md wrappers without touching authored sidecars.",
  )
  upgrade_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to upgrade. Defaults to the current working directory.",
  )
  upgrade_parser.add_argument(
    "--skip-validate",
    action="store_true",
    help="Skip scripts/validate_agent_configs.py after wrapper regeneration.",
  )
  upgrade_parser.add_argument(
    "--skill-name",
    action="append",
    help="Optional governed or horizontal skill name to regenerate. Repeat to target multiple skills.",
  )
  upgrade_parser.add_argument("--format", choices=("text", "json"), default="text")
  upgrade_parser.set_defaults(handler=upgrade_command)

  render_parser = subparsers.add_parser(
    "render",
    help="Alias for upgrade: regenerate scaffold-managed SKILL.md wrappers.",
  )
  render_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to upgrade. Defaults to the current working directory.",
  )
  render_parser.add_argument(
    "--skip-validate",
    action="store_true",
    help="Skip scripts/validate_agent_configs.py after wrapper regeneration.",
  )
  render_parser.add_argument(
    "--skill-name",
    action="append",
    help="Optional governed or horizontal skill name to regenerate. Repeat to target multiple skills.",
  )
  render_parser.add_argument("--format", choices=("text", "json"), default="text")
  render_parser.set_defaults(handler=upgrade_command)

  edit_parser = subparsers.add_parser(
    "edit",
    help="Edit a content-managed skill's authored content.md and regenerate the wrapper.",
  )
  edit_parser.add_argument("skill_name", help="Governed skill name to edit.")
  edit_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to edit. Defaults to the current working directory.",
  )
  edit_parser.add_argument(
    "--body-file",
    help="Replace content.md from a file path (or '-' for stdin) instead of prompting or launching $EDITOR.",
  )
  edit_parser.add_argument(
    "--editor",
    action="store_true",
    help="Open content.md in $VISUAL or $EDITOR instead of using guided section editing.",
  )
  edit_parser.add_argument(
    "--section",
    help="Optional authored H2 section name to edit in isolation.",
  )
  edit_parser.add_argument("--format", choices=("text", "json"), default="text")
  edit_parser.set_defaults(handler=edit_command)

  fill_parser = subparsers.add_parser(
    "fill",
    help="Write authored content into content.md, regenerate the wrapper, and validate the result.",
  )
  fill_parser.add_argument("skill_name", help="Governed skill name to fill.")
  fill_parser.add_argument(
    "--repo-root",
    default=".",
    help="Repo root to edit. Defaults to the current working directory.",
  )
  fill_group = fill_parser.add_mutually_exclusive_group(required=True)
  fill_group.add_argument(
    "--body",
    help="Body text to write. If it does not start with '# ', the command preserves the existing H1 title.",
  )
  fill_group.add_argument(
    "--body-file",
    help="Read body text from a file path or '-' for stdin.",
  )
  fill_parser.add_argument(
    "--section",
    help="Optional authored H2 section name to replace instead of replacing the full body.",
  )
  fill_parser.add_argument("--format", choices=("text", "json"), default="text")
  fill_parser.set_defaults(handler=fill_command)

  new_skill_parser = subparsers.add_parser(
    "new-skill",
    help="Scaffold a new skill from a payload file or interactive prompts.",
  )
  new_skill_parser.add_argument(
    "--payload",
    help="Path to a JSON payload file (or '-' for stdin).",
  )
  new_skill_parser.add_argument(
    "--interactive",
    action="store_true",
    help="Collect a skill scaffold payload via interactive prompts.",
  )
  new_skill_parser.add_argument(
    "--dry-run",
    action="store_true",
    help="Plan the scaffold and report the operations without touching disk.",
  )
  new_skill_parser.add_argument("--format", choices=("text", "json"), default="text")
  new_skill_parser.set_defaults(handler=new_skill_command)

  new_parser = subparsers.add_parser(
    "new",
    help="Alias for new-skill: scaffold one skill from a payload file or interactive prompts.",
  )
  new_parser.add_argument(
    "--payload",
    help="Path to a JSON payload file (or '-' for stdin).",
  )
  new_parser.add_argument(
    "--interactive",
    action="store_true",
    help="Collect a skill scaffold payload via interactive prompts.",
  )
  new_parser.add_argument(
    "--dry-run",
    action="store_true",
    help="Plan the scaffold and report the operations without touching disk.",
  )
  new_parser.add_argument("--format", choices=("text", "json"), default="text")
  new_parser.set_defaults(handler=new_skill_command)

  create_and_fill_parser = subparsers.add_parser(
    "create-and-fill",
    help="Scaffold one governed skill, then immediately author content.md and validate it.",
  )
  create_and_fill_parser.add_argument(
    "--payload",
    help="Path to a JSON payload file (or '-' for stdin).",
  )
  create_and_fill_parser.add_argument(
    "--interactive",
    action="store_true",
    help="Collect a skill scaffold payload via interactive prompts.",
  )
  create_and_fill_parser.add_argument(
    "--dry-run",
    action="store_true",
    help="Plan the scaffold and report the operations without touching disk.",
  )
  create_and_fill_group = create_and_fill_parser.add_mutually_exclusive_group()
  create_and_fill_group.add_argument(
    "--body",
    help="Optional authored body to write after scaffolding.",
  )
  create_and_fill_group.add_argument(
    "--body-file",
    help="Optional file path (or '-' for stdin) to read the authored body from after scaffolding.",
  )
  create_and_fill_group.add_argument(
    "--editor",
    action="store_true",
    help="Open the newly scaffolded content.md in $VISUAL or $EDITOR after scaffolding.",
  )
  create_and_fill_parser.add_argument("--format", choices=("text", "json"), default="text")
  create_and_fill_parser.set_defaults(handler=create_and_fill_command)

  new_addon_parser = subparsers.add_parser(
    "new-addon",
    help="Create a governed add-on file inside an existing platform pack.",
  )
  new_addon_parser.add_argument(
    "--platform",
    help="Owning platform slug. Required unless --interactive is used.",
  )
  new_addon_parser.add_argument(
    "--name",
    help="Add-on slug (without a bill- prefix). Required unless --interactive is used.",
  )
  body_group = new_addon_parser.add_mutually_exclusive_group()
  body_group.add_argument(
    "--body",
    help="Complete markdown body to write to the add-on file.",
  )
  body_group.add_argument(
    "--body-file",
    help="Path to a markdown file to copy into the add-on (or '-' for stdin).",
  )
  new_addon_parser.add_argument(
    "--interactive",
    action="store_true",
    help="Prompt for platform, add-on slug, and markdown content.",
  )
  new_addon_parser.add_argument(
    "--dry-run",
    action="store_true",
    help="Plan the scaffold and report the operations without touching disk.",
  )
  new_addon_parser.add_argument("--format", choices=("text", "json"), default="text")
  new_addon_parser.set_defaults(handler=new_addon_command)

  install_parser = subparsers.add_parser(
    "install",
    help="Install-side primitives (agent-path lookup, agent detection, skill symlinking).",
  )
  install_subparsers = install_parser.add_subparsers(dest="install_command", required=True)

  agent_path_parser = install_subparsers.add_parser(
    "agent-path",
    help="Print the canonical install directory for a given agent.",
  )
  agent_path_parser.add_argument("agent")
  agent_path_parser.set_defaults(handler=install_agent_path_command)

  detect_parser = install_subparsers.add_parser(
    "detect-agents",
    help="List detected agents as 'name\\tpath' lines.",
  )
  detect_parser.set_defaults(handler=install_detect_agents_command)

  link_parser = install_subparsers.add_parser(
    "link-skill",
    help=(
      "Symlink a skill DIRECTORY into an agent's install directory. Files "
      "(e.g. add-on markdown) are shipped with their owning package and "
      "must not be linked here."
    ),
  )
  link_parser.add_argument("--source", required=True)
  link_parser.add_argument("--target-dir", required=True)
  link_parser.add_argument("--agent", default="")
  link_parser.set_defaults(handler=install_link_skill_command)

  return parser


def main(argv: list[str] | None = None) -> int:
  parser = build_parser()
  args = parser.parse_args(argv)

  try:
    return int(args.handler(args))
  except ValueError as error:
    print(str(error), file=sys.stderr)
    return 1
  except Exception as error:
    from skill_bill.shell_content_contract import ShellContentContractError

    if isinstance(error, ShellContentContractError):
      print(str(error), file=sys.stderr)
      return 1
    raise


if __name__ == "__main__":
  raise SystemExit(main())
