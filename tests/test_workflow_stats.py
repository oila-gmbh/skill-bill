from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.cli import main  # noqa: E402
from skill_bill.db import ensure_database  # noqa: E402
from skill_bill.mcp_server import (  # noqa: E402
  feature_implement_stats,
  feature_verify_stats,
)


class WorkflowStatsTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.resolved_db_path = str(Path(self.db_path).resolve())
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value
    self.seed_workflow_sessions()

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def seed_workflow_sessions(self) -> None:
    connection = ensure_database(Path(self.db_path))
    try:
      with connection:
        connection.executemany(
          """
          INSERT INTO feature_verify_sessions (
            session_id,
            acceptance_criteria_count,
            rollout_relevant,
            spec_summary,
            started_at,
            feature_flag_audit_performed,
            review_iterations,
            audit_result,
            completion_status,
            history_relevance,
            history_helpfulness,
            gaps_found,
            finished_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          [
            (
              "fvr-20260422-100000-aa11",
              4,
              1,
              "Verify a telemetry-backed payment retry flow.",
              "2026-04-22 10:00:00",
              1,
              2,
              "had_gaps",
              "completed",
              "high",
              "medium",
              json.dumps(["rollout toggle missing"]),
              "2026-04-22 10:05:00",
            ),
            (
              "fvr-20260422-110000-bb22",
              6,
              0,
              "Verify an abandoned audit flow.",
              "2026-04-22 11:00:00",
              0,
              1,
              "skipped",
              "abandoned_at_audit",
              "irrelevant",
              "low",
              json.dumps([]),
              "2026-04-22 11:10:00",
            ),
            (
              "fvr-20260422-120000-cc33",
              5,
              1,
              "Verify a still-running workflow.",
              "2026-04-22 12:00:00",
              None,
              None,
              None,
              None,
              "none",
              "none",
              "",
              None,
            ),
          ],
        )
        connection.executemany(
          """
          INSERT INTO feature_implement_sessions (
            session_id,
            issue_key_provided,
            issue_key_type,
            spec_input_types,
            spec_word_count,
            feature_size,
            feature_name,
            rollout_needed,
            acceptance_criteria_count,
            open_questions_count,
            spec_summary,
            started_at,
            completion_status,
            plan_correction_count,
            plan_task_count,
            plan_phase_count,
            feature_flag_used,
            feature_flag_pattern,
            files_created,
            files_modified,
            tasks_completed,
            review_iterations,
            audit_result,
            audit_iterations,
            validation_result,
            boundary_history_written,
            boundary_history_value,
            pr_created,
            plan_deviation_notes,
            finished_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          [
            (
              "fis-20260422-100000-aa11",
              1,
              "jira",
              json.dumps(["markdown_file"]),
              2000,
              "MEDIUM",
              "payment-retry",
              1,
              5,
              1,
              "Retry failed payments with backoff.",
              "2026-04-22 10:00:00",
              "completed",
              0,
              8,
              1,
              1,
              "legacy",
              3,
              5,
              8,
              2,
              "all_pass",
              1,
              "pass",
              1,
              "medium",
              1,
              "Split one task for quality-check follow-up.",
              "2026-04-22 10:20:00",
            ),
            (
              "fis-20260422-110000-bb22",
              0,
              "none",
              json.dumps(["raw_text"]),
              400,
              "SMALL",
              "copy tweak",
              0,
              2,
              0,
              "Tweak wording in one screen.",
              "2026-04-22 11:00:00",
              "abandoned_at_review",
              1,
              2,
              1,
              0,
              "none",
              0,
              2,
              1,
              1,
              "skipped",
              0,
              "skipped",
              0,
              "none",
              0,
              "",
              "2026-04-22 11:05:00",
            ),
            (
              "fis-20260422-120000-cc33",
              1,
              "linear",
              json.dumps(["pdf"]),
              3000,
              "LARGE",
              "billing migration",
              1,
              7,
              2,
              "Large migration still in progress.",
              "2026-04-22 12:00:00",
              "",
              None,
              None,
              None,
              None,
              "",
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              "none",
              None,
              "",
              None,
            ),
          ],
        )
    finally:
      connection.close()

  def test_verify_stats_command_reports_aggregate_metrics(self) -> None:
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
      exit_code = main(["verify-stats", "--format", "json"])

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["workflow"], "bill-feature-verify")
    self.assertEqual(payload["total_runs"], 3)
    self.assertEqual(payload["finished_runs"], 2)
    self.assertEqual(payload["in_progress_runs"], 1)
    self.assertEqual(payload["completion_status_counts"]["completed"], 1)
    self.assertEqual(payload["completion_status_counts"]["abandoned_at_audit"], 1)
    self.assertEqual(payload["audit_result_counts"]["had_gaps"], 1)
    self.assertEqual(payload["audit_result_counts"]["skipped"], 1)
    self.assertEqual(payload["rollout_relevant_runs"], 2)
    self.assertEqual(payload["rollout_relevant_rate"], 0.667)
    self.assertEqual(payload["feature_flag_audit_performed_runs"], 1)
    self.assertEqual(payload["feature_flag_audit_performed_rate"], 0.5)
    self.assertEqual(payload["history_read_runs"], 2)
    self.assertEqual(payload["history_read_rate"], 1.0)
    self.assertEqual(payload["history_relevant_runs"], 1)
    self.assertEqual(payload["history_relevant_rate"], 0.5)
    self.assertEqual(payload["history_helpful_runs"], 1)
    self.assertEqual(payload["history_helpful_rate"], 0.5)
    self.assertEqual(payload["history_relevance_counts"]["high"], 1)
    self.assertEqual(payload["history_relevance_counts"]["irrelevant"], 1)
    self.assertEqual(payload["history_helpfulness_counts"]["medium"], 1)
    self.assertEqual(payload["history_helpfulness_counts"]["low"], 1)
    self.assertEqual(payload["runs_with_gaps_found"], 1)
    self.assertEqual(payload["average_acceptance_criteria_count"], 5.0)
    self.assertEqual(payload["average_review_iterations"], 1.5)
    self.assertEqual(payload["average_duration_seconds"], 450.0)
    self.assertEqual(payload["db_path"], self.resolved_db_path)

  def test_implement_stats_command_reports_aggregate_metrics(self) -> None:
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
      exit_code = main(["implement-stats", "--format", "json"])

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["workflow"], "bill-feature-implement")
    self.assertEqual(payload["total_runs"], 3)
    self.assertEqual(payload["finished_runs"], 2)
    self.assertEqual(payload["in_progress_runs"], 1)
    self.assertEqual(payload["feature_size_counts"]["SMALL"], 1)
    self.assertEqual(payload["feature_size_counts"]["MEDIUM"], 1)
    self.assertEqual(payload["feature_size_counts"]["LARGE"], 1)
    self.assertEqual(payload["completion_status_counts"]["completed"], 1)
    self.assertEqual(payload["completion_status_counts"]["abandoned_at_review"], 1)
    self.assertEqual(payload["audit_result_counts"]["all_pass"], 1)
    self.assertEqual(payload["audit_result_counts"]["skipped"], 1)
    self.assertEqual(payload["validation_result_counts"]["pass"], 1)
    self.assertEqual(payload["validation_result_counts"]["skipped"], 1)
    self.assertEqual(payload["feature_flag_pattern_counts"]["legacy"], 1)
    self.assertEqual(payload["feature_flag_pattern_counts"]["none"], 1)
    self.assertEqual(payload["rollout_needed_runs"], 2)
    self.assertEqual(payload["rollout_needed_rate"], 0.667)
    self.assertEqual(payload["feature_flag_used_runs"], 1)
    self.assertEqual(payload["feature_flag_used_rate"], 0.5)
    self.assertEqual(payload["pr_created_runs"], 1)
    self.assertEqual(payload["pr_created_rate"], 0.5)
    self.assertEqual(payload["boundary_history_written_runs"], 1)
    self.assertEqual(payload["boundary_history_written_rate"], 0.5)
    self.assertEqual(payload["average_acceptance_criteria_count"], 4.67)
    self.assertEqual(payload["average_spec_word_count"], 1800.0)
    self.assertEqual(payload["average_review_iterations"], 1.5)
    self.assertEqual(payload["average_audit_iterations"], 0.5)
    self.assertEqual(payload["average_files_created"], 1.5)
    self.assertEqual(payload["average_files_modified"], 3.5)
    self.assertEqual(payload["average_tasks_completed"], 4.5)
    self.assertEqual(payload["average_duration_seconds"], 750.0)
    self.assertEqual(payload["db_path"], self.resolved_db_path)

  def test_verify_stats_mcp_tool_reports_same_shape(self) -> None:
    payload = feature_verify_stats()
    self.assertEqual(payload["workflow"], "bill-feature-verify")
    self.assertEqual(payload["total_runs"], 3)
    self.assertEqual(payload["runs_with_gaps_found"], 1)
    self.assertEqual(payload["db_path"], self.resolved_db_path)

  def test_implement_stats_mcp_tool_reports_same_shape(self) -> None:
    payload = feature_implement_stats()
    self.assertEqual(payload["workflow"], "bill-feature-implement")
    self.assertEqual(payload["total_runs"], 3)
    self.assertEqual(payload["pr_created_runs"], 1)
    self.assertEqual(payload["db_path"], self.resolved_db_path)


if __name__ == "__main__":
  unittest.main()
