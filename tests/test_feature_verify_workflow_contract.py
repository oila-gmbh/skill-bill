from __future__ import annotations

from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

VERIFY_SKILL = (ROOT / "skills" / "bill-feature-verify" / "SKILL.md").read_text(encoding="utf-8")
VERIFY_CONTENT = (ROOT / "skills" / "bill-feature-verify" / "content.md").read_text(encoding="utf-8")
VERIFY_COMBINED = VERIFY_SKILL + "\n" + VERIFY_CONTENT


class FeatureVerifyWorkflowContractTest(unittest.TestCase):

  def test_skill_uses_verify_workflow_state_tools(self) -> None:
    self.assertIn("## Execution", VERIFY_SKILL)
    self.assertIn("[content.md](content.md)", VERIFY_SKILL)
    self.assertIn("audit-rubrics.md", VERIFY_SKILL)
    self.assertIn("telemetry-contract.md", VERIFY_SKILL)
    self.assertIn("shell-ceremony.md", VERIFY_SKILL)
    self.assertIn("## Workflow State", VERIFY_CONTENT)
    self.assertIn("## Continuation Mode", VERIFY_CONTENT)
    self.assertIn("feature_verify_workflow_open", VERIFY_CONTENT)
    self.assertIn("feature_verify_workflow_update", VERIFY_CONTENT)
    self.assertIn("feature_verify_workflow_get", VERIFY_CONTENT)
    self.assertIn("feature_verify_workflow_continue", VERIFY_CONTENT)

  def test_skill_names_stable_verify_workflow_artifacts(self) -> None:
    self.assertIn("`input_context`", VERIFY_CONTENT)
    self.assertIn("`criteria_summary`", VERIFY_CONTENT)
    self.assertIn("`diff_summary`", VERIFY_CONTENT)
    self.assertIn("`review_result`", VERIFY_CONTENT)
    self.assertIn("`completeness_audit_result`", VERIFY_CONTENT)
    self.assertIn("`verdict_result`", VERIFY_CONTENT)

  def test_content_holds_execution_body(self) -> None:
    self.assertIn("## Step 1: Collect Inputs", VERIFY_CONTENT)
    self.assertIn("## Step 2: Extract Acceptance Criteria", VERIFY_CONTENT)
    self.assertIn("## Step 7: Consolidated Verdict", VERIFY_CONTENT)
    self.assertNotIn("## Workflow State", VERIFY_SKILL)
    self.assertNotIn("## Continuation Mode", VERIFY_SKILL)


if __name__ == "__main__":
  unittest.main()
