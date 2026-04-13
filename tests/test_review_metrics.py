from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import urllib.error  # noqa: E402
import urllib.request  # noqa: E402

from skill_bill.cli import main  # noqa: E402
from skill_bill.constants import (  # noqa: E402
  CONFIG_ENVIRONMENT_KEY,
  DEFAULT_TELEMETRY_PROXY_URL,
  INSTALL_ID_ENVIRONMENT_KEY,
  TELEMETRY_ENABLED_ENVIRONMENT_KEY,
  TELEMETRY_LEVEL_ENVIRONMENT_KEY,
  TELEMETRY_PROXY_URL_ENVIRONMENT_KEY,
)


SAMPLE_REVIEW = """\
Routed to: bill-agent-config-code-review
Review session ID: rvs-20260402-001
Review run ID: rvw-20260402-001
Detected review scope: unstaged changes
Detected stack: agent-config
Signals: README.md, install.sh
Execution mode: inline
Reason: agent-config signals dominate

### 2. Risk Register
- [F-001] Major | High | README.md:12 | README wording is stale after the routing change.
- [F-002] Minor | Medium | install.sh:88 | Installer prompt wording is inconsistent with the new flow.
"""

ZERO_FINDING_REVIEW = """\
Routed to: bill-agent-config-code-review
Review session ID: rvs-20260402-empty
Review run ID: rvw-20260402-empty
Detected review scope: unstaged changes
Detected stack: agent-config
Signals: README.md, install.sh
Execution mode: inline
Reason: agent-config signals dominate

### 2. Risk Register
No findings.
"""

TABLE_FORMAT_A_REVIEW = """\
Routed to: bill-kmp-code-review
Review session ID: rvs-20260402-tbl-a
Review run ID: rvw-20260402-tbl-a
Detected review scope: files
Detected stack: kmp
Signals: commonMain, expect/actual
Execution mode: inline
Reason: kmp signals dominate

## Section 2 — Risk Register

| # | Severity | Category | File | Line(s) | Finding |
|---|----------|----------|------|---------|---------|
| 1 | High | Correctness | ViewModel.kt | 147-152 | init block calls refresh() |
| 2 | Medium | UI | Screen.kt | 156 | Loading indicator not centered |
| 3 | Low | DRY | ScreenDesktop.kt | 496-528 | Duplicates RegularDomainText |
"""

TABLE_FORMAT_B_REVIEW = """\
Routed to: bill-kmp-code-review
Review session ID: rvs-20260402-tbl-b
Review run ID: rvw-20260402-tbl-b
Detected review scope: files
Detected stack: kmp
Signals: commonMain, expect/actual
Execution mode: inline
Reason: kmp signals dominate

## Section 2 — Risk Register

| # | Severity | File | Line(s) | Finding |
|---|----------|------|---------|---------|
| 1 | P1 | Screen.kt | 292 | remember keyed on recreated lambda |
| 2 | P2 | Screen.kt | 311-340 | Single-use data class wrappers add indirection |
"""


