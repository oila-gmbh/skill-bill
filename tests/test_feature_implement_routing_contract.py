from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def read(relative_path: str) -> str:
  return (ROOT / relative_path).read_text(encoding="utf-8")


FEATURE_IMPLEMENT = read("skills/base/bill-feature-implement/SKILL.md")
CODE_REVIEW = read("skills/base/bill-code-review/SKILL.md")
QUALITY_CHECK = read("skills/base/bill-quality-check/SKILL.md")
KOTLIN_CODE_REVIEW = read("skills/kotlin/bill-kotlin-code-review/SKILL.md")
BACKEND_KOTLIN_CODE_REVIEW = read("skills/backend-kotlin/bill-backend-kotlin-code-review/SKILL.md")
KMP_CODE_REVIEW = read("skills/kmp/bill-kmp-code-review/SKILL.md")
PHP_CODE_REVIEW = read("skills/php/bill-php-code-review/SKILL.md")
GO_CODE_REVIEW = read("skills/go/bill-go-code-review/SKILL.md")


class FeatureImplementRoutingContractTest(unittest.TestCase):
  def test_feature_implement_invokes_shared_review_and_validation_routers(self) -> None:
    self.assertIn("Run the `bill-code-review` skill", FEATURE_IMPLEMENT)
    self.assertIn("run `bill-quality-check`", FEATURE_IMPLEMENT)
    self.assertIn("`bill-code-review`", FEATURE_IMPLEMENT)
    self.assertIn("`bill-quality-check`", FEATURE_IMPLEMENT)

  def test_kotlin_context_routes_to_kotlin_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `kotlin` signals dominate without meaningful `kmp` or `backend-kotlin` markers, delegate to `bill-kotlin-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `kotlin` signals dominate, delegate to the canonical `bill-kotlin-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )

  def test_backend_kotlin_context_routes_to_backend_review_and_current_quality_check(self) -> None:
    self.assertIn(
      "- If `backend-kotlin` signals dominate, delegate to `bill-backend-kotlin-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `backend-kotlin` signals dominate, delegate to the canonical quality-check implementation for the `backend-kotlin` package when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Today, until separate `kmp` and `backend-kotlin` quality-check implementations exist, route `kmp`, `backend-kotlin`, and `kotlin` work to `bill-kotlin-quality-check`.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "### Step 1: Run `bill-kotlin-code-review` as the baseline review",
      BACKEND_KOTLIN_CODE_REVIEW,
    )

  def test_kmp_context_routes_to_kmp_review_and_current_quality_check(self) -> None:
    self.assertIn(
      "- If `kmp` signals dominate, delegate to `bill-kmp-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `kmp` signals dominate, delegate to the canonical quality-check implementation for the `kmp` package when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Today, until separate `kmp` and `backend-kotlin` quality-check implementations exist, route `kmp`, `backend-kotlin`, and `kotlin` work to `bill-kotlin-quality-check`.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "- Otherwise use `bill-kotlin-code-review`",
      KMP_CODE_REVIEW,
    )

  def test_php_context_routes_to_php_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `php` signals dominate, delegate to `bill-php-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `php` signals dominate, delegate to the canonical `bill-php-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "read `orchestration/stack-routing/PLAYBOOK.md`",
      PHP_CODE_REVIEW,
    )

  def test_go_context_routes_to_go_review_and_quality_check(self) -> None:
    self.assertIn(
      "- If `go` signals dominate, delegate to `bill-go-code-review`.",
      CODE_REVIEW,
    )
    self.assertIn(
      "- If `go` signals dominate, delegate to the canonical `bill-go-quality-check` skill when it exists.",
      QUALITY_CHECK,
    )
    self.assertIn(
      "read `orchestration/stack-routing/PLAYBOOK.md`",
      GO_CODE_REVIEW,
    )

  def test_kmp_plus_backend_context_uses_backend_baseline_inside_kmp_review(self) -> None:
    self.assertIn(
      "- If backend/server files are also touched, choose `bill-backend-kotlin-code-review` as the baseline review layer so backend coverage is preserved before this skill adds mobile-specific specialists.",
      KMP_CODE_REVIEW,
    )
    self.assertIn(
      "- Use `bill-backend-kotlin-code-review` when backend/server files or markers are meaningfully in scope",
      KMP_CODE_REVIEW,
    )
    self.assertIn(
      "- Otherwise use `bill-kotlin-code-review`",
      KMP_CODE_REVIEW,
    )

  def test_kotlin_baseline_refuses_to_pretend_it_is_full_backend_or_kmp_review(self) -> None:
    self.assertIn(
      "- If strong Android/KMP markers are present and this skill is invoked standalone, clearly say that `bill-kmp-code-review` is required for full Android/KMP coverage.",
      KOTLIN_CODE_REVIEW,
    )
    self.assertIn(
      "- If backend/server signals clearly dominate and this skill is invoked standalone, delegate to `bill-backend-kotlin-code-review` and stop instead of pretending this baseline layer is the full backend review.",
      KOTLIN_CODE_REVIEW,
    )


if __name__ == "__main__":
  unittest.main()
