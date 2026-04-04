from __future__ import annotations

import argparse
import json
import sys

from skill_bill import __version__
from skill_bill.config import (
  load_telemetry_settings,
  set_telemetry_enabled,
  telemetry_is_enabled,
)
from skill_bill.constants import (
  DB_ENVIRONMENT_KEY,
  DEFAULT_DB_PATH,
  FINDING_OUTCOME_TYPES,
  LEARNING_SCOPE_PRECEDENCE,
  LEARNING_SCOPES,
  LEARNING_STATUSES,
)
from skill_bill.db import ensure_database, resolve_db_path
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
  auto_sync_telemetry,
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
  db_path = resolve_db_path(args.db)
  text, source_path = read_input(args.input)
  review = parse_review(text)
  connection = ensure_database(db_path)
  try:
    save_imported_review(connection, review, source_path=source_path)
  finally:
    connection.close()

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
  auto_sync_telemetry(db_path)
  return 0


def record_feedback_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    record_feedback(
      connection,
      review_run_id=args.run_id,
      finding_ids=args.finding,
      event_type=args.event,
      note=args.note,
    )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
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
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
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
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
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
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload = stats_payload(connection, args.run_id)
  finally:
    connection.close()

  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_add_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
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
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_list_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload_entries = [learning_payload(row) for row in list_learnings(connection, status=args.status)]
  finally:
    connection.close()

  if args.format == "json":
    emit({"db_path": str(db_path), "learnings": payload_entries}, args.format)
  else:
    print_learnings(payload_entries)
  return 0


def learnings_show_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload = learning_payload(get_learning(connection, args.id))
  finally:
    connection.close()

  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_resolve_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  telemetry_enabled = telemetry_is_enabled()
  try:
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
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
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
  db_path = resolve_db_path(args.db)
  if all(
    value is None
    for value in (args.scope, args.scope_key, args.title, args.rule, args.reason)
  ):
    raise ValueError("Learning edit requires at least one field to update.")

  connection = ensure_database(db_path)
  try:
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
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_status_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    payload = learning_payload(
      set_learning_status(connection, learning_id=args.id, status=args.status_value)
    )
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
  payload["db_path"] = str(db_path)
  emit(payload, args.format)
  return 0


def learnings_delete_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  connection = ensure_database(db_path)
  try:
    delete_learning(connection, args.id)
  finally:
    connection.close()

  auto_sync_telemetry(db_path)
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
  settings, cleared_events = set_telemetry_enabled(
    args.enabled_value,
    db_path=resolve_db_path(args.db),
  )
  payload = {
    "config_path": str(settings.config_path),
    "telemetry_enabled": settings.enabled,
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


def doctor_command(args: argparse.Namespace) -> int:
  db_path = resolve_db_path(args.db)
  telemetry_enabled = telemetry_is_enabled()
  payload: dict[str, object] = {
    "version": __version__,
    "db_path": str(db_path),
    "db_exists": db_path.exists(),
    "telemetry_enabled": telemetry_enabled,
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
  telemetry_enable_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_enable_parser.set_defaults(handler=telemetry_toggle_command, enabled_value=True)

  telemetry_disable_parser = telemetry_subparsers.add_parser("disable", help="Disable remote telemetry sync.")
  telemetry_disable_parser.add_argument("--format", choices=("text", "json"), default="text")
  telemetry_disable_parser.set_defaults(handler=telemetry_toggle_command, enabled_value=False)

  version_parser = subparsers.add_parser("version", help="Show the installed skill-bill version.")
  version_parser.add_argument("--format", choices=("text", "json"), default="text")
  version_parser.set_defaults(handler=version_command)

  doctor_parser = subparsers.add_parser("doctor", help="Check skill-bill installation health.")
  doctor_parser.add_argument("--format", choices=("text", "json"), default="text")
  doctor_parser.set_defaults(handler=doctor_command)

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
