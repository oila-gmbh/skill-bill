"""Regression coverage for #43: test runs must never reach the production relay.

`auto_sync_telemetry` flushes pending outbox rows on every `open_db` close. If a
telemetry-enabled test fixture leaves `SKILL_BILL_TELEMETRY_PROXY_URL` unset,
the sync falls back to `DEFAULT_TELEMETRY_PROXY_URL` and ships synthetic test
events into PostHog, polluting funnels under `distinct_id=test-install-id`.

This test installs a network sentinel that records every `urlopen` attempt and
asserts:
  1. The configured proxy URL never resolves to the hosted relay host.
  2. Any HTTP attempt that does fire targets the loopback address — never an
     external host.

It exercises the same code path the issue described: telemetry enabled, no
custom proxy URL set in config, then `open_db` closes and triggers auto sync.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill import sync as sync_module
from skill_bill.constants import DEFAULT_TELEMETRY_PROXY_URL
from skill_bill.mcp_server import feature_implement_started


STARTED_PARAMS = {
  "feature_size": "SMALL",
  "acceptance_criteria_count": 2,
  "open_questions_count": 0,
  "spec_input_types": ["markdown_file"],
  "spec_word_count": 200,
  "rollout_needed": False,
  "feature_name": "isolation-test",
  "issue_key": "",
  "issue_key_type": "none",
  "spec_summary": "Network isolation regression",
}


class TelemetryNetworkIsolationTest(unittest.TestCase):

  def setUp(self) -> None:
    self.temp_dir = tempfile.mkdtemp()
    self.db_path = os.path.join(self.temp_dir, "metrics.db")
    self.config_path = os.path.join(self.temp_dir, "config.json")
    # Intentionally leave proxy_url empty in the config — we want to prove the
    # env override is what keeps the sync off the production host even when the
    # config itself does not steer us away from it.
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
      "SKILL_BILL_TELEMETRY_PROXY_URL": "http://127.0.0.1:0",
    }
    for key, value in env_overrides.items():
      self._original_env[key] = os.environ.get(key)
      os.environ[key] = value

    self._urlopen_calls: list[str] = []
    self._original_urlopen = urllib.request.urlopen
    self._original_sync_urlopen = sync_module.urllib.request.urlopen

    def sentinel_urlopen(request, *args, **kwargs):
      url = request.full_url if hasattr(request, "full_url") else str(request)
      self._urlopen_calls.append(url)
      # Simulate connection refused so auto_sync_telemetry's exception handler
      # marks the rows as failed instead of hanging on a real socket.
      raise urllib.error.URLError("blocked by network isolation sentinel")

    urllib.request.urlopen = sentinel_urlopen
    sync_module.urllib.request.urlopen = sentinel_urlopen

  def tearDown(self) -> None:
    urllib.request.urlopen = self._original_urlopen
    sync_module.urllib.request.urlopen = self._original_sync_urlopen
    for key, value in self._original_env.items():
      if value is None:
        os.environ.pop(key, None)
      else:
        os.environ[key] = value
    shutil.rmtree(self.temp_dir, ignore_errors=True)

  def test_sync_target_is_loopback_not_hosted_relay(self) -> None:
    # Trigger the same lifecycle production tools follow: emit an event, then
    # let auto sync attempt to flush the outbox.
    feature_implement_started(**STARTED_PARAMS)
    sync_module.auto_sync_telemetry(Path(self.db_path))

    self.assertGreater(
      len(self._urlopen_calls),
      0,
      "Expected the auto-sync path to attempt at least one HTTP request so "
      "the sentinel could observe it. If this fires, the sync codepath has "
      "moved and this regression test needs to follow.",
    )
    hosted_host = urlsplit(DEFAULT_TELEMETRY_PROXY_URL).hostname
    for url in self._urlopen_calls:
      host = urlsplit(url).hostname
      self.assertNotEqual(
        host,
        hosted_host,
        f"Test telemetry attempted outbound HTTP to the production relay: {url}",
      )
      self.assertIn(
        host,
        ("127.0.0.1", "localhost", "::1"),
        f"Test telemetry attempted outbound HTTP to a non-loopback host: {url}",
      )


if __name__ == "__main__":
  unittest.main()