class ReviewMetricsTest(unittest.TestCase):
  def test_import_review_creates_run_and_findings(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(SAMPLE_REVIEW, encoding="utf-8")

      result = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(payload["review_session_id"], "rvs-20260402-001")
      self.assertEqual(payload["review_run_id"], "rvw-20260402-001")
      self.assertEqual(payload["finding_count"], 2)
      self.assertEqual(payload["routed_skill"], "bill-agent-config-code-review")

  def test_import_review_allows_zero_findings_and_stats_report_empty_run(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(ZERO_FINDING_REVIEW, encoding="utf-8")

      imported = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(imported["exit_code"], 0, imported["stderr"])
      payload = json.loads(imported["stdout"])
      self.assertEqual(payload["review_session_id"], "rvs-20260402-empty")
      self.assertEqual(payload["review_run_id"], "rvw-20260402-empty")
      self.assertEqual(payload["finding_count"], 0)

      stats = self.run_cli(
        ["--db", str(db_path), "stats", "--run-id", "rvw-20260402-empty", "--format", "json"]
      )

      self.assertEqual(stats["exit_code"], 0, stats["stderr"])
      stats_payload = json.loads(stats["stdout"])
      self.assertEqual(stats_payload["total_findings"], 0)
      self.assertEqual(stats_payload["accepted_findings"], 0)
      self.assertEqual(stats_payload["rejected_findings"], 0)
      self.assertEqual(stats_payload["unresolved_findings"], 0)

  def test_import_table_format_a_with_category_column(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(TABLE_FORMAT_A_REVIEW, encoding="utf-8")

      result = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(payload["review_run_id"], "rvw-20260402-tbl-a")
      self.assertEqual(payload["finding_count"], 3)

      conn = sqlite3.connect(str(db_path))
      conn.row_factory = sqlite3.Row
      findings = conn.execute(
        "SELECT finding_id, severity, confidence, location, description "
        "FROM findings WHERE review_run_id = ? ORDER BY finding_id",
        ("rvw-20260402-tbl-a",),
      ).fetchall()
      conn.close()

      self.assertEqual(len(findings), 3)
      self.assertEqual(findings[0]["finding_id"], "F-001")
      self.assertEqual(findings[0]["severity"], "Major")
      self.assertEqual(findings[0]["confidence"], "Medium")
      self.assertEqual(findings[0]["location"], "ViewModel.kt:147-152")
      self.assertEqual(findings[1]["finding_id"], "F-002")
      self.assertEqual(findings[1]["severity"], "Minor")
      self.assertEqual(findings[2]["finding_id"], "F-003")
      self.assertEqual(findings[2]["severity"], "Minor")

  def test_import_table_format_b_with_priority_severity(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(TABLE_FORMAT_B_REVIEW, encoding="utf-8")

      result = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(payload["review_run_id"], "rvw-20260402-tbl-b")
      self.assertEqual(payload["finding_count"], 2)

      conn = sqlite3.connect(str(db_path))
      conn.row_factory = sqlite3.Row
      findings = conn.execute(
        "SELECT finding_id, severity, confidence, location "
        "FROM findings WHERE review_run_id = ? ORDER BY finding_id",
        ("rvw-20260402-tbl-b",),
      ).fetchall()
      conn.close()

      self.assertEqual(len(findings), 2)
      self.assertEqual(findings[0]["finding_id"], "F-001")
      self.assertEqual(findings[0]["severity"], "Blocker")
      self.assertEqual(findings[0]["confidence"], "Medium")
      self.assertEqual(findings[0]["location"], "Screen.kt:292")
      self.assertEqual(findings[1]["finding_id"], "F-002")
      self.assertEqual(findings[1]["severity"], "Major")
      self.assertEqual(findings[1]["location"], "Screen.kt:311-340")

  def test_bullet_format_takes_priority_over_table_fallback(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(SAMPLE_REVIEW, encoding="utf-8")

      result = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(payload["finding_count"], 2)

      conn = sqlite3.connect(str(db_path))
      conn.row_factory = sqlite3.Row
      findings = conn.execute(
        "SELECT finding_id, confidence FROM findings WHERE review_run_id = ? ORDER BY finding_id",
        ("rvw-20260402-001",),
      ).fetchall()
      conn.close()

      self.assertEqual(findings[0]["finding_id"], "F-001")
      self.assertEqual(findings[0]["confidence"], "High")
      self.assertEqual(findings[1]["finding_id"], "F-002")
      self.assertEqual(findings[1]["confidence"], "Medium")

  def test_record_feedback_and_stats_report_latest_outcomes(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      accepted = self.run_cli(
        [
          "--db",
          str(db_path),
          "record-feedback",
          "--run-id",
          "rvw-20260402-001",
          "--event",
          "finding_accepted",
          "--finding",
          "F-001",
          "--format",
          "json",
        ]
      )
      self.assertEqual(accepted["exit_code"], 0, accepted["stderr"])

      rejected = self.run_cli(
        [
          "--db",
          str(db_path),
          "record-feedback",
          "--run-id",
          "rvw-20260402-001",
          "--event",
          "fix_rejected",
          "--finding",
          "F-002",
          "--note",
          "wording is intentional",
          "--format",
          "json",
        ]
      )
      self.assertEqual(rejected["exit_code"], 0, rejected["stderr"])

      stats = self.run_cli(
        ["--db", str(db_path), "stats", "--run-id", "rvw-20260402-001", "--format", "json"]
      )
      self.assertEqual(stats["exit_code"], 0, stats["stderr"])

      payload = json.loads(stats["stdout"])
      self.assertEqual(payload["total_findings"], 2)
      self.assertEqual(payload["accepted_findings"], 1)
      self.assertEqual(payload["rejected_findings"], 1)
      self.assertEqual(payload["unresolved_findings"], 0)
      self.assertEqual(payload["accepted_rate"], 0.5)
      self.assertEqual(payload["rejected_rate"], 0.5)
      self.assertEqual(payload["latest_outcome_counts"]["finding_accepted"], 1)
      self.assertEqual(payload["latest_outcome_counts"]["fix_rejected"], 1)
      self.assertEqual(payload["accepted_severity_counts"], {"Blocker": 0, "Major": 1, "Minor": 0})
      self.assertEqual(payload["rejected_severity_counts"], {"Blocker": 0, "Major": 0, "Minor": 1})
      self.assertEqual(
        payload["accepted_finding_details"],
        [
          {
            "finding_id": "F-001",
            "severity": "Major",
            "confidence": "High",
            "location": "README.md:12",
            "description": "README wording is stale after the routing change.",
            "outcome_type": "finding_accepted",
          }
        ],
      )
      self.assertEqual(payload["rejected_findings_with_notes"], 1)
      self.assertEqual(
        payload["rejected_finding_details"],
        [
          {
            "finding_id": "F-002",
            "severity": "Minor",
            "confidence": "Medium",
            "location": "install.sh:88",
            "description": "Installer prompt wording is inconsistent with the new flow.",
            "outcome_type": "fix_rejected",
            "note": "wording is intentional",
          }
        ],
      )

  def test_reimport_review_replaces_changed_findings_and_detaches_prior_history(self) -> None:
    updated_review = """\
Routed to: bill-agent-config-code-review
Review session ID: rvs-20260402-001
Review run ID: rvw-20260402-001
Detected review scope: unstaged changes
Detected stack: agent-config
Signals: README.md, install.sh
Execution mode: inline
Reason: agent-config signals dominate

### 2. Risk Register
- [F-001] Minor | Medium | install.sh:88 | Installer prompt wording is inconsistent with the new flow.
"""
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      feedback = self.run_cli(
        [
          "--db",
          str(db_path),
          "record-feedback",
          "--run-id",
          "rvw-20260402-001",
          "--event",
          "fix_rejected",
          "--finding",
          "F-001",
          "--note",
          "README wording updates happen in the docs pass",
          "--format",
          "json",
        ]
      )
      self.assertEqual(feedback["exit_code"], 0, feedback["stderr"])

      learning = self.run_cli(
        [
          "--db",
          str(db_path),
          "learnings",
          "add",
          "--scope",
          "repo",
          "--scope-key",
          "Sermilion/skill-bill",
          "--title",
          "README wording churn after routing changes is expected",
          "--rule",
          "Do not flag README wording as stale after routing changes.",
          "--from-run",
          "rvw-20260402-001",
          "--from-finding",
          "F-001",
          "--format",
          "json",
        ]
      )
      self.assertEqual(learning["exit_code"], 0, learning["stderr"])

      review_path = Path(temp_dir) / "updated-review.txt"
      review_path.write_text(updated_review, encoding="utf-8")
      imported = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )
      self.assertEqual(imported["exit_code"], 0, imported["stderr"])
      self.assertEqual(json.loads(imported["stdout"])["finding_count"], 1)

      with sqlite3.connect(db_path) as connection:
        findings = connection.execute(
          """
          SELECT finding_id, severity, confidence, location, description
          FROM findings
          WHERE review_run_id = ?
          ORDER BY finding_id
          """,
          ("rvw-20260402-001",),
        ).fetchall()
        feedback_count = connection.execute(
          "SELECT COUNT(*) FROM feedback_events WHERE review_run_id = ?",
          ("rvw-20260402-001",),
        ).fetchone()[0]
        learning_source = connection.execute(
          """
          SELECT source_review_run_id, source_finding_id
          FROM learnings
          WHERE id = 1
          """
        ).fetchone()

      self.assertEqual(
        findings,
        [
          (
            "F-001",
            "Minor",
            "Medium",
            "install.sh:88",
            "Installer prompt wording is inconsistent with the new flow.",
          )
        ],
      )
      self.assertEqual(feedback_count, 0)
      self.assertEqual(learning_source, (None, None))

      stats = self.run_cli(
        ["--db", str(db_path), "stats", "--run-id", "rvw-20260402-001", "--format", "json"]
      )
      self.assertEqual(stats["exit_code"], 0, stats["stderr"])
      stats_payload = json.loads(stats["stdout"])
      self.assertEqual(stats_payload["total_findings"], 1)
      self.assertEqual(stats_payload["accepted_findings"], 0)
      self.assertEqual(stats_payload["rejected_findings"], 0)
      self.assertEqual(stats_payload["unresolved_findings"], 1)

  def test_import_review_rejects_missing_review_run_id(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(
        SAMPLE_REVIEW.replace("Review run ID: rvw-20260402-001\n", ""),
        encoding="utf-8",
      )

      result = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(result["exit_code"], 1)
      self.assertIn("Review output is missing 'Review run ID", result["stderr"])

  def test_import_review_rejects_missing_review_session_id(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      review_path = Path(temp_dir) / "review.txt"
      review_path.write_text(
        SAMPLE_REVIEW.replace("Review session ID: rvs-20260402-001\n", ""),
        encoding="utf-8",
      )

      result = self.run_cli(
        ["--db", str(db_path), "import-review", str(review_path), "--format", "json"]
      )

      self.assertEqual(result["exit_code"], 1)
      self.assertIn("Review output is missing 'Review session ID", result["stderr"])

  def test_triage_maps_numbers_to_findings_and_records_notes(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      result = self.run_cli(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "1 fix - keep current terminology",
          "--decision",
          "2 skip - wording is intentional",
          "--format",
          "json",
        ]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(len(payload["recorded"]), 2)
      self.assertEqual(payload["recorded"][0]["finding_id"], "F-001")
      self.assertEqual(payload["recorded"][0]["outcome_type"], "fix_applied")
      self.assertEqual(payload["recorded"][0]["note"], "keep current terminology")
      self.assertEqual(payload["recorded"][1]["finding_id"], "F-002")
      self.assertEqual(payload["recorded"][1]["outcome_type"], "fix_rejected")
      self.assertEqual(payload["recorded"][1]["note"], "wording is intentional")

      with sqlite3.connect(db_path) as connection:
        rows = connection.execute(
          """
          SELECT finding_id, event_type, note
          FROM feedback_events
          ORDER BY id
          """
        ).fetchall()
      self.assertEqual(
        rows,
        [
          ("F-001", "fix_applied", "keep current terminology"),
          ("F-002", "fix_rejected", "wording is intentional"),
        ],
      )

  def test_triage_rejects_unknown_number(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      result = self.run_cli(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "3 fix",
          "--format",
          "json",
        ]
      )

      self.assertEqual(result["exit_code"], 1)
      self.assertIn("Unknown finding number '3'", result["stderr"])

  def test_triage_ignores_separator_only_notes(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      result = self.run_cli(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "1 fix -",
          "--format",
          "json",
        ]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(payload["recorded"][0]["note"], "")

      with sqlite3.connect(db_path) as connection:
        note = connection.execute(
          "SELECT note FROM feedback_events WHERE finding_id = 'F-001'"
        ).fetchone()[0]
      self.assertEqual(note, "")

  def test_triage_accepts_structured_selection_decisions(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      result = self.run_cli(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "fix=[1] reject=[2]",
          "--format",
          "json",
        ]
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertEqual(
        payload["recorded"],
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

      with sqlite3.connect(db_path) as connection:
        rows = connection.execute(
          """
          SELECT finding_id, event_type, note
          FROM feedback_events
          ORDER BY id
          """
        ).fetchall()
      self.assertEqual(
        rows,
        [
          ("F-001", "fix_applied", ""),
          ("F-002", "fix_rejected", ""),
        ],
      )

  def test_learnings_crud_keeps_history_separate_from_feedback(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      self.import_sample_review(db_path, temp_dir)

      triage = self.run_cli(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "2 skip - intentional wording",
          "--format",
          "json",
        ]
      )
      self.assertEqual(triage["exit_code"], 0, triage["stderr"])

      created = self.run_cli(
        [
          "--db",
          str(db_path),
          "learnings",
          "add",
          "--scope",
          "repo",
          "--scope-key",
          "Sermilion/skill-bill",
          "--title",
          "Installer wording is intentionally informal",
          "--rule",
          "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice for CLI tools.",
          "--reason",
          "intentional wording",
          "--from-run",
          "rvw-20260402-001",
          "--from-finding",
          "F-002",
          "--format",
          "json",
        ]
      )
      self.assertEqual(created["exit_code"], 0, created["stderr"])
      created_payload = json.loads(created["stdout"])
      learning_id = created_payload["id"]
      self.assertEqual(created_payload["status"], "active")
      self.assertEqual(created_payload["source_finding_id"], "F-002")

      listed = self.run_cli(
        ["--db", str(db_path), "learnings", "list", "--format", "json"]
      )
      self.assertEqual(listed["exit_code"], 0, listed["stderr"])
      listed_payload = json.loads(listed["stdout"])
      self.assertEqual(len(listed_payload["learnings"]), 1)

      shown = self.run_cli(
        ["--db", str(db_path), "learnings", "show", "--id", str(learning_id), "--format", "json"]
      )
      self.assertEqual(shown["exit_code"], 0, shown["stderr"])
      self.assertEqual(json.loads(shown["stdout"])["title"], "Installer wording is intentionally informal")

      edited = self.run_cli(
        [
          "--db",
          str(db_path),
          "learnings",
          "edit",
          "--id",
          str(learning_id),
          "--title",
          "Installer wording is a deliberate UX choice",
          "--reason",
          "Confirmed by repeated skip feedback on installer wording findings.",
          "--format",
          "json",
        ]
      )
      self.assertEqual(edited["exit_code"], 0, edited["stderr"])
      edited_payload = json.loads(edited["stdout"])
      self.assertEqual(edited_payload["title"], "Installer wording is a deliberate UX choice")

      disabled = self.run_cli(
        ["--db", str(db_path), "learnings", "disable", "--id", str(learning_id), "--format", "json"]
      )
      self.assertEqual(disabled["exit_code"], 0, disabled["stderr"])
      self.assertEqual(json.loads(disabled["stdout"])["status"], "disabled")

      deleted = self.run_cli(
        ["--db", str(db_path), "learnings", "delete", "--id", str(learning_id), "--format", "json"]
      )
      self.assertEqual(deleted["exit_code"], 0, deleted["stderr"])

      with sqlite3.connect(db_path) as connection:
        feedback_count = connection.execute("SELECT COUNT(*) FROM feedback_events").fetchone()[0]
        learning_count = connection.execute("SELECT COUNT(*) FROM learnings").fetchone()[0]
      self.assertEqual(feedback_count, 1)
      self.assertEqual(learning_count, 0)

  def test_learnings_resolve_returns_active_entries_in_scope_precedence_order(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      env = {CONFIG_ENVIRONMENT_KEY: str(config_path)}
      self.import_sample_review(db_path, temp_dir, env=env)

      triage = self.run_cli(
        [
          "--db", str(db_path),
          "triage", "--run-id", "rvw-20260402-001",
          "--decision", "1 reject - README wording is always stale right after a routing change",
          "--decision", "2 reject - installer wording is intentionally informal",
          "--format", "json",
        ],
        env=env,
      )
      self.assertEqual(triage["exit_code"], 0, triage["stderr"])

      global_learning = self.run_cli(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "global",
          "--title", "README wording churn after routing changes is expected",
          "--rule", "Do not flag README wording as stale immediately after a routing change — it gets updated in the next docs pass.",
          "--from-run", "rvw-20260402-001",
          "--from-finding", "F-001",
          "--format", "json",
        ],
        env=env,
      )
      repo_learning = self.run_cli(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "repo",
          "--scope-key", "Sermilion/skill-bill",
          "--title", "Installer prompt wording is intentionally informal",
          "--rule", "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice for CLI tools.",
          "--from-run", "rvw-20260402-001",
          "--from-finding", "F-002",
          "--format", "json",
        ],
        env=env,
      )
      skill_learning = self.run_cli(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "skill",
          "--scope-key", "bill-agent-config-code-review",
          "--title", "Installer wording findings need validator evidence",
          "--rule", "Only flag installer wording when the validator or a contract test also fails — cosmetic wording changes are not actionable in this repo.",
          "--from-run", "rvw-20260402-001",
          "--from-finding", "F-002",
          "--format", "json",
        ],
        env=env,
      )
      disabled_learning = self.run_cli(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "repo",
          "--scope-key", "Sermilion/skill-bill",
          "--title", "Superseded learning that should not resolve",
          "--rule", "This learning was disabled and should never appear in resolve output.",
          "--from-run", "rvw-20260402-001",
          "--from-finding", "F-001",
          "--format", "json",
        ],
        env=env,
      )

      self.assertEqual(global_learning["exit_code"], 0, global_learning["stderr"])
      self.assertEqual(repo_learning["exit_code"], 0, repo_learning["stderr"])
      self.assertEqual(skill_learning["exit_code"], 0, skill_learning["stderr"])
      self.assertEqual(disabled_learning["exit_code"], 0, disabled_learning["stderr"])

      disabled_id = json.loads(disabled_learning["stdout"])["id"]
      disable_result = self.run_cli(
        [
          "--db",
          str(db_path),
          "learnings",
          "disable",
          "--id",
          str(disabled_id),
          "--format",
          "json",
        ],
        env=env,
      )
      self.assertEqual(disable_result["exit_code"], 0, disable_result["stderr"])

      resolved = self.run_cli(
        [
          "--db",
          str(db_path),
          "learnings",
          "resolve",
          "--repo",
          "Sermilion/skill-bill",
          "--skill",
          "bill-agent-config-code-review",
          "--format",
          "json",
        ],
        env=env,
      )
      self.assertEqual(resolved["exit_code"], 0, resolved["stderr"])

      payload = json.loads(resolved["stdout"])
      self.assertEqual(payload["scope_precedence"], ["skill", "repo", "global"])
      self.assertEqual(payload["repo_scope_key"], "Sermilion/skill-bill")
      self.assertEqual(payload["skill_name"], "bill-agent-config-code-review")
      self.assertEqual(
        [entry["scope"] for entry in payload["learnings"]],
        ["skill", "repo", "global"],
      )
      self.assertEqual(
        [entry["reference"] for entry in payload["learnings"]],
        ["L-003", "L-002", "L-001"],
      )
      self.assertEqual(payload["applied_learnings"], "L-003, L-002, L-001")

  def test_telemetry_status_reports_disabled_without_materializing_config(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "status", "--format", "json"],
        env={CONFIG_ENVIRONMENT_KEY: str(config_path)},
      )

      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertFalse(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "off")
      self.assertEqual(payload["sync_target"], "disabled")
      self.assertTrue(payload["remote_configured"])
      self.assertFalse(payload["proxy_configured"])
      self.assertEqual(payload["proxy_url"], DEFAULT_TELEMETRY_PROXY_URL)
      self.assertIsNone(payload["custom_proxy_url"])
      self.assertEqual(payload["pending_events"], 0)
      self.assertFalse(config_path.exists())

  def test_telemetry_disable_removes_config_and_clears_outbox(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
      }

      enabled = self.run_cli(
        ["--db", str(db_path), "telemetry", "enable", "--format", "json"],
        env=env,
      )
      self.assertEqual(enabled["exit_code"], 0, enabled["stderr"])
      self.assertTrue(config_path.exists())

      self.import_and_resolve_sample_review(
        db_path,
        temp_dir,
        env=env,
        block_telemetry_delivery=True,
      )

      connection = sqlite3.connect(db_path)
      try:
        pending_events = connection.execute("SELECT COUNT(*) FROM telemetry_outbox").fetchone()[0]
      finally:
        connection.close()
      self.assertGreater(pending_events, 0)

      disabled = self.run_cli(
        ["--db", str(db_path), "telemetry", "disable", "--format", "json"],
        env=env,
      )
      self.assertEqual(disabled["exit_code"], 0, disabled["stderr"])
      disabled_payload = json.loads(disabled["stdout"])
      self.assertFalse(disabled_payload["telemetry_enabled"])
      self.assertEqual(disabled_payload["cleared_events"], pending_events)
      self.assertFalse(config_path.exists())

      status = self.run_cli(
        ["--db", str(db_path), "telemetry", "status", "--format", "json"],
        env=env,
      )
      self.assertEqual(status["exit_code"], 0, status["stderr"])
      self.assertFalse(json.loads(status["stdout"])["telemetry_enabled"])

      connection = sqlite3.connect(db_path)
      try:
        remaining_events = connection.execute("SELECT COUNT(*) FROM telemetry_outbox").fetchone()[0]
      finally:
        connection.close()
      self.assertEqual(remaining_events, 0)

  def test_telemetry_status_reports_proxy_configuration(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      config_path.write_text(
        json.dumps(
          {
            "install_id": "install-test-123",
            "telemetry": {
              "enabled": True,
              "proxy_url": "https://telemetry.example.dev/ingest",
              "batch_size": 50,
            },
          },
          indent=2,
          sort_keys=True,
        ) + "\n",
        encoding="utf-8",
      )

      status = self.run_cli(
        ["--db", str(db_path), "telemetry", "status", "--format", "json"],
        env={CONFIG_ENVIRONMENT_KEY: str(config_path)},
      )

      self.assertEqual(status["exit_code"], 0, status["stderr"])
      payload = json.loads(status["stdout"])
      self.assertEqual(payload["sync_target"], "custom_proxy")
      self.assertTrue(payload["remote_configured"])
      self.assertTrue(payload["proxy_configured"])
      self.assertEqual(payload["proxy_url"], "https://telemetry.example.dev/ingest")
      self.assertEqual(payload["custom_proxy_url"], "https://telemetry.example.dev/ingest")

  def test_telemetry_review_finished_includes_resolved_learning_details(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      reject_f001 = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "triage", "--run-id", "rvw-20260402-001",
          "--decision", "1 reject - README wording is stale by design during routing changes",
          "--format", "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(reject_f001["exit_code"], 0, reject_f001["stderr"])

      add_learning = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "skill",
          "--scope-key", "bill-agent-config-code-review",
          "--title", "README staleness after routing changes is expected",
          "--rule", "Do not flag README wording as stale after routing changes — it is updated in the next docs pass.",
          "--reason", "README wording is stale by design during routing changes",
          "--from-run", "rvw-20260402-001",
          "--from-finding", "F-001",
          "--format", "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(add_learning["exit_code"], 0, add_learning["stderr"])

      resolve_learning = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "learnings", "resolve",
          "--repo", "private/repo-name",
          "--skill", "bill-agent-config-code-review",
          "--review-session-id", "rvs-20260402-001",
          "--format", "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(resolve_learning["exit_code"], 0, resolve_learning["stderr"])

      accept_f002 = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "triage", "--run-id", "rvw-20260402-001",
          "--decision", "2 accept",
          "--format", "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(accept_f002["exit_code"], 0, accept_f002["stderr"])

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(payload["synced_events"], 1)
      self.assertEqual(len(captured_requests), 1)

      events = captured_requests[0]["batch"]
      self.assertEqual([event["event"] for event in events], ["skillbill_review_finished"])

      finished = events[0]["properties"]
      self.assertEqual(finished["review_session_id"], "rvs-20260402-001")
      self.assertEqual(finished["learnings"]["applied_count"], 1)
      self.assertEqual(finished["learnings"]["applied_references"], ["L-001"])
      self.assertEqual(finished["learnings"]["applied_summary"], "L-001")
      self.assertEqual(finished["learnings"]["scope_counts"], {"global": 0, "repo": 0, "skill": 1})
      self.assertEqual(
        finished["learnings"]["entries"],
        [
          {
            "reference": "L-001",
            "scope": "skill",
          }
        ],
      )

  def test_telemetry_sync_sends_only_to_custom_proxy_when_configured(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_and_resolve_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      captured_requests: list[dict[str, object]] = []
      captured_urls: list[str] = []

      class FakeResponse:
        status = 200

        def __enter__(self):
          return self

        def __exit__(self, exc_type, exc, tb):
          return False

        def getcode(self) -> int:
          return 200

      def fake_urlopen(request, timeout=10):
        captured_urls.append(request.full_url)
        captured_requests.append(json.loads(request.data.decode("utf-8")))
        return FakeResponse()

      sync_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
        sync_result = self.run_cli(
          ["--db", str(db_path), "telemetry", "sync", "--format", "json"],
          env=sync_env,
        )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_target"], "custom_proxy")
      self.assertTrue(payload["remote_configured"])
      self.assertTrue(payload["proxy_configured"])
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)
      self.assertNotIn("api_key", captured_requests[0])
      self.assertIn(
        "skillbill_review_finished",
        [event["event"] for event in captured_requests[0]["batch"]],
      )

  def test_telemetry_sync_fails_when_custom_proxy_delivery_fails(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_and_resolve_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
        failing_urls={"https://telemetry.example.dev/ingest"},
      )

      self.assertEqual(sync_result["exit_code"], 1, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_target"], "custom_proxy")
      self.assertEqual(payload["sync_status"], "failed")
      self.assertEqual(payload["synced_events"], 0)
      self.assertGreater(payload["pending_events"], 0)
      self.assertIn("proxy unavailable", payload["message"])
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)
      self.assertIn(
        "skillbill_review_finished",
        [event["event"] for event in captured_requests[0]["batch"]],
      )

  def test_telemetry_sync_emits_single_review_finished_event_after_review_resolution(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      triage_result = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "1 fix - updated README copy",
          "--decision",
          "2 skip - installer wording is intentional",
          "--format",
          "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(triage_result["exit_code"], 0, triage_result["stderr"])

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_target"], "custom_proxy")
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(payload["synced_events"], 1)
      self.assertEqual(payload["pending_events"], 0)
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)
      self.assertNotIn("api_key", captured_requests[0])

      events = captured_requests[0]["batch"]
      self.assertEqual(
        [event["event"] for event in events],
        ["skillbill_review_finished"],
      )
      finished_event = events[0]
      self.assertEqual(finished_event["distinct_id"], "install-test-123")
      self.assertFalse(finished_event["properties"].get("$process_person_profile", True))
      self.assertNotIn("$insert_id", finished_event["properties"])

      review_summary = finished_event["properties"]
      self.assertEqual(review_summary["review_session_id"], "rvs-20260402-001")
      self.assertNotIn("review_run_id", review_summary)
      self.assertNotIn("review_status", review_summary)
      self.assertNotIn("rejected_findings", review_summary)
      self.assertNotIn("rejected_rate", review_summary)
      self.assertNotIn("rejected_findings_with_notes", review_summary)
      self.assertNotIn("latest_outcome_counts", review_summary)
      self.assertNotIn("unresolved_severity_counts", review_summary)
      self.assertNotIn("rejected_severity_counts", review_summary)
      self.assertNotIn("detected_stack", review_summary)
      self.assertNotIn("applied_learning_count", review_summary)
      self.assertNotIn("applied_learning_references", review_summary)
      self.assertNotIn("applied_learnings", review_summary)
      self.assertNotIn("scope_counts", review_summary)
      self.assertEqual(review_summary["accepted_findings"], 1)
      self.assertEqual(review_summary["unresolved_findings"], 0)
      self.assertTrue(review_summary["review_finished_at"])
      self.assertEqual(review_summary["review_subskills"], [])
      self.assertEqual(
        review_summary["learnings"],
        {
          "applied_count": 0,
          "applied_references": [],
          "applied_summary": "none",
          "scope_counts": {"global": 0, "repo": 0, "skill": 0},
          "entries": [],
        },
      )
      self.assertEqual(
        review_summary["accepted_finding_details"],
        [
          {
            "finding_id": "F-001",
            "severity": "Major",
            "confidence": "High",
            "outcome_type": "fix_applied",
          }
        ],
      )
      self.assertEqual(
        review_summary["rejected_finding_details"],
        [
          {
            "finding_id": "F-002",
            "severity": "Minor",
            "confidence": "Medium",
            "outcome_type": "fix_rejected",
          }
        ],
      )

  def test_telemetry_sync_emits_review_finished_event_after_partial_triage(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      triage_result = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db",
          str(db_path),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "1 fix - updated README copy",
          "--format",
          "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(triage_result["exit_code"], 0, triage_result["stderr"])

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(payload["synced_events"], 1)
      self.assertEqual(payload["pending_events"], 0)
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)

      events = captured_requests[0]["batch"]
      self.assertEqual([event["event"] for event in events], ["skillbill_review_finished"])

      review_summary = events[0]["properties"]
      self.assertEqual(review_summary["accepted_findings"], 1)
      self.assertEqual(review_summary["unresolved_findings"], 1)
      self.assertEqual(review_summary["rejected_finding_details"], [])
      self.assertEqual(
        review_summary["accepted_finding_details"],
        [
          {
            "finding_id": "F-001",
            "severity": "Major",
            "confidence": "High",
            "outcome_type": "fix_applied",
          }
        ],
      )

  def test_telemetry_sync_skips_duplicate_review_finished_event_for_unchanged_reimport(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_and_resolve_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)
      self.import_and_resolve_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(payload["pending_events"], 0)
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)
      all_events = [event["event"] for event in captured_requests[0]["batch"]]
      self.assertEqual(all_events.count("skillbill_review_finished"), 1)

  def test_telemetry_sync_review_finished_uses_latest_retriaged_outcomes(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      accepted_result = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db",
          str(db_path),
          "record-feedback",
          "--run-id",
          "rvw-20260402-001",
          "--event",
          "finding_accepted",
          "--finding",
          "F-001",
          "--format",
          "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(accepted_result["exit_code"], 0, accepted_result["stderr"])

      retriaged_result = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db",
          str(db_path),
          "record-feedback",
          "--run-id",
          "rvw-20260402-001",
          "--event",
          "false_positive",
          "--finding",
          "F-001",
          "--note",
          "rule does not apply to this repo",
          "--format",
          "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(retriaged_result["exit_code"], 0, retriaged_result["stderr"])

      resolved_second_finding = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db",
          str(db_path),
          "record-feedback",
          "--run-id",
          "rvw-20260402-001",
          "--event",
          "false_positive",
          "--finding",
          "F-002",
          "--note",
          "installer wording is intentional",
          "--format",
          "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(resolved_second_finding["exit_code"], 0, resolved_second_finding["stderr"])

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(payload["synced_events"], 1)
      self.assertEqual(payload["sync_target"], "custom_proxy")
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)

      events = captured_requests[0]["batch"]
      self.assertEqual(
        [event["event"] for event in events],
        ["skillbill_review_finished"],
      )

      review_summary = events[0]["properties"]
      self.assertEqual(review_summary["accepted_findings"], 0)
      self.assertEqual(review_summary["accepted_finding_details"], [])
      self.assertEqual(len(review_summary["rejected_finding_details"]), 2)
      self.assertEqual(review_summary["unresolved_findings"], 0)
      self.assertNotIn("latest_outcome_counts", review_summary)
      self.assertEqual(
        review_summary["rejected_finding_details"][0],
        {
          "finding_id": "F-001",
          "severity": "Major",
          "confidence": "High",
          "outcome_type": "false_positive",
        },
      )

  def test_telemetry_sync_ignores_learning_bookkeeping_events(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      enabled_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_sample_review(db_path, temp_dir, env=enabled_env, block_telemetry_delivery=True)

      reject_finding = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "triage", "--run-id", "rvw-20260402-001",
          "--decision", "2 reject - installer wording is intentionally informal",
          "--format", "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(reject_finding["exit_code"], 0, reject_finding["stderr"])

      added = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "repo",
          "--scope-key", "Sermilion/skill-bill",
          "--title", "Installer prompt wording is intentionally informal",
          "--rule", "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice.",
          "--from-run", "rvw-20260402-001",
          "--from-finding", "F-002",
          "--format", "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(added["exit_code"], 0, added["stderr"])
      learning_id = json.loads(added["stdout"])["id"]

      edited = self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db",
          str(db_path),
          "learnings",
          "edit",
          "--id",
          str(learning_id),
          "--reason",
          "Tighten the local guidance wording.",
          "--format",
          "json",
        ],
        env=enabled_env,
      )
      self.assertEqual(edited["exit_code"], 0, edited["stderr"])

      disabled = self.run_cli_with_blocked_telemetry_delivery(
        ["--db", str(db_path), "learnings", "disable", "--id", str(learning_id), "--format", "json"],
        env=enabled_env,
      )
      self.assertEqual(disabled["exit_code"], 0, disabled["stderr"])

      deleted = self.run_cli_with_blocked_telemetry_delivery(
        ["--db", str(db_path), "learnings", "delete", "--id", str(learning_id), "--format", "json"],
        env=enabled_env,
      )
      self.assertEqual(deleted["exit_code"], 0, deleted["stderr"])

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )

      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      payload = json.loads(sync_result["stdout"])
      self.assertEqual(payload["sync_status"], "synced")
      self.assertEqual(payload["synced_events"], 1)
      self.assertEqual(payload["pending_events"], 0)
      self.assertEqual(captured_urls, ["https://telemetry.example.dev/ingest"])
      self.assertEqual(len(captured_requests), 1)
      self.assertEqual(
        [event["event"] for event in captured_requests[0]["batch"]],
        ["skillbill_review_finished"],
      )

  def test_telemetry_set_level_command(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      env = {CONFIG_ENVIRONMENT_KEY: str(config_path)}

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "set-level", "full", "--format", "json"],
        env=env,
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertTrue(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "full")

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "set-level", "anonymous", "--format", "json"],
        env=env,
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertTrue(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "anonymous")

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "set-level", "off", "--format", "json"],
        env=env,
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertFalse(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "off")

  def test_telemetry_enable_with_level_flag(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      env = {CONFIG_ENVIRONMENT_KEY: str(config_path)}

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "enable", "--level", "full", "--format", "json"],
        env=env,
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertTrue(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "full")

  def test_telemetry_level_backward_compat_enabled_true_maps_to_anonymous(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      config_path.write_text(
        json.dumps({
          "install_id": "install-test-123",
          "telemetry": {"enabled": True, "proxy_url": "", "batch_size": 50},
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
      )

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "status", "--format", "json"],
        env={CONFIG_ENVIRONMENT_KEY: str(config_path)},
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertTrue(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "anonymous")

  def test_telemetry_level_backward_compat_enabled_false_maps_to_off(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      config_path.write_text(
        json.dumps({
          "install_id": "install-test-123",
          "telemetry": {"enabled": False, "proxy_url": "", "batch_size": 50},
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
      )

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "status", "--format", "json"],
        env={CONFIG_ENVIRONMENT_KEY: str(config_path)},
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertFalse(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "off")

  def test_telemetry_level_env_var_overrides_config(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      config_path.write_text(
        json.dumps({
          "install_id": "install-test-123",
          "telemetry": {"level": "anonymous", "proxy_url": "", "batch_size": 50},
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
      )

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "status", "--format", "json"],
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_LEVEL_ENVIRONMENT_KEY: "full",
        },
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertTrue(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "full")

  def test_telemetry_level_env_var_takes_precedence_over_legacy_enabled_env(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"

      result = self.run_cli(
        ["--db", str(db_path), "telemetry", "enable", "--format", "json"],
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_LEVEL_ENVIRONMENT_KEY: "off",
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )
      self.assertEqual(result["exit_code"], 0, result["stderr"])
      payload = json.loads(result["stdout"])
      self.assertFalse(payload["telemetry_enabled"])
      self.assertEqual(payload["telemetry_level"], "off")

  def test_telemetry_full_level_payload_includes_finding_details(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      full_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_LEVEL_ENVIRONMENT_KEY: "full",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_and_resolve_sample_review(
        db_path, temp_dir, env=full_env, block_telemetry_delivery=True,
      )

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_LEVEL_ENVIRONMENT_KEY: "full",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )
      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      events = captured_requests[0]["batch"]
      finished = events[0]["properties"]

      accepted = finished["accepted_finding_details"]
      self.assertTrue(len(accepted) > 0)
      self.assertIn("description", accepted[0])
      self.assertIn("location", accepted[0])

      rejected = finished["rejected_finding_details"]
      self.assertTrue(len(rejected) > 0)
      self.assertIn("description", rejected[0])
      self.assertIn("location", rejected[0])

  def test_telemetry_anonymous_level_payload_redacts_finding_details(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      anon_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_and_resolve_sample_review(
        db_path, temp_dir, env=anon_env, block_telemetry_delivery=True,
      )

      sync_result, captured_urls, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_ENABLED_ENVIRONMENT_KEY: "true",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )
      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      events = captured_requests[0]["batch"]
      finished = events[0]["properties"]

      accepted = finished["accepted_finding_details"]
      self.assertTrue(len(accepted) > 0)
      self.assertNotIn("description", accepted[0])
      self.assertNotIn("location", accepted[0])

  def test_telemetry_full_level_payload_includes_learning_content(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      db_path = Path(temp_dir) / "metrics.db"
      config_path = Path(temp_dir) / "config.json"
      full_env = {
        CONFIG_ENVIRONMENT_KEY: str(config_path),
        TELEMETRY_LEVEL_ENVIRONMENT_KEY: "full",
        INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
      }
      self.import_sample_review(db_path, temp_dir, env=full_env, block_telemetry_delivery=True)

      self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "triage", "--run-id", "rvw-20260402-001",
          "--decision", "1 reject - stale by design",
          "--format", "json",
        ],
        env=full_env,
      )
      self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "learnings", "add",
          "--scope", "global", "--title", "README staleness expected",
          "--rule", "Do not flag README as stale during routing changes.",
          "--from-run", "rvw-20260402-001", "--from-finding", "F-001",
          "--format", "json",
        ],
        env=full_env,
      )
      self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "learnings", "resolve",
          "--review-session-id", "rvs-20260402-001",
          "--format", "json",
        ],
        env=full_env,
      )
      self.run_cli_with_blocked_telemetry_delivery(
        [
          "--db", str(db_path),
          "triage", "--run-id", "rvw-20260402-001",
          "--decision", "2 accept",
          "--format", "json",
        ],
        env=full_env,
      )

      sync_result, _, captured_requests = self.sync_with_capture(
        db_path,
        config_path,
        env={
          CONFIG_ENVIRONMENT_KEY: str(config_path),
          TELEMETRY_LEVEL_ENVIRONMENT_KEY: "full",
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY: "https://telemetry.example.dev/ingest",
          INSTALL_ID_ENVIRONMENT_KEY: "install-test-123",
        },
      )
      self.assertEqual(sync_result["exit_code"], 0, sync_result["stderr"])
      events = captured_requests[0]["batch"]
      finished = events[0]["properties"]

      entries = finished["learnings"]["entries"]
      self.assertTrue(len(entries) > 0)
      self.assertIn("title", entries[0])
      self.assertIn("rule_text", entries[0])

  def test_config_migration_replaces_enabled_with_level(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      config_path = Path(temp_dir) / "config.json"
      config_path.write_text(
        json.dumps({
          "install_id": "install-test-123",
          "telemetry": {"enabled": True, "proxy_url": "", "batch_size": 50},
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
      )
      db_path = Path(temp_dir) / "metrics.db"

      self.run_cli(
        ["--db", str(db_path), "telemetry", "enable", "--format", "json"],
        env={CONFIG_ENVIRONMENT_KEY: str(config_path)},
      )

      migrated = json.loads(config_path.read_text(encoding="utf-8"))
      self.assertIn("level", migrated["telemetry"])
      self.assertNotIn("enabled", migrated["telemetry"])

  def import_sample_review(
    self,
    db_path: Path,
    temp_dir: str,
    env: dict[str, str] | None = None,
    *,
    block_telemetry_delivery: bool = False,
  ) -> None:
    review_path = Path(temp_dir) / "review.txt"
    review_path.write_text(SAMPLE_REVIEW, encoding="utf-8")
    run_cli = self.run_cli_with_blocked_telemetry_delivery if block_telemetry_delivery else self.run_cli
    result = run_cli(
      ["--db", str(db_path), "import-review", str(review_path), "--format", "json"],
      env=env,
    )
    self.assertEqual(result["exit_code"], 0, result["stderr"])

  def import_zero_finding_review(
    self,
    db_path: Path,
    temp_dir: str,
    env: dict[str, str] | None = None,
    *,
    block_telemetry_delivery: bool = False,
  ) -> None:
    review_path = Path(temp_dir) / "review.txt"
    review_path.write_text(ZERO_FINDING_REVIEW, encoding="utf-8")
    run_cli = self.run_cli_with_blocked_telemetry_delivery if block_telemetry_delivery else self.run_cli
    result = run_cli(
      ["--db", str(db_path), "import-review", str(review_path), "--format", "json"],
      env=env,
    )
    self.assertEqual(result["exit_code"], 0, result["stderr"])

  def import_and_resolve_sample_review(
    self,
    db_path: Path,
    temp_dir: str,
    env: dict[str, str] | None = None,
    *,
    block_telemetry_delivery: bool = False,
  ) -> None:
    self.import_sample_review(db_path, temp_dir, env=env, block_telemetry_delivery=block_telemetry_delivery)
    run_cli = self.run_cli_with_blocked_telemetry_delivery if block_telemetry_delivery else self.run_cli
    result = run_cli(
      [
        "--db", str(db_path),
        "triage", "--run-id", "rvw-20260402-001",
        "--decision", "1 fix",
        "--decision", "2 skip",
        "--format", "json",
      ],
      env=env,
    )
    self.assertEqual(result["exit_code"], 0, result["stderr"])

  def sync_with_capture(
    self,
    db_path: Path,
    config_path: Path,
    *,
    env: dict[str, str],
    failing_urls: set[str] | None = None,
  ) -> tuple[dict[str, str | int], list[str], list[dict[str, object]]]:
    captured_requests: list[dict[str, object]] = []
    captured_urls: list[str] = []
    failing_urls = failing_urls or set()

    class FakeResponse:
      status = 200

      def __enter__(self):
        return self

      def __exit__(self, exc_type, exc, tb):
        return False

      def getcode(self) -> int:
        return 200

    def fake_urlopen(request, timeout=10):
      captured_urls.append(request.full_url)
      captured_requests.append(json.loads(request.data.decode("utf-8")))
      if request.full_url in failing_urls:
        raise urllib.error.URLError("proxy unavailable")
      return FakeResponse()

    with patch.object(urllib.request, "urlopen", side_effect=fake_urlopen):
      sync_result = self.run_cli(
        ["--db", str(db_path), "telemetry", "sync", "--format", "json"],
        env=env,
      )

    return sync_result, captured_urls, captured_requests

  def run_cli_with_blocked_telemetry_delivery(
    self,
    argv: list[str],
    env: dict[str, str] | None = None,
  ) -> dict[str, str | int]:
    with patch.object(
      urllib.request,
      "urlopen",
      side_effect=urllib.error.URLError("telemetry blocked in test setup"),
    ):
      return self.run_cli(argv, env=env)

  def run_cli(self, argv: list[str], env: dict[str, str] | None = None) -> dict[str, str | int]:
    stdout = io.StringIO()
    stderr = io.StringIO()
    patched_env = {
      key: value
      for key, value in os.environ.items()
      if not key.startswith("SKILL_BILL_")
    }
    if CONFIG_ENVIRONMENT_KEY not in (env or {}):
      patched_env[CONFIG_ENVIRONMENT_KEY] = os.path.join(
        tempfile.gettempdir(), f"skill-bill-test-config-{os.getpid()}.json"
      )
    patched_env.update(env or {})
    with patch.dict(os.environ, patched_env, clear=True):
      with redirect_stdout(stdout), redirect_stderr(stderr):
        exit_code = main(argv)
    return {
      "exit_code": exit_code,
      "stdout": stdout.getvalue(),
      "stderr": stderr.getvalue(),
    }


class ReviewOrchestratedRetrofitTest(unittest.TestCase):
  """MCP-level coverage for import_review / triage_findings orchestrated=True."""

  def setUp(self) -> None:
    import shutil as _shutil
    self._shutil = _shutil
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.config_path = os.path.join(self.temp_dir, "config.json")
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "anonymous", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": self.db_path,
      "SKILL_BILL_CONFIG_PATH": self.config_path,
      "SKILL_BILL_TELEMETRY_ENABLED": "true",
      "SKILL_BILL_INSTALL_ID": "test-install-id",
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
    self._shutil.rmtree(self.temp_dir, ignore_errors=True)

  def _outbox_rows(self, event_name: str) -> list[sqlite3.Row]:
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
      "SELECT * FROM telemetry_outbox WHERE event_name = ?",
      (event_name,),
    ).fetchall()
    conn.close()
    return rows

  def test_import_review_orchestrated_marks_row_and_suppresses_zero_finding_emit(self) -> None:
    from skill_bill.mcp_server import import_review

    result = import_review(review_text=ZERO_FINDING_REVIEW, orchestrated=True)
    self.assertEqual(result["mode"], "orchestrated")
    # Review had zero findings so the review lifecycle auto-resolves and the
    # payload is returned instead of enqueued.
    self.assertIn("telemetry_payload", result)
    self.assertEqual(result["telemetry_payload"]["skill"], "bill-code-review")
    rows = self._outbox_rows("skillbill_review_finished")
    self.assertEqual(len(rows), 0)

    # Row is persisted locally and marked orchestrated.
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    run_row = conn.execute(
      "SELECT orchestrated_run FROM review_runs WHERE review_run_id = ?",
      ("rvw-20260402-empty",),
    ).fetchone()
    conn.close()
    self.assertIsNotNone(run_row)
    self.assertEqual(int(run_row["orchestrated_run"]), 1)

  def test_triage_findings_orchestrated_returns_payload_and_does_not_emit(self) -> None:
    from skill_bill.mcp_server import import_review, triage_findings

    import_result = import_review(review_text=SAMPLE_REVIEW, orchestrated=True)
    self.assertEqual(import_result["mode"], "orchestrated")
    # Two findings, still unresolved -> no payload yet
    self.assertNotIn("telemetry_payload", import_result)

    triage_result = triage_findings(
      review_run_id="rvw-20260402-001",
      decisions=["fix=[1,2]"],
      orchestrated=True,
    )
    self.assertEqual(triage_result["mode"], "orchestrated")
    self.assertIn("telemetry_payload", triage_result)
    self.assertEqual(triage_result["telemetry_payload"]["skill"], "bill-code-review")
    self.assertEqual(triage_result["telemetry_payload"]["total_findings"], 2)

    # No outbox emission from any of these calls.
    rows = self._outbox_rows("skillbill_review_finished")
    self.assertEqual(len(rows), 0)

  def test_standalone_still_emits_when_flag_missing(self) -> None:
    from skill_bill.mcp_server import import_review, triage_findings

    import_review(review_text=SAMPLE_REVIEW)
    triage_findings(
      review_run_id="rvw-20260402-001",
      decisions=["fix=[1,2]"],
    )
    # Graceful default: children emit when orchestrated flag is missing.
    rows = self._outbox_rows("skillbill_review_finished")
    self.assertEqual(len(rows), 1)


if __name__ == "__main__":
  unittest.main()
