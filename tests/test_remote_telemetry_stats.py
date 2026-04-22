from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.cli import main  # noqa: E402
from skill_bill.constants import (  # noqa: E402
  CONFIG_ENVIRONMENT_KEY,
  TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY,
  TELEMETRY_PROXY_URL_ENVIRONMENT_KEY,
)
from skill_bill.mcp_server import (  # noqa: E402
  telemetry_proxy_capabilities,
  telemetry_remote_stats,
)


class RemoteTelemetryStatsTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.config_path = os.path.join(self.temp_dir, "config.json")
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "off", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      CONFIG_ENVIRONMENT_KEY: self.config_path,
      TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
      TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY: "stats-token-123",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value

  def tearDown(self) -> None:
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_cli_telemetry_stats_verify_posts_proxy_contract(self) -> None:
    captured_requests: list[dict[str, object]] = []

    class FakeResponse:
      def __init__(self, payload: dict[str, object]) -> None:
        self.status = 200
        self.payload = payload

      def __enter__(self):
        return self

      def __exit__(self, exc_type, exc, tb):
        return False

      def getcode(self) -> int:
        return 200

      def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")

    def fake_urlopen(request, timeout=10):
      body = None
      if request.data is not None:
        body = json.loads(request.data.decode("utf-8"))
      captured_requests.append({
        "url": request.full_url,
        "method": request.get_method(),
        "body": body,
        "authorization": request.headers.get("Authorization"),
      })
      if request.full_url.endswith("/capabilities"):
        return FakeResponse({
          "contract_version": "1",
          "supports_ingest": True,
          "supports_stats": True,
          "supported_workflows": [
            "bill-feature-verify",
            "bill-feature-implement",
          ],
        })
      return FakeResponse({
        "status": "ok",
        "workflow": "bill-feature-verify",
        "source": "remote_proxy",
        "started_runs": 14,
        "finished_runs": 12,
        "in_progress_runs": 2,
        "in_progress_rate": 0.143,
        "completion_rate": 0.75,
        "history_read_runs": 9,
        "history_read_rate": 0.75,
        "history_relevant_runs": 7,
        "history_relevant_rate": 0.583,
        "history_helpful_runs": 6,
        "history_helpful_rate": 0.5,
        "history_relevance_counts": {
          "none": 3,
          "irrelevant": 2,
          "low": 0,
          "medium": 4,
          "high": 3,
        },
        "history_helpfulness_counts": {
          "none": 3,
          "irrelevant": 1,
          "low": 2,
          "medium": 4,
          "high": 2,
        },
      })

    stdout = io.StringIO()
    with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
      with contextlib.redirect_stdout(stdout):
        exit_code = main([
          "telemetry",
          "stats",
          "verify",
          "--date-from",
          "2026-04-01",
          "--date-to",
          "2026-04-22",
          "--format",
          "json",
        ])

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["workflow"], "bill-feature-verify")
    self.assertEqual(payload["started_runs"], 14)
    self.assertEqual(payload["finished_runs"], 12)
    self.assertEqual(payload["in_progress_runs"], 2)
    self.assertEqual(payload["in_progress_rate"], 0.143)
    self.assertEqual(payload["history_relevant_runs"], 7)
    self.assertEqual(payload["history_helpful_rate"], 0.5)
    self.assertEqual(payload["source"], "remote_proxy")
    self.assertEqual(
      captured_requests,
      [
        {
          "url": "https://telemetry.example.dev/ingest/capabilities",
          "method": "GET",
          "body": None,
          "authorization": "Bearer stats-token-123",
        },
        {
          "url": "https://telemetry.example.dev/ingest/stats",
          "method": "POST",
          "body": {
            "workflow": "bill-feature-verify",
            "date_from": "2026-04-01",
            "date_to": "2026-04-22",
          },
          "authorization": "Bearer stats-token-123",
        },
      ],
    )
    self.assertEqual(
      payload["capabilities"]["supports_stats"],
      True,
    )

  def test_cli_telemetry_stats_verify_posts_group_by_when_requested(self) -> None:
    captured_requests: list[dict[str, object]] = []

    class FakeResponse:
      def __init__(self, payload: dict[str, object]) -> None:
        self.status = 200
        self.payload = payload

      def __enter__(self):
        return self

      def __exit__(self, exc_type, exc, tb):
        return False

      def getcode(self) -> int:
        return 200

      def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")

    def fake_urlopen(request, timeout=10):
      body = None
      if request.data is not None:
        body = json.loads(request.data.decode("utf-8"))
      captured_requests.append({
        "url": request.full_url,
        "method": request.get_method(),
        "body": body,
      })
      if request.full_url.endswith("/capabilities"):
        return FakeResponse({
          "contract_version": "1",
          "supports_ingest": True,
          "supports_stats": True,
          "supported_workflows": [
            "bill-feature-verify",
            "bill-feature-implement",
          ],
        })
      return FakeResponse({
        "status": "ok",
        "workflow": "bill-feature-verify",
        "source": "remote_proxy",
        "group_by": "day",
        "series": [
          {
            "bucket_start": "2026-04-01",
            "bucket_end": "2026-04-01",
            "started_runs": 2,
            "finished_runs": 1,
            "in_progress_runs": 1,
            "in_progress_rate": 0.5,
            "completion_rate": 0.5,
            "abandonment_rate": 0,
          },
        ],
      })

    stdout = io.StringIO()
    with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
      with contextlib.redirect_stdout(stdout):
        exit_code = main([
          "telemetry",
          "stats",
          "verify",
          "--since",
          "7d",
          "--group-by",
          "day",
          "--format",
          "json",
        ])

    self.assertEqual(exit_code, 0)
    payload = json.loads(stdout.getvalue())
    self.assertEqual(payload["group_by"], "day")
    self.assertEqual(payload["series"][0]["bucket_start"], "2026-04-01")
    self.assertEqual(
      captured_requests[1]["body"],
      {
        "workflow": "bill-feature-verify",
        "date_from": payload["date_from"],
        "date_to": payload["date_to"],
        "group_by": "day",
      },
    )

  def test_cli_telemetry_stats_rejects_invalid_since_format(self) -> None:
    stderr = io.StringIO()
    with contextlib.redirect_stderr(stderr):
      exit_code = main(["telemetry", "stats", "verify", "--since", "month"])

    self.assertEqual(exit_code, 1)
    self.assertIn("since must use <days>d format", stderr.getvalue())

  def test_mcp_tool_fetches_remote_implement_stats(self) -> None:
    captured_requests: list[dict[str, object]] = []

    class FakeResponse:
      def __init__(self, payload: dict[str, object]) -> None:
        self.status = 200
        self.payload = payload

      def __enter__(self):
        return self

      def __exit__(self, exc_type, exc, tb):
        return False

      def getcode(self) -> int:
        return 200

      def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")

    def fake_urlopen(request, timeout=10):
      body = None
      if request.data is not None:
        body = json.loads(request.data.decode("utf-8"))
      captured_requests.append({
        "url": request.full_url,
        "method": request.get_method(),
        "body": body,
      })
      if request.full_url.endswith("/capabilities"):
        return FakeResponse({
          "contract_version": "1",
          "supports_ingest": True,
          "supports_stats": True,
          "supported_workflows": [
            "bill-feature-verify",
            "bill-feature-implement",
          ],
        })
      return FakeResponse({
        "status": "ok",
        "workflow": "bill-feature-implement",
        "source": "remote_proxy",
        "started_runs": 20,
        "finished_runs": 18,
        "in_progress_runs": 2,
        "in_progress_rate": 0.1,
        "pr_created_rate": 0.61,
        "boundary_history_written_runs": 12,
        "boundary_history_written_rate": 0.667,
        "boundary_history_useful_runs": 12,
        "boundary_history_useful_rate": 0.667,
        "boundary_history_value_counts": {
          "none": 3,
          "irrelevant": 1,
          "low": 2,
          "medium": 7,
          "high": 5,
        },
      })

    with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
      payload = telemetry_remote_stats(
        workflow="bill-feature-implement",
        date_from="2026-04-01",
        date_to="2026-04-22",
        since="",
      )

    self.assertEqual(payload["workflow"], "bill-feature-implement")
    self.assertEqual(payload["started_runs"], 20)
    self.assertEqual(payload["in_progress_runs"], 2)
    self.assertEqual(payload["pr_created_rate"], 0.61)
    self.assertEqual(payload["boundary_history_written_runs"], 12)
    self.assertEqual(payload["boundary_history_useful_runs"], 12)
    self.assertEqual(payload["boundary_history_useful_rate"], 0.667)
    self.assertEqual(payload["boundary_history_value_counts"]["medium"], 7)
    self.assertEqual(captured_requests[0]["url"], "https://telemetry.example.dev/ingest/capabilities")
    self.assertEqual(captured_requests[0]["method"], "GET")
    self.assertEqual(captured_requests[1]["url"], "https://telemetry.example.dev/ingest/stats")
    self.assertEqual(captured_requests[1]["method"], "POST")
    self.assertEqual(
      captured_requests[1]["body"],
      {
        "workflow": "bill-feature-implement",
        "date_from": "2026-04-01",
        "date_to": "2026-04-22",
      },
    )

  def test_mcp_tool_fetches_grouped_remote_implement_stats(self) -> None:
    captured_requests: list[dict[str, object]] = []

    class FakeResponse:
      def __init__(self, payload: dict[str, object]) -> None:
        self.status = 200
        self.payload = payload

      def __enter__(self):
        return self

      def __exit__(self, exc_type, exc, tb):
        return False

      def getcode(self) -> int:
        return 200

      def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")

    def fake_urlopen(request, timeout=10):
      body = None
      if request.data is not None:
        body = json.loads(request.data.decode("utf-8"))
      captured_requests.append({
        "url": request.full_url,
        "method": request.get_method(),
        "body": body,
      })
      if request.full_url.endswith("/capabilities"):
        return FakeResponse({
          "contract_version": "1",
          "supports_ingest": True,
          "supports_stats": True,
          "supported_workflows": [
            "bill-feature-verify",
            "bill-feature-implement",
          ],
        })
      return FakeResponse({
        "status": "ok",
        "workflow": "bill-feature-implement",
        "source": "remote_proxy",
        "group_by": "week",
        "series": [
          {
            "bucket_start": "2026-03-30",
            "bucket_end": "2026-04-05",
            "started_runs": 4,
            "finished_runs": 3,
            "in_progress_runs": 1,
            "in_progress_rate": 0.25,
            "completion_rate": 0.5,
          },
        ],
      })

    with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
      payload = telemetry_remote_stats(
        workflow="bill-feature-implement",
        date_from="2026-04-01",
        date_to="2026-04-22",
        since="",
        group_by="week",
      )

    self.assertEqual(payload["group_by"], "week")
    self.assertEqual(payload["series"][0]["bucket_end"], "2026-04-05")
    self.assertEqual(
      captured_requests[1]["body"],
      {
        "workflow": "bill-feature-implement",
        "date_from": "2026-04-01",
        "date_to": "2026-04-22",
        "group_by": "week",
      },
    )

  def test_capabilities_command_and_mcp_tool_use_proxy_contract(self) -> None:
    class FakeResponse:
      status = 200

      def __enter__(self):
        return self

      def __exit__(self, exc_type, exc, tb):
        return False

      def getcode(self) -> int:
        return 200

      def read(self) -> bytes:
        return json.dumps({
          "contract_version": "1",
          "supports_ingest": True,
          "supports_stats": True,
          "supported_workflows": [
            "bill-feature-verify",
            "bill-feature-implement",
          ],
        }).encode("utf-8")

    with patch.object(urllib.request, "urlopen", return_value=FakeResponse()):
      stdout = io.StringIO()
      with contextlib.redirect_stdout(stdout):
        exit_code = main(["telemetry", "capabilities", "--format", "json"])
      self.assertEqual(exit_code, 0)
      cli_payload = json.loads(stdout.getvalue())
      mcp_payload = telemetry_proxy_capabilities()

    self.assertEqual(cli_payload["contract_version"], "1")
    self.assertTrue(cli_payload["supports_stats"])
    self.assertEqual(
      cli_payload["supported_workflows"],
      ["bill-feature-verify", "bill-feature-implement"],
    )
    self.assertEqual(mcp_payload["contract_version"], "1")
    self.assertTrue(mcp_payload["supports_ingest"])

  def test_remote_stats_fails_cleanly_when_proxy_is_ingest_only(self) -> None:
    class NotFoundError(urllib.error.HTTPError):
      def __init__(self) -> None:
        super().__init__(
          url="https://telemetry.example.dev/ingest/capabilities",
          code=404,
          msg="Not Found",
          hdrs=None,
          fp=None,
        )

      def read(self) -> bytes:
        return b'{"error":"Not found."}'

    def fake_urlopen(request, timeout=10):
      if request.full_url.endswith("/capabilities"):
        raise NotFoundError()
      raise AssertionError("stats should not be called when capabilities say unsupported")

    stderr = io.StringIO()
    with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
      with contextlib.redirect_stderr(stderr):
        exit_code = main(["telemetry", "stats", "verify"])

    self.assertEqual(exit_code, 1)
    self.assertIn("does not support remote stats yet", stderr.getvalue())

  def test_worker_normalize_verify_stats_uses_started_runs_for_completion_rate(self) -> None:
    worker_path = ROOT / "docs" / "cloudflare-telemetry-proxy" / "worker.js"
    temp_dir = tempfile.mkdtemp()
    temp_worker = Path(temp_dir) / "worker.mjs"
    shutil.copyfile(worker_path, temp_worker)
    script = f"""
import {{ normalizeVerifyStats }} from {json.dumps(temp_worker.as_uri())};
const payload = normalizeVerifyStats({{
  started_runs: 10,
  finished_runs: 6,
  completion_status_completed: 4,
  completion_status_abandoned_at_review: 1,
  completion_status_abandoned_at_audit: 1,
  completion_status_error: 0,
  history_read_runs: 5,
  history_relevance_none: 1,
  history_relevance_irrelevant: 1,
  history_relevance_low: 1,
  history_relevance_medium: 2,
  history_relevance_high: 1,
  history_helpfulness_none: 1,
  history_helpfulness_irrelevant: 1,
  history_helpfulness_low: 2,
  history_helpfulness_medium: 1,
  history_helpfulness_high: 1,
  audit_result_all_pass: 3,
  audit_result_had_gaps: 2,
  audit_result_skipped: 1,
  rollout_relevant_runs: 7,
  feature_flag_audit_performed_runs: 5,
  average_acceptance_criteria_count: 4.25,
  average_review_iterations: 1.5,
  average_duration_seconds: 42.0,
}}, "2026-04-01", "2026-04-22");
console.log(JSON.stringify(payload));
"""
    try:
      result = subprocess.run(
        ["node", "--input-type=module", "--eval", script],
        check=True,
        capture_output=True,
        text=True,
      )
    finally:
      shutil.rmtree(temp_dir, ignore_errors=True)

    payload = json.loads(result.stdout)
    self.assertEqual(payload["started_runs"], 10)
    self.assertEqual(payload["finished_runs"], 6)
    self.assertEqual(payload["in_progress_runs"], 4)
    self.assertEqual(payload["in_progress_rate"], 0.4)
    self.assertEqual(payload["completion_rate"], 0.4)
    self.assertEqual(payload["abandonment_rate"], 0.2)
    self.assertEqual(payload["history_read_rate"], 0.833)
    self.assertEqual(payload["history_relevant_runs"], 3)
    self.assertEqual(payload["history_helpful_runs"], 2)
    self.assertEqual(payload["feature_flag_audit_performed_rate"], 0.833)

  def test_worker_normalize_implement_stats_uses_started_runs_for_completion_rate(self) -> None:
    worker_path = ROOT / "docs" / "cloudflare-telemetry-proxy" / "worker.js"
    temp_dir = tempfile.mkdtemp()
    temp_worker = Path(temp_dir) / "worker.mjs"
    shutil.copyfile(worker_path, temp_worker)
    script = f"""
import {{ normalizeImplementStats }} from {json.dumps(temp_worker.as_uri())};
const payload = normalizeImplementStats({{
  started_runs: 12,
  finished_runs: 9,
  completion_status_completed: 6,
  completion_status_abandoned_at_planning: 1,
  completion_status_abandoned_at_implementation: 1,
  completion_status_abandoned_at_review: 1,
  completion_status_error: 0,
  feature_size_small: 4,
  feature_size_medium: 5,
  feature_size_large: 3,
  audit_result_all_pass: 5,
  audit_result_had_gaps: 3,
  audit_result_skipped: 1,
  validation_result_pass: 7,
  validation_result_fail: 1,
  validation_result_skipped: 1,
  rollout_needed_runs: 8,
  feature_flag_used_runs: 7,
  pr_created_runs: 5,
  boundary_history_written_runs: 6,
  boundary_history_value_none: 1,
  boundary_history_value_irrelevant: 1,
  boundary_history_value_low: 1,
  boundary_history_value_medium: 3,
  boundary_history_value_high: 3,
  average_acceptance_criteria_count: 3.0,
  average_spec_word_count: 1200,
  average_review_iterations: 1.8,
  average_audit_iterations: 1.2,
  average_duration_seconds: 88.5,
}}, "2026-04-01", "2026-04-22");
console.log(JSON.stringify(payload));
"""
    try:
      result = subprocess.run(
        ["node", "--input-type=module", "--eval", script],
        check=True,
        capture_output=True,
        text=True,
      )
    finally:
      shutil.rmtree(temp_dir, ignore_errors=True)

    payload = json.loads(result.stdout)
    self.assertEqual(payload["started_runs"], 12)
    self.assertEqual(payload["finished_runs"], 9)
    self.assertEqual(payload["in_progress_runs"], 3)
    self.assertEqual(payload["in_progress_rate"], 0.25)
    self.assertEqual(payload["completion_rate"], 0.5)
    self.assertEqual(payload["feature_flag_used_rate"], 0.778)
    self.assertEqual(payload["pr_created_rate"], 0.556)
    self.assertEqual(payload["boundary_history_written_rate"], 0.667)
    self.assertEqual(payload["boundary_history_useful_runs"], 6)
    self.assertEqual(payload["boundary_history_useful_rate"], 0.667)
    self.assertEqual(payload["boundary_history_value_counts"]["medium"], 3)

  def test_worker_builds_weekly_verify_series(self) -> None:
    worker_path = ROOT / "docs" / "cloudflare-telemetry-proxy" / "worker.js"
    temp_dir = tempfile.mkdtemp()
    temp_worker = Path(temp_dir) / "worker.mjs"
    shutil.copyfile(worker_path, temp_worker)
    script = f"""
import {{ buildVerifySeries }} from {json.dumps(temp_worker.as_uri())};
const payload = buildVerifySeries([
  {{
    bucket_date: "2026-04-01",
    started_runs: 2,
    finished_runs: 1,
    rollout_relevant_runs: 2,
    feature_flag_audit_performed_runs: 1,
    history_read_runs: 1,
    history_relevance_none: 0,
    history_relevance_irrelevant: 0,
    history_relevance_low: 0,
    history_relevance_medium: 1,
    history_relevance_high: 0,
    history_helpfulness_none: 0,
    history_helpfulness_irrelevant: 0,
    history_helpfulness_low: 0,
    history_helpfulness_medium: 1,
    history_helpfulness_high: 0,
    audit_result_all_pass: 0,
    audit_result_had_gaps: 1,
    audit_result_skipped: 0,
    completion_status_completed: 1,
    completion_status_abandoned_at_review: 0,
    completion_status_abandoned_at_audit: 0,
    completion_status_error: 0,
  }},
  {{
    bucket_date: "2026-04-03",
    started_runs: 1,
    finished_runs: 1,
    rollout_relevant_runs: 1,
    feature_flag_audit_performed_runs: 1,
    history_read_runs: 1,
    history_relevance_none: 0,
    history_relevance_irrelevant: 0,
    history_relevance_low: 0,
    history_relevance_medium: 0,
    history_relevance_high: 1,
    history_helpfulness_none: 0,
    history_helpfulness_irrelevant: 0,
    history_helpfulness_low: 0,
    history_helpfulness_medium: 0,
    history_helpfulness_high: 1,
    audit_result_all_pass: 0,
    audit_result_had_gaps: 1,
    audit_result_skipped: 0,
    completion_status_completed: 1,
    completion_status_abandoned_at_review: 0,
    completion_status_abandoned_at_audit: 0,
    completion_status_error: 0,
  }},
  {{
    bucket_date: "2026-04-08",
    started_runs: 1,
    finished_runs: 0,
    rollout_relevant_runs: 1,
    feature_flag_audit_performed_runs: 0,
    history_read_runs: 0,
    history_relevance_none: 0,
    history_relevance_irrelevant: 0,
    history_relevance_low: 0,
    history_relevance_medium: 0,
    history_relevance_high: 0,
    history_helpfulness_none: 0,
    history_helpfulness_irrelevant: 0,
    history_helpfulness_low: 0,
    history_helpfulness_medium: 0,
    history_helpfulness_high: 0,
    audit_result_all_pass: 0,
    audit_result_had_gaps: 0,
    audit_result_skipped: 0,
    completion_status_completed: 0,
    completion_status_abandoned_at_review: 0,
    completion_status_abandoned_at_audit: 0,
    completion_status_error: 0,
  }},
], "week", "2026-04-01", "2026-04-10");
console.log(JSON.stringify(payload));
"""
    try:
      result = subprocess.run(
        ["node", "--input-type=module", "--eval", script],
        check=True,
        capture_output=True,
        text=True,
      )
    finally:
      shutil.rmtree(temp_dir, ignore_errors=True)

    payload = json.loads(result.stdout)
    self.assertEqual(len(payload), 2)
    self.assertEqual(payload[0]["bucket_start"], "2026-04-01")
    self.assertEqual(payload[0]["bucket_end"], "2026-04-05")
    self.assertEqual(payload[0]["started_runs"], 3)
    self.assertEqual(payload[0]["finished_runs"], 2)
    self.assertEqual(payload[0]["in_progress_runs"], 1)
    self.assertEqual(payload[0]["completion_rate"], 0.667)
    self.assertEqual(payload[0]["history_relevant_runs"], 2)
    self.assertEqual(payload[0]["history_helpful_runs"], 2)
    self.assertEqual(payload[1]["bucket_start"], "2026-04-06")
    self.assertEqual(payload[1]["bucket_end"], "2026-04-10")


if __name__ == "__main__":
  unittest.main()
