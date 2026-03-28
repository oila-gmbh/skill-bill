from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "validate_release_ref.py"
sys.path.insert(0, str(ROOT / "scripts"))

from validate_release_ref import parse_release_ref  # noqa: E402


class ValidateReleaseRefTest(unittest.TestCase):
  def test_accepts_stable_semver_tag(self) -> None:
    release_ref = parse_release_ref("v0.4.0")

    self.assertEqual(release_ref.tag, "v0.4.0")
    self.assertEqual(release_ref.version, "0.4.0")
    self.assertFalse(release_ref.prerelease)

  def test_accepts_tag_ref_and_marks_prerelease(self) -> None:
    release_ref = parse_release_ref("refs/tags/v0.5.0-rc.1")

    self.assertEqual(release_ref.tag, "v0.5.0-rc.1")
    self.assertEqual(release_ref.version, "0.5.0-rc.1")
    self.assertTrue(release_ref.prerelease)

  def test_rejects_invalid_tag(self) -> None:
    with self.assertRaisesRegex(ValueError, "Release tag must match"):
      parse_release_ref("release-0.4.0")

  def test_cli_writes_github_output(self) -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
      github_output = Path(temp_dir) / "github-output.txt"
      result = subprocess.run(
        [
          "python3",
          str(SCRIPT_PATH),
          "v1.2.3-rc.1",
          "--github-output",
          str(github_output),
        ],
        capture_output=True,
        text=True,
        check=False,
      )

      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
      self.assertEqual(
        json.loads(result.stdout),
        {
          "tag": "v1.2.3-rc.1",
          "version": "1.2.3-rc.1",
          "prerelease": True,
        },
      )
      self.assertEqual(
        github_output.read_text(encoding="utf-8"),
        "tag=v1.2.3-rc.1\nversion=1.2.3-rc.1\nprerelease=true\n",
      )


if __name__ == "__main__":
  unittest.main()
