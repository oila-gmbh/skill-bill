"""Smoke test: end-to-end review pipeline against a toy codebase.

Creates a small toy project with deliberate issues, constructs a review
output as if bill-code-review was run against it, then verifies the full
import → triage → stats pipeline round-trips correctly and findings
reference actual files in the codebase.
"""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from skill_bill.cli import main  # noqa: E402
from skill_bill.constants import CONFIG_ENVIRONMENT_KEY  # noqa: E402
from skill_bill.review import parse_review  # noqa: E402


# ---------------------------------------------------------------------------
# Toy codebase: a small Kotlin backend project with 3 deliberate bugs
# ---------------------------------------------------------------------------

TOY_FILES: dict[str, str] = {
    "build.gradle.kts": """\
plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.spring") version "1.9.22"
    id("org.springframework.boot") version "3.2.2"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")
    runtimeOnly("org.postgresql:postgresql:42.7.1")
}
""",
    "src/main/kotlin/com/example/UserController.kt": """\
package com.example

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: String): User {
        // BUG: no input validation on id — SQL injection vector
        return userService.findById(id)
    }

    @PostMapping
    fun createUser(@RequestBody body: Map<String, Any>): User {
        val name = body["name"] as String
        val email = body["email"] as String
        // BUG: unchecked cast — ClassCastException if fields are missing
        return userService.create(name, email)
    }

    @DeleteMapping("/{id}")
    fun deleteUser(@PathVariable id: String) {
        userService.delete(id)
        // BUG: no authorization check — any caller can delete any user
    }
}
""",
    "src/main/kotlin/com/example/UserService.kt": """\
package com.example

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class UserService {

    fun findById(id: String): User = transaction {
        val row = Users.select { Users.id eq id }.single()
        User(row[Users.id], row[Users.name], row[Users.email])
    }

    fun create(name: String, email: String): User = transaction {
        val id = Users.insert {
            it[Users.name] = name
            it[Users.email] = email
        } get Users.id
        User(id, name, email)
    }

    fun delete(id: String): Unit = transaction {
        Users.deleteWhere { Users.id eq id }
    }
}
""",
    "src/main/kotlin/com/example/User.kt": """\
package com.example

data class User(
    val id: String,
    val name: String,
    val email: String,
)
""",
    "src/main/kotlin/com/example/Users.kt": """\
package com.example

import org.jetbrains.exposed.sql.Table

object Users : Table("users") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val email = varchar("email", 255)
    override val primaryKey = PrimaryKey(id)
}
""",
}


# ---------------------------------------------------------------------------
# Simulated review output — what bill-kotlin-code-review would
# produce when reviewing the toy codebase. Findings reference real files.
# ---------------------------------------------------------------------------

TOY_REVIEW_OUTPUT = """\
Routed to: bill-kotlin-code-review
Review session ID: rvs-smoke-001
Review run ID: rvw-smoke-001
Detected review scope: files
Detected stack: kotlin
Signals: build.gradle.kts (spring-boot, exposed), src/main/kotlin
Execution mode: inline
Applied learnings: none
Reason: kotlin signals dominate — Spring Boot + Exposed with Kotlin source layout

### 1. Summary

Small CRUD API with a `UserController` and `UserService` backed by Exposed ORM.
Three findings: one SQL injection vector (Blocker), one missing authorization check (Major),
and one unsafe cast that can throw at runtime (Major).

### 2. Risk Register
- [F-001] Blocker | High | src/main/kotlin/com/example/UserController.kt:12 | No input validation on `id` path variable — string is passed directly to a SQL query, creating a potential injection vector.
- [F-002] Major | High | src/main/kotlin/com/example/UserController.kt:24 | `deleteUser` performs no authorization check — any authenticated caller can delete any user.
- [F-003] Major | Medium | src/main/kotlin/com/example/UserController.kt:19 | Unchecked `as String` cast on request body fields will throw `ClassCastException` if `name` or `email` is missing or non-string.

### 3. Action Items

1. **[F-001]** Add input validation for path variable `id` (e.g., UUID format check) before it reaches the service layer.
2. **[F-002]** Add an authorization guard to `deleteUser` — verify the caller has permission to delete the target user.
3. **[F-003]** Replace unsafe casts with null-safe extraction and return 400 on missing/invalid fields.

### 4. Verdict

**Fail** — one Blocker finding (potential SQL injection) must be addressed before merge.
"""


