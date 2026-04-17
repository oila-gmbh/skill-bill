from __future__ import annotations

import argparse
import json
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


def new_skill_command(args: argparse.Namespace) -> int:
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

  session_id = _scaffold_domain.generate_new_skill_session_id()

  try:
    level = load_telemetry_settings().level
  except ValueError:
    level = "anonymous"

  started_payload = _scaffold_domain.build_started_payload_from_fields(
    session_id=session_id,
    kind=str(payload.get("kind", "")),
    skill_name=str(payload.get("name", "")),
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
    return exit_code_map.get(type(error), 1)
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

  emit(
    {
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
    args.format,
  )
  return 0


def _prompt_new_skill_interactively() -> dict:
  """Collect the four-prompt interactive payload (no LLM).

  Mirrors the decision tree in ``skills/base/bill-new-skill-all-agents/SKILL.md``
  so operators can bypass the LLM wrapper when they know exactly what they
  want to create.
  """
  kind = input("Kind (horizontal/platform-override-piloted/code-review-area/add-on): ").strip()
  name = input("Skill name (bill-...): ").strip()
  platform = input("Platform slug (blank for horizontal): ").strip()
  area = input("Area slug (blank unless code-review-area): ").strip()
  payload: dict = {
    "scaffold_payload_version": "1.0",
    "kind": kind,
    "name": name,
  }
  if platform:
    payload["platform"] = platform
  if area:
    payload["area"] = area
  if kind == "platform-override-piloted":
    family = input("Family (code-review/quality-check/feature-implement/feature-verify): ").strip()
    if family:
      payload["family"] = family
  return payload


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
  doctor_parser.add_argument("--format", choices=("text", "json"), default="text")
  doctor_parser.set_defaults(handler=doctor_command)

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
    help="Collect the payload via four no-LLM prompts (kind, name, platform, area).",
  )
  new_skill_parser.add_argument(
    "--dry-run",
    action="store_true",
    help="Plan the scaffold and report the operations without touching disk.",
  )
  new_skill_parser.add_argument("--format", choices=("text", "json"), default="text")
  new_skill_parser.set_defaults(handler=new_skill_command)

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


if __name__ == "__main__":
  raise SystemExit(main())
