## [2026-08-06] SKILL-136 Subtask 6 review store integrity, outbox signal, and lifecycle
Areas: runtime-kotlin/runtime-infra-sqlite db/core + db/telemetry + sqlite goal/review, runtime-kotlin/runtime-application review + goalrunner, runtime-kotlin/runtime-infra-fs snapshot gateway, runtime-kotlin/runtime-ports review + persistence, runtime-kotlin/runtime-cli review
- `telemetry_outbox.last_error` is now nullable-as-healthy: success writes `NULL`, `TelemetryOutboxLastErrorMigration` backfills legacy empty strings, and `latestError` excludes both `NULL` and legacy-empty rows so "has an error" is a real signal. Migration is idempotent on re-application. reusable
- `learnings` is neither dead schema nor a broken promotion target (AC-002 evidence recorded in `SQLiteLearningStore` header): it is live, user-driven curated memory behind `skill-bill learnings` and `ReviewLearningsPort`; `session_learnings` is a downstream per-session cache, not an upstream source. Promotion stays explicit, never a side effect of accepting a finding. reusable
- New `review_finding_outcomes` table (keyed `review_run_id` + `finding_id`, indexed) is the shared key joining the workflow review loop's `unaddressed_findings` to imported `review_runs`/`findings`, so triage dispositions and feedback join to the routed pack. Existing ledger rows migrate as unresolved. reusable
- Accepted/rejected outcomes are now loop-recorded for every run producing findings rather than only the `feedback_events` subset; `ReviewFindingStatsSupport` LEFT JOINs outcomes so runs without them stay visible.
- `DatabaseRuntime` is the only main-source `jdbc:sqlite` connection site (enforced by `RuntimeArchitectureTest`); every open applies migrations, so a `review-metrics.db` is absent or schema-complete. Regression covers the zero-byte working-directory file. reusable
- Snapshot retention: `ReviewSnapshotPruneService` + `skill-bill prune-snapshots` are opt-in and dry-run by default; no code path deletes snapshots automatically, and the live `review-metrics.db` is excluded from candidates.
- Migrations verified row-preserving against a real 107MB store copy and an 82MB legacy snapshot fixture; referential integrity sound, outbox still drains fully.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-03] SKILL-155 read-path write-lock removal
Areas: runtime-kotlin/runtime-infra-sqlite db/core + sqlite factory, runtime-kotlin/runtime-ports persistence, runtime-kotlin/runtime-application featuretask + workflow, runtime-kotlin/runtime-cli featuretask
- `DatabaseMigrations.apply` takes an optimistic read-only pre-check (`MigrationLedger.readState`) and returns early when nothing is pending, so a read open no longer grabs the write lock just to learn there is no work. The in-lock re-derivation stays the sole authority, so concurrent applies still run each migration exactly once. reusable
- Pending-work rules: missing ledger table and a version-keyed ledger both count as pending (`ensureNameKeyed` rebuilds the table under the lock); otherwise pending is any migration name absent from `applied_names`. reusable
- `DatabaseRuntime.openReadDb` opens existing databases with `SQLiteConfig.setReadOnly(true)`; an absent database is still bootstrapped write-capable because callers rely on first-use creation. This makes read-path writes fail loudly instead of silently contending.
- New `DatabaseSessionFactory.selfManagedWrite` (abstract; a `read` default would silently degrade a write seam) is the write-capable, no-outer-transaction seam for repository methods that own their own transaction boundary and cannot nest in `transaction`. Callers previously misusing `read` for writes (worker coordinator, planning checkpoint, rejected-output CLI) moved onto it. reusable
- Contended read and no-op apply both return under 1000 ms, well inside the 5000 ms busy_timeout; revert-proofed by tests that fail with SQLITE_BUSY / a completed write when either change is reverted.
- `MigrationLedger.State` owns `hasPendingWork` (detekt LargeClass on DatabaseMigrations); no schema or contract-version change, purely a locking/capability change.
- Goal-runner write-transaction lifetime (criterion 7 determination): no transaction spans child agent execution. `JvmAgentRunProcessRunner` holds no database session; it observes progress only through injected probes. Every goal-runner store operation (`GoalRunnerWorkflowStores`, worker coordinator, phase recorder) opens, commits, and closes its own short `database.transaction` per call. The minute-long contention that defeated the 5000 ms busy_timeout came from open frequency, not a held lock: the supervisor polls progress on a 250 ms cadence (`SUPERVISOR_POLL_CADENCE_NANOS`, memoized to ~200 ms via `TICK_MEMO_WINDOW_NANOS` in `GoalRunnerTickProgressReader`), and before this fix every one of those opens ran `DatabaseMigrations.apply`'s unconditional `BEGIN IMMEDIATE` — several write-lock acquisitions per second for the whole child run, starving any reader that also needed the write lock. No separate defect exists; there is no holding call path to name. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-10] SKILL-109 Follow-up 1 telemetry reliability remediation
Areas: runtime-kotlin/runtime-infra-sqlite reconciliation/telemetry, runtime-kotlin/runtime-application telemetry sync, runtime-kotlin/runtime-ports persistence, runtime-kotlin/runtime-domain review labels, orchestration/contracts telemetry schema
- Goal-issue abandonment is gated to last-segment-blocked candidates (last_blocked_at NOT NULL AND latest_segment_workflow_id = last_blocked_segment_workflow_id); never-blocked or newer-active issues are never abandoned. reusable
- Reconciliation candidate selection extracted to StaleReconciliationCandidateQuery (single static UNION-ALL CTE, ORDER BY stale_at, LIMIT batch); partial indexes back every candidate predicate; goal-issue progress recovery reconstructs aggregates from goal_run_sessions or suppresses emission loudly (never blank first_started_at). reusable
- Pattern: manual `skill-bill telemetry sync` forces reconciliation before flush (cadenceSeconds=0, bounded batch) while autoSync keeps the independent 300s cadence guard — backlogs drain across repeated bounded runs. reusable
- feature-task prose/runtime finished payloads now emit duration_seconds (schema + MCP inputSchema kept in parity, additive, contract_version unchanged); the request-taking TelemetryReconciliationRepository method is now primary so no impl silently reverts to an unbounded transaction.
- NormalizedStackLabel normalizes mixed KMP/Kotlin labels to kmp; reliability contract tests now assert against real emitted outbox rows with genuine mutation-style negatives (aggregate accuracy, routing normalization).
Feature flag: N/A
Acceptance criteria: 18/18 implemented

