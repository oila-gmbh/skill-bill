from __future__ import annotations

import json
from pathlib import Path
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.mcp_server import pr_description_generated


PARAMS = {
  "commit_count": 3,
  "files_changed_count": 12,
  "was_edited_by_user": False,
  "pr_created": True,
  "pr_title": "feat: [SKILL-9] add telemetry orchestration contract",
}


class PrDescriptionEnabledTest(unittest.TestCase):

  def setUp(self) -> None:
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
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def _outbox_rows(self, event_name: str) -> list[sqlite3.Row]:
    if not os.path.exists(self.db_path):
      return []
    conn = sqlite3.connect(self.db_path)
    conn.row_factory = sqlite3.Row
    try:
      rows = conn.execute(
        "SELECT * FROM telemetry_outbox WHERE event_name = ?",
        (event_name,),
      ).fetchall()
    except sqlite3.OperationalError:
      rows = []
    conn.close()
    return rows

  def test_standalone_emits_event(self) -> None:
    result = pr_description_generated(**PARAMS)
    self.assertEqual(result["status"], "ok")
    self.assertRegex(result["session_id"], r"^prd-\d{8}-\d{6}-[a-z0-9]{4}$")
    rows = self._outbox_rows("skillbill_pr_description_generated")
    self.assertEqual(len(rows), 1)
    payload = json.loads(rows[0]["payload_json"])
    self.assertEqual(payload["commit_count"], 3)
    self.assertEqual(payload["files_changed_count"], 12)
    self.assertFalse(payload["was_edited_by_user"])
    self.assertTrue(payload["pr_created"])
    # anonymous level: pr_title is excluded
    self.assertNotIn("pr_title", payload)

  def test_full_level_includes_pr_title(self) -> None:
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "full", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    os.environ["SKILL_BILL_TELEMETRY_LEVEL"] = "full"
    try:
      pr_description_generated(**PARAMS)
      rows = self._outbox_rows("skillbill_pr_description_generated")
      payload = json.loads(rows[0]["payload_json"])
      self.assertEqual(payload["pr_title"], PARAMS["pr_title"])
    finally:
      os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)

  def test_orchestrated_returns_payload_and_does_not_emit(self) -> None:
    result = pr_description_generated(orchestrated=True, **PARAMS)
    self.assertEqual(result["mode"], "orchestrated")
    payload = result["telemetry_payload"]
    self.assertEqual(payload["skill"], "bill-pr-description")
    self.assertEqual(payload["commit_count"], 3)
    self.assertTrue(payload["pr_created"])
    self.assertNotIn("session_id", payload)
    rows = self._outbox_rows("skillbill_pr_description_generated")
    self.assertEqual(len(rows), 0)

  def test_orchestrated_full_level_includes_pr_title(self) -> None:
    Path(self.config_path).write_text(
      json.dumps({
        "install_id": "test-install-id",
        "telemetry": {"level": "full", "proxy_url": "", "batch_size": 50},
      }),
      encoding="utf-8",
    )
    os.environ["SKILL_BILL_TELEMETRY_LEVEL"] = "full"
    try:
      result = pr_description_generated(orchestrated=True, **PARAMS)
      payload = result["telemetry_payload"]
      self.assertEqual(payload["pr_title"], PARAMS["pr_title"])
    finally:
      os.environ.pop("SKILL_BILL_TELEMETRY_LEVEL", None)


class PrDescriptionDisabledTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self._original_env: dict[str, str | None] = {}
    env_overrides = {
      "SKILL_BILL_REVIEW_DB": os.path.join(self.temp_dir, "metrics.db"),
      "SKILL_BILL_CONFIG_PATH": os.path.join(self.temp_dir, "config.json"),
      "SKILL_BILL_TELEMETRY_ENABLED": "false",
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

  def test_standalone_skips_when_disabled(self) -> None:
    result = pr_description_generated(**PARAMS)
    self.assertEqual(result["status"], "skipped")


if __name__ == "__main__":
  unittest.main()
