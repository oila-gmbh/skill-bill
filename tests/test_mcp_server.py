from __future__ import annotations

import json
from pathlib import Path
import os
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.mcp_server import (
  doctor,
  import_review,
  resolve_learnings,
  review_stats,
  triage_findings,
)


SAMPLE_REVIEW = """\
Routed to: bill-kotlin-code-review
Review session ID: rvs-20260405-mcp
Review run ID: rvw-20260405-mcp
Detected review scope: unstaged changes
Detected stack: kotlin
Signals: README.md, install.sh
Execution mode: inline
Reason: kotlin signals dominate

### 2. Risk Register
- [F-001] Major | High | README.md:12 | README wording is stale after the routing change.
- [F-002] Minor | Medium | install.sh:88 | Installer prompt wording is inconsistent with the new flow.
"""


class McpServerEnabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.config_path = os.path.join(self.temp_dir, "config.json")
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"enabled": True, "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    self._original_env = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": self.config_path,
      "SKILL_BILL_TELEMETRY_ENABLED": "true",
      "SKILL_BILL_INSTALL_ID": "test-install-id",
      # Defensive: pin the relay URL to an unreachable loopback so any
      # accidental telemetry sync during tests cannot ship events to the
      # production PostHog project (#43).
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
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
    import shutil
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_doctor_returns_version_and_db_info(self) -> None:
    result = doctor()
    self.assertIn("version", result)
    self.assertIn("db_path", result)
    self.assertIn("db_exists", result)
    self.assertIn("telemetry_enabled", result)
    self.assertIn("telemetry_level", result)
    self.assertTrue(result["telemetry_enabled"])
    self.assertEqual(result["telemetry_level"], "anonymous")

  def test_import_review_parses_and_stores(self) -> None:
    result = import_review(review_text=SAMPLE_REVIEW)
    self.assertEqual(result["review_run_id"], "rvw-20260405-mcp")
    self.assertEqual(result["review_session_id"], "rvs-20260405-mcp")
    self.assertEqual(result["finding_count"], 2)
    self.assertEqual(result["routed_skill"], "bill-kotlin-code-review")
    self.assertEqual(result["detected_stack"], "kotlin")

  def test_triage_findings_records_decisions(self) -> None:
    import_review(review_text=SAMPLE_REVIEW)
    result = triage_findings(
      review_run_id="rvw-20260405-mcp",
      decisions=["1 fix", "2 skip - intentional"],
    )
    self.assertEqual(result["review_run_id"], "rvw-20260405-mcp")
    self.assertEqual(len(result["recorded"]), 2)
    self.assertEqual(result["recorded"][0]["outcome_type"], "fix_applied")
    self.assertEqual(result["recorded"][1]["outcome_type"], "fix_rejected")
    self.assertEqual(result["recorded"][1]["note"], "intentional")

  def test_triage_findings_accepts_structured_selection_decisions(self) -> None:
    import_review(review_text=SAMPLE_REVIEW)
    result = triage_findings(
      review_run_id="rvw-20260405-mcp",
      decisions=["fix=[1] reject=[2]"],
    )
    self.assertEqual(result["review_run_id"], "rvw-20260405-mcp")
    self.assertEqual(
      result["recorded"],
      [
        {
          "number": 1,
          "finding_id": "F-001",
          "outcome_type": "fix_applied",
          "note": "",
        },
        {
          "number": 2,
          "finding_id": "F-002",
          "outcome_type": "fix_rejected",
          "note": "",
        },
      ],
    )

  def test_review_stats_returns_metrics(self) -> None:
    import_review(review_text=SAMPLE_REVIEW)
    result = review_stats(review_run_id="rvw-20260405-mcp")
    self.assertEqual(result["total_findings"], 2)
    self.assertEqual(result["unresolved_findings"], 2)
    self.assertIn("accepted_rate", result)

  def test_review_stats_aggregate(self) -> None:
    result = review_stats()
    self.assertEqual(result["total_findings"], 0)
    self.assertIn("db_path", result)

  def test_resolve_learnings_returns_empty_when_none(self) -> None:
    result = resolve_learnings()
    self.assertEqual(result["applied_learnings"], "none")
    self.assertEqual(result["learnings"], [])
    self.assertIn("scope_precedence", result)

  def test_import_then_stats_then_triage_flow(self) -> None:
    import_review(review_text=SAMPLE_REVIEW)

    stats_before = review_stats(review_run_id="rvw-20260405-mcp")
    self.assertEqual(stats_before["unresolved_findings"], 2)

    triage_findings(
      review_run_id="rvw-20260405-mcp",
      decisions=["1 fix", "2 fix"],
    )

    stats_after = review_stats(review_run_id="rvw-20260405-mcp")
    self.assertEqual(stats_after["unresolved_findings"], 0)
    self.assertEqual(stats_after["accepted_findings"], 2)


class McpServerDisabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self._original_env = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": os.path.join(self.temp_dir, "metrics.db"),
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
      # Belt-and-braces: even when telemetry is off in this fixture, pin the
      # relay URL so an accidental enable can never ship to prod (#43).
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
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
    import shutil
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_import_review_skips_when_disabled(self) -> None:
    result = import_review(review_text=SAMPLE_REVIEW)
    self.assertEqual(result["status"], "skipped")
    self.assertEqual(result["review_run_id"], "rvw-20260405-mcp")
    self.assertEqual(result["finding_count"], 2)
    self.assertNotIn("db_path", result)

  def test_triage_findings_skips_when_disabled(self) -> None:
    result = triage_findings(
      review_run_id="rvw-20260405-mcp",
      decisions=["1 fix"],
    )
    self.assertEqual(result["status"], "skipped")
    self.assertNotIn("recorded", result)

  def test_resolve_learnings_skips_when_disabled(self) -> None:
    result = resolve_learnings()
    self.assertEqual(result["status"], "skipped")
    self.assertEqual(result["applied_learnings"], "none")
    self.assertEqual(result["learnings"], [])

  def test_doctor_still_works_when_disabled(self) -> None:
    result = doctor()
    self.assertIn("version", result)
    self.assertFalse(result["telemetry_enabled"])
    self.assertEqual(result["telemetry_level"], "off")

  def test_no_db_created_when_disabled(self) -> None:
    import_review(review_text=SAMPLE_REVIEW)
    db_path = os.path.join(self.temp_dir, "metrics.db")
    self.assertFalse(os.path.exists(db_path))


if __name__ == "__main__":
  unittest.main()