## [2026-07-09] SKILL-109 terminal telemetry completeness
Areas: runtime-kotlin/runtime-infra-sqlite reconciliation/schema, runtime-kotlin/runtime-application telemetry auto-sync, runtime-kotlin/runtime-ports persistence
- StaleSessionReconciler now runs from TelemetryService.autoSync through a UnitOfWork reconciliation port before outbox sync, making production sync the bounded terminal-event repair trigger. reusable
- SQLite reconciliation selects unfinished/unemitted feature implement, feature-task-runtime, feature verify, quality check, and abandoned goal-issue rows, marks terminal state, then emits through shared finished helpers for exact-once telemetry. reusable
- Goal issue progress stores last activity and last-blocked timestamps so inactive blocked goals can emit `goal_issue_finished` with `status=abandoned`; migrations self-heal legacy rows additively.
- Pattern: late normal finishes must respect already-emitted stale terminals, while feature implement may still count duplicate finish attempts without emitting duplicate terminal telemetry.
- Known limitation: leakage target under 5% still requires post-deploy analytics confirmation after reconciled rows are present in telemetry.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-07-09] SKILL-109 reliable lifecycle duration telemetry
Areas: runtime-kotlin/runtime-infra-sqlite lifecycle migrations/tests, orchestration/contracts telemetry schema, runtime-mcp goal telemetry parity
- Lifecycle session self-heal now ensures legacy feature implement, feature verify, and quality check tables have `started_at`; feature implement blank starts are recovered from matching workflow rows before falling back to `CURRENT_TIMESTAMP`. reusable
- Feature implement finished telemetry regression coverage builds a pre-column legacy database and asserts migrated duration_seconds reflects real elapsed time instead of a blank-start near-zero value.
- Goal terminal telemetry standardizes on `duration_seconds` at the event/schema boundary while preserving internal millisecond persistence.
- Known limitation: legacy feature verify and quality check blank starts still backfill to migration time because they lack a matching workflow-start recovery source.
Feature flag: N/A
Acceptance criteria: 4/4 implemented