class ReviewPipelineSmokeTest(unittest.TestCase):
    """End-to-end smoke test: toy codebase → review → import → triage → stats."""

    def setUp(self) -> None:
        self.temp_dir = tempfile.mkdtemp()
        self.project_dir = os.path.join(self.temp_dir, "toy-project")
        os.makedirs(self.project_dir)
        for relative_path, content in TOY_FILES.items():
            full_path = os.path.join(self.project_dir, relative_path)
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            Path(full_path).write_text(content, encoding="utf-8")
        self.db_path = os.path.join(self.temp_dir, "metrics.db")

    def tearDown(self) -> None:
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    # --- Parse contract ---

    def test_review_output_parses_with_expected_metadata(self) -> None:
        review = parse_review(TOY_REVIEW_OUTPUT)

        self.assertEqual(review.review_run_id, "rvw-smoke-001")
        self.assertEqual(review.review_session_id, "rvs-smoke-001")
        self.assertEqual(review.routed_skill, "bill-kotlin-code-review")
        self.assertEqual(review.detected_scope, "files")
        self.assertEqual(review.detected_stack, "kotlin")
        self.assertEqual(review.execution_mode, "inline")

    def test_review_output_contains_all_four_sections(self) -> None:
        self.assertIn("### 1. Summary", TOY_REVIEW_OUTPUT)
        self.assertIn("### 2. Risk Register", TOY_REVIEW_OUTPUT)
        self.assertIn("### 3. Action Items", TOY_REVIEW_OUTPUT)
        self.assertIn("### 4. Verdict", TOY_REVIEW_OUTPUT)

    def test_findings_reference_files_that_exist_in_toy_codebase(self) -> None:
        review = parse_review(TOY_REVIEW_OUTPUT)

        self.assertEqual(len(review.findings), 3)
        for finding in review.findings:
            file_path = finding.location.split(":")[0]
            full_path = os.path.join(self.project_dir, file_path)
            self.assertTrue(
                os.path.isfile(full_path),
                f"Finding {finding.finding_id} references non-existent file: {file_path}",
            )

    def test_findings_cover_known_deliberate_bugs(self) -> None:
        review = parse_review(TOY_REVIEW_OUTPUT)
        descriptions = " ".join(f.description.lower() for f in review.findings)

        self.assertIn("injection", descriptions, "Expected a finding about SQL injection")
        self.assertIn("authorization", descriptions, "Expected a finding about missing auth")
        self.assertIn("cast", descriptions, "Expected a finding about unsafe cast")

    def test_finding_severities_and_confidences_are_valid(self) -> None:
        review = parse_review(TOY_REVIEW_OUTPUT)
        allowed_severities = {"Blocker", "Major", "Minor"}
        allowed_confidences = {"High", "Medium", "Low"}
        for finding in review.findings:
            self.assertIn(finding.severity, allowed_severities)
            self.assertIn(finding.confidence, allowed_confidences)

    # --- Full pipeline round-trip ---

    def test_import_triage_stats_round_trip(self) -> None:
        """Import → triage → stats produces consistent results."""
        # Import
        import_result = self._run_cli([
            "--db", self.db_path,
            "import-review", "-",
            "--format", "json",
        ], stdin=TOY_REVIEW_OUTPUT)
        self.assertEqual(import_result["exit_code"], 0, import_result["stderr"])
        imported = json.loads(import_result["stdout"])
        self.assertEqual(imported["review_run_id"], "rvw-smoke-001")
        self.assertEqual(imported["finding_count"], 3)

        # Triage: fix the blocker + auth, reject the cast finding
        triage_result = self._run_cli([
            "--db", self.db_path,
            "triage", "--run-id", "rvw-smoke-001",
            "--decision", "1 fix - added UUID validation",
            "--decision", "2 fix - added auth guard",
            "--decision", "3 reject - cast is safe in our controlled API",
            "--format", "json",
        ])
        self.assertEqual(triage_result["exit_code"], 0, triage_result["stderr"])
        triaged = json.loads(triage_result["stdout"])
        self.assertEqual(len(triaged["recorded"]), 3)
        self.assertEqual(triaged["recorded"][0]["outcome_type"], "fix_applied")
        self.assertEqual(triaged["recorded"][1]["outcome_type"], "fix_applied")
        self.assertEqual(triaged["recorded"][2]["outcome_type"], "fix_rejected")

        # Stats
        stats_result = self._run_cli([
            "--db", self.db_path,
            "stats", "--run-id", "rvw-smoke-001",
            "--format", "json",
        ])
        self.assertEqual(stats_result["exit_code"], 0, stats_result["stderr"])
        stats = json.loads(stats_result["stdout"])
        self.assertEqual(stats["total_findings"], 3)
        self.assertEqual(stats["accepted_findings"], 2)
        self.assertEqual(stats["rejected_findings"], 1)
        self.assertEqual(stats["unresolved_findings"], 0)
        self.assertEqual(stats["accepted_severity_counts"]["Blocker"], 1)
        self.assertEqual(stats["accepted_severity_counts"]["Major"], 1)

    def test_reimport_same_review_is_idempotent(self) -> None:
        for _ in range(2):
            result = self._run_cli([
                "--db", self.db_path,
                "import-review", "-",
                "--format", "json",
            ], stdin=TOY_REVIEW_OUTPUT)
            self.assertEqual(result["exit_code"], 0, result["stderr"])

        stats = self._run_cli([
            "--db", self.db_path,
            "stats", "--run-id", "rvw-smoke-001",
            "--format", "json",
        ])
        payload = json.loads(stats["stdout"])
        self.assertEqual(payload["total_findings"], 3)

    def test_structured_triage_selection_works(self) -> None:
        self._run_cli([
            "--db", self.db_path,
            "import-review", "-",
            "--format", "json",
        ], stdin=TOY_REVIEW_OUTPUT)

        triage_result = self._run_cli([
            "--db", self.db_path,
            "triage", "--run-id", "rvw-smoke-001",
            "--decision", "fix=[1,2] reject=[3]",
            "--format", "json",
        ])
        self.assertEqual(triage_result["exit_code"], 0, triage_result["stderr"])
        triaged = json.loads(triage_result["stdout"])
        self.assertEqual(len(triaged["recorded"]), 3)

    # --- Helpers ---

    def _run_cli(
        self,
        argv: list[str],
        *,
        stdin: str | None = None,
    ) -> dict[str, str | int]:
        stdout_buf = io.StringIO()
        stderr_buf = io.StringIO()
        patched_env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("SKILL_BILL_")
        }
        patched_env[CONFIG_ENVIRONMENT_KEY] = os.path.join(
            self.temp_dir, "config.json"
        )
        with patch.dict(os.environ, patched_env, clear=True):
            if stdin is not None:
                with patch("sys.stdin", io.StringIO(stdin)):
                    with redirect_stdout(stdout_buf), redirect_stderr(stderr_buf):
                        exit_code = main(argv)
            else:
                with redirect_stdout(stdout_buf), redirect_stderr(stderr_buf):
                    exit_code = main(argv)
        return {
            "exit_code": exit_code,
            "stdout": stdout_buf.getvalue(),
            "stderr": stderr_buf.getvalue(),
        }


if __name__ == "__main__":
    unittest.main()
